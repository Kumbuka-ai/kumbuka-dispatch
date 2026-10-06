package ai.kumbuka.dispatch.tenancy;

import org.eclipse.microprofile.config.ConfigProvider;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The six stop checks of V17, each seen red against a stock that holds exactly
 * the case it is about, and the abort seen to leave nothing behind.
 *
 * <h2>What each case asserts</h2>
 *
 * V17 stops with SQLSTATE {@code KD003}, and the message names the case and
 * the number of rows in it. After the abort none of the three new tables
 * exists, {@code exchange} and {@code idempotency_key} are row for row and in
 * the catalogue what they were before, {@code FORCE ROW LEVEL SECURITY}
 * included, and the Flyway history does not carry V17 as applied.
 *
 * <h2>Why the offending rows sit in the second tenant</h2>
 *
 * The migration runs bound to the configured tenant, and the checks read the
 * source with its forced security lifted. A check that read through the policy
 * would see the bound tenant's rows and none of the other's. So each case puts
 * one ordinary row into the bound tenant and its offending rows into the other,
 * and two of them, so the count in the message is a count and not a constant.
 *
 * <h2>Why the abort can leave nothing</h2>
 *
 * Flyway runs a migration on PostgreSQL inside one transaction unless the
 * migration or the configuration says otherwise, and neither does here. The
 * {@code NO FORCE} at the top of V17 is then rolled back with everything else.
 * These cases are the observation of that, not the assumption.
 */
class TaskStockCopyStopIT {

    private static final String MIGRATOR = "stop_migrator";
    private static final String MIGRATOR_PASSWORD = "test-only-stop-password";

    private static final UUID TENANT_A = UUID.fromString(config("dispatch.tenant-id"));
    private static final UUID SCOPE_A = UUID.fromString(config("dispatch.scope-id"));
    private static final UUID TENANT_B = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
    private static final UUID SCOPE_B = UUID.fromString("00000000-0000-0000-0000-0000000000c2");

    private static MigrationHarness harness;

    @BeforeAll
    static void startDatabase() throws SQLException {
        harness = MigrationHarness.start();
        harness.createMigrator(MIGRATOR, MIGRATOR_PASSWORD, "CREATEROLE NOSUPERUSER NOBYPASSRLS");
    }

    @AfterAll
    static void stopDatabase() {
        if (harness != null) {
            harness.close();
        }
    }

    // ------------------------------------------------------------------
    // The six cases, in the order V17 checks them
    // ------------------------------------------------------------------

    @Test
    void an_addendum_without_a_base_row_stops_the_copy() throws SQLException {
        stopsAndLeavesNothing("stop_no_base", (c, selector) -> {
            insert(c, selector, 60, "'a'", "'open'", "", "");
            insert(c, selector, 61, "'a'", "'open'", "", "");
        }, "2 addendum row(s) of exchange have no base row at their address");
    }

    @Test
    void an_addendum_with_a_further_text_stops_the_copy() throws SQLException {
        stopsAndLeavesNothing("stop_addendum_text", (c, selector) -> {
            insert(c, selector, 62, "NULL", "'open'", "", "");
            insert(c, selector, 62, "'a'", "'open'", ", return_body", ", 'a return on an addendum'");
            insert(c, selector, 63, "NULL", "'open'", "", "");
            insert(c, selector, 63, "'a'", "'open'", ", termination_reason", ", 'a reason on an addendum'");
        }, "2 addendum row(s) of exchange carry a text besides their own");
    }

    @Test
    void a_curation_target_in_a_status_that_does_not_close_stops_the_copy() throws SQLException {
        stopsAndLeavesNothing("stop_curated_open", (c, selector) -> {
            long target = insert(c, selector, 64, "NULL", "'closed'", "", "");
            insert(c, selector, 65, "NULL", "'open'", ", curated_into_id", ", " + target);
            insert(c, selector, 66, "NULL", "'draft'", ", curated_into_id", ", " + target);
        }, "2 exchange row(s) carry a curation target in a status that does not become closed");
    }

    @Test
    void a_curation_into_an_addendum_stops_the_copy() throws SQLException {
        stopsAndLeavesNothing("stop_curated_addendum", (c, selector) -> {
            insert(c, selector, 67, "NULL", "'closed'", "", "");
            long addendum = insert(c, selector, 67, "'a'", "'closed'", "", "");
            insert(c, selector, 68, "NULL", "'consumed'", ", curated_into_id", ", " + addendum);
            insert(c, selector, 69, "NULL", "'closed'", ", curated_into_id", ", " + addendum);
        }, "2 exchange row(s) are curated into an addendum, which becomes no task");
    }

    @Test
    void an_idempotency_record_on_an_addendum_stops_the_copy() throws SQLException {
        stopsAndLeavesNothing("stop_idempotency_addendum", (c, selector) -> {
            insert(c, selector, 70, "NULL", "'open'", "", "");
            long addendum = insert(c, selector, 70, "'a'", "'open'", "", "");
            for (String key : new String[] {"key-1", "key-2"}) {
                exec(c, "INSERT INTO dispatch.idempotency_key (tenant_id, scope_id, caller_subject, "
                    + "idempotency_key, call_name, argument_digest, exchange_id) VALUES ('" + TENANT_B
                    + "', '" + SCOPE_B + "', 'caller', '" + key + "', 'dispatch_append', 'digest', "
                    + addendum + ")");
            }
        }, "2 idempotency record(s) point at an addendum, which becomes no task");
    }

    @Test
    void a_metadata_date_other_than_the_dispatch_date_stops_the_copy() throws SQLException {
        stopsAndLeavesNothing("stop_metadata_date", (c, selector) -> {
            insert(c, selector, 71, "NULL", "'open'", ", dispatch_metadata",
                ", '{\"date\": \"2026-01-01\"}'::jsonb");
            insert(c, selector, 72, "NULL", "'open'", ", dispatch_metadata",
                ", '{\"date\": \"2026-12-24\"}'::jsonb");
            // The same date as the dispatch date is no conflict and is not counted.
            insert(c, selector, 73, "NULL", "'open'", ", dispatch_metadata",
                ", '{\"date\": \"2026-08-31\"}'::jsonb");
        }, "2 exchange row(s) carry a metadata key date that differs from their dispatch date");
    }

    // ------------------------------------------------------------------
    // The one run every case makes
    // ------------------------------------------------------------------

    private interface Stock {
        void stage(Connection c, long selectorB) throws SQLException;
    }

    private static void stopsAndLeavesNothing(String database, Stock offending, String message)
            throws SQLException {
        String url = harness.freshDatabase(database, MIGRATOR);
        harness.migrateTo(url, MIGRATOR, MIGRATOR_PASSWORD, "16", new TenantMigrationCallback());

        SourceTables.Snapshot before;
        try (Connection c = harness.adminConnection(url)) {
            exec(c, "INSERT INTO dispatch.selector (tenant_id, scope_id, name) VALUES ('"
                + TENANT_B + "', '" + SCOPE_B + "', 'sprint')");
            long selectorA = scalarLong(c, "SELECT id FROM dispatch.selector WHERE tenant_id = '"
                + TENANT_A + "' AND scope_id = '" + SCOPE_A + "' AND name = 'sprint'");
            long selectorB = scalarLong(c, "SELECT id FROM dispatch.selector WHERE tenant_id = '"
                + TENANT_B + "'");
            exec(c, "INSERT INTO dispatch.exchange (tenant_id, scope_id, selector_id, number, sub, "
                + "status, title, dispatch_body, apparatus, dispatch_date, sent_at) VALUES ('"
                + TENANT_A + "', '" + SCOPE_A + "', " + selectorA + ", 1, 0, 'open', 'ordinary', "
                + "'an ordinary commission', 'code', DATE '2026-08-31', now())");
            offending.stage(c, selectorB);
            before = SourceTables.snapshot(c);
        }
        assertThat(before.catalogue())
            .as("the stock is staged under forced row security, as a deployed database has it")
            .contains("exchange rls=true force=true")
            .contains("idempotency_key rls=true force=true");

        FlywayException stopped = catchThrowableOfType(FlywayException.class,
            () -> harness.migrate(url, MIGRATOR, MIGRATOR_PASSWORD, new TenantMigrationCallback()));

        assertThat(stopped).as("V17 must stop on this stock").isNotNull();
        PSQLException cause = rootSqlError(stopped);
        assertThat((Throwable) cause).as("the stop is a database refusal: %s", stopped.getMessage()).isNotNull();
        assertThat(cause.getSQLState()).as("the stop carries the class of V17's checks")
            .isEqualTo("KD003");
        assertThat(cause.getServerErrorMessage().getMessage())
            .as("the message names the case and the number of rows in it")
            .isEqualTo("V17 stops: " + message);

        try (Connection c = harness.adminConnection(url)) {
            assertThat(scalarLong(c, "SELECT count(*) FROM pg_class WHERE relnamespace = "
                    + "'dispatch'::regnamespace AND relname IN ('task', 'task_text', "
                    + "'task_idempotency_key')"))
                .as("none of the three new tables exists after the abort")
                .isZero();
            assertThat(SourceTables.snapshot(c))
                .as("exchange and idempotency_key row for row, and their row security, policies, "
                    + "triggers, constraints and grants, as before the abort")
                .isEqualTo(before);
            assertThat(scalarLong(c, "SELECT count(*) FROM dispatch.flyway_schema_history "
                    + "WHERE version = '17' AND success"))
                .as("the Flyway history does not carry V17 as applied")
                .isZero();
            assertThat(scalarString(c, "SELECT max(version::int)::text FROM "
                    + "dispatch.flyway_schema_history WHERE success"))
                .as("and the database stands at V16")
                .isEqualTo("16");
        }
    }

    /** One exchange row in the second tenant; extra columns and values come with a leading comma. */
    private static long insert(Connection c, long selector, int number, String suffix, String status,
                               String columns, String values) throws SQLException {
        String sentAt = "'draft'".equals(status) ? "NULL" : "now()";
        return scalarLong(c, "INSERT INTO dispatch.exchange (tenant_id, scope_id, selector_id, number, "
            + "sub, addendum_suffix, status, title, dispatch_body, apparatus, dispatch_date, sent_at"
            + columns + ") VALUES ('" + TENANT_B + "', '" + SCOPE_B + "', " + selector + ", " + number
            + ", 0, " + suffix + ", " + status + ", 'task " + number + "', 'commission " + number
            + "', 'code', DATE '2026-08-31', " + sentAt + values + ") RETURNING id");
    }

    private static PSQLException rootSqlError(Throwable t) {
        for (Throwable at = t; at != null; at = at.getCause()) {
            if (at instanceof PSQLException e) {
                return e;
            }
        }
        return null;
    }

    private static long scalarLong(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String scalarString(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static String config(String key) {
        return ConfigProvider.getConfig().getValue(key, String.class);
    }
}
