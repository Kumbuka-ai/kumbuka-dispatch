package ai.kumbuka.dispatch.tenancy;

import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The upgrade path, against the state a deployed database is actually in.
 *
 * <h2>Why this exists and what the other probes cannot say</h2>
 *
 * Every other probe here starts from a cold start, and a cold start reaches
 * the target state by doing nothing: without the ownership callback the
 * migrator already owns what it created, so V8's ownership section is a no-op
 * and never runs. The deployment is the opposite case. It has been running
 * v0.1.0 since 2026-08-23 with the callback firing after every migration, so
 * the schema and all four relations sit with the RUNTIME role — and V8's
 * ownership section is the only thing that moves them back.
 *
 * <p>That section is therefore untested by everything else in this suite,
 * while being the part of this change that touches production data. So this
 * probe reproduces the deployed state rather than describing it: it applies
 * V1..V7 — what shipped — then performs the handover the shipped code performed
 * at runtime, checks it is standing in the measured defect, and only then lets
 * V8 run.
 *
 * <h2>What building it found</h2>
 *
 * The first version of this probe HUNG, and so would the deploy. Flyway keeps
 * its schema-history connection open in a transaction for the whole run,
 * holding ACCESS SHARE on {@code dispatch.flyway_schema_history}; the
 * {@code ALTER TABLE … OWNER TO} that would move that one table needs ACCESS
 * EXCLUSIVE and waits for a lock that is not released until the run it is part
 * of has finished. Measured 2026-09-01 on PostgreSQL 16.13, from
 * {@code pg_locks}: the migration's own backend held AccessExclusiveLock on
 * {@code exchange} and waited, ungranted, on {@code flyway_schema_history}.
 *
 * <p>So the history table cannot be moved by a migration at all, and V8
 * refuses instead of waiting. The two cases below are that refusal and the
 * upgrade that follows the one operator statement it asks for.
 */
class UpgradeFromOwnedSchemaIT {

    private static final String MIGRATOR = "upgrade_migrator";
    private static final String MIGRATOR_PASSWORD = "test-only-upgrade-password";

    /** What an owner holds implicitly, and what the deployment was measured holding. */
    private static final List<String> FULL_PRIVILEGE_SET = List.of(
        "SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER");

    /** What V8 leaves a domain table with. */
    private static final List<String> ENUMERATED = List.of("SELECT", "INSERT", "UPDATE");

    private static final List<String> DOMAIN_TABLES =
        List.of("exchange", "selector", "number_circle");
    private static final String HISTORY_TABLE = "flyway_schema_history";

    /** The application-defined SQLSTATE V8 raises when it cannot take a relation back. */
    private static final String REFUSAL_SQLSTATE = "KD002";

    /** The one statement V8's message asks the operator for. */
    private static final String OPERATOR_STATEMENT =
        "ALTER TABLE dispatch." + HISTORY_TABLE + " OWNER TO " + MIGRATOR;

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

    /**
     * Reproduces what the deployment is standing in: v0.1.0's chain, plus the
     * handover its ownership callback performed at runtime.
     *
     * @return the JDBC url of a database in that state
     */
    private static String stageDeployedState(String database) throws SQLException {
        String url = harness.freshDatabase(database, MIGRATOR);

        // 1. What shipped: the chain as tag v0.1.0 carried it.
        harness.migrateTo(url, MIGRATOR, MIGRATOR_PASSWORD, "7", new TenantMigrationCallback());

        // 2. What the shipped code then did, in the order SchemaOwnershipCallback
        //    did it: the schema first, because the incoming owner of a relation
        //    must hold CREATE on its schema, then every relation of a kind that
        //    carries an owner. The history table is included exactly as the
        //    sweep included it — it lives in this schema, and that is the whole
        //    reason it travelled.
        List<String> handover = new ArrayList<>();
        handover.add("ALTER SCHEMA dispatch OWNER TO " + SubstrateDatabaseResource.SERVICE_ROLE);
        for (String table : allRelations()) {
            handover.add("ALTER TABLE dispatch." + table + " OWNER TO "
                + SubstrateDatabaseResource.SERVICE_ROLE);
        }
        harness.asMigrator(MIGRATOR, MIGRATOR_PASSWORD, url, handover.toArray(String[]::new));

        return url;
    }

    /**
     * The measured defect, reproduced — and V8 refusing to paper over the one
     * part of it a migration cannot reach.
     */
    @Test
    void the_upgrade_refuses_while_the_history_table_belongs_to_the_runtime_role()
            throws SQLException {
        String url = stageDeployedState("upgrade_without_operator_step");

        try (Connection c = harness.adminConnection(url)) {
            assertThat(ownerOfSchema(c))
                .as("the reproduced state must be the deployed one: the runtime role holds "
                    + "the schema. If it did not, everything below would be measuring a "
                    + "cold start with extra steps")
                .isEqualTo(SubstrateDatabaseResource.SERVICE_ROLE);

            for (String relation : allRelations()) {
                assertThat(heldPrivileges(c, relation))
                    .as("MEASURED DEFECT, reproduced: the runtime role holds the FULL "
                        + "privilege set on %s — with no GRANT anywhere in V1..V7 to show "
                        + "for it, because ownership confers it implicitly. TRUNCATE is in "
                        + "there, and TRUNCATE bypasses row-level security entirely",
                        relation)
                    .containsExactlyInAnyOrderElementsOf(FULL_PRIVILEGE_SET);
            }
        }

        assertThatThrownBy(() -> harness.migrateTo(url, MIGRATOR, MIGRATOR_PASSWORD, null,
                new TenantMigrationCallback()))
            .as("RED STATE, observed: V8 will not wait on a lock it can never get, and will "
                + "not carry on with the history table left where it is. It refuses, and "
                + "the message carries the one statement that resolves it")
            .isInstanceOf(FlywayException.class)
            .satisfies(thrown -> {
                assertThat(sqlStateOf(thrown))
                    .as("matched on the application-defined SQLSTATE rather than on message "
                        + "text, which is localised and version-dependent")
                    .isEqualTo(REFUSAL_SQLSTATE);
                assertThat(thrown.getMessage())
                    .as("and the message must name the statement to run, because a refusal "
                        + "that only says 'no' leaves the operator to work it out at deploy "
                        + "time")
                    .contains("ALTER TABLE dispatch." + HISTORY_TABLE + " OWNER TO");
            });

        // Nothing half-applied: PostgreSQL rolls DDL back with the transaction,
        // so a refused V8 leaves the schema exactly as it was and the retry
        // after the operator statement starts from the same place.
        try (Connection c = harness.adminConnection(url)) {
            assertThat(scalar(c, "SELECT count(*) FROM dispatch." + HISTORY_TABLE
                + " WHERE success AND version = '8'"))
                .as("V8 must not be recorded as applied — a chain that refuses and then "
                    + "marks itself done would refuse exactly once, ever")
                .isEqualTo("0");
            assertThat(ownerOfSchema(c))
                .as("and the ownership it did change before refusing is rolled back with it")
                .isEqualTo(SubstrateDatabaseResource.SERVICE_ROLE);
        }
    }

    /**
     * And with that one statement run, the upgrade completes and leaves the
     * target state.
     */
    @Test
    void the_upgrade_takes_ownership_back_and_leaves_the_enumerated_entitlement()
            throws SQLException {
        String url = stageDeployedState("upgrade_with_operator_step");

        // The operator's statement, run with no migration in progress — which
        // is the condition the refusal names and the reason it can succeed
        // here while the same statement inside a migration cannot.
        harness.asMigrator(MIGRATOR, MIGRATOR_PASSWORD, url, OPERATOR_STATEMENT);

        harness.migrateTo(url, MIGRATOR, MIGRATOR_PASSWORD, null, new TenantMigrationCallback());

        try (Connection c = harness.adminConnection(url)) {
            assertThat(ownerOfSchema(c))
                .as("the migrator has the schema back. Without this step first, PostgreSQL "
                    + "would refuse every relation handover with a message about the schema "
                    + "rather than about the table")
                .isEqualTo(MIGRATOR);

            assertThat(relationsNotOwnedBy(c, MIGRATOR))
                .as("and every relation with it, the history table included")
                .isEmpty();

            for (String table : DOMAIN_TABLES) {
                assertThat(heldPrivileges(c, table))
                    .as("%s carries exactly the enumerated grant afterwards. No TRUNCATE, "
                        + "no TRIGGER, no REFERENCES — and no DELETE, because no verb in "
                        + "this service deletes", table)
                    .containsExactlyInAnyOrderElementsOf(ENUMERATED);
            }

            assertThat(heldPrivileges(c, HISTORY_TABLE))
                .as("and nothing whatever on the migrator's own record. A runtime role that "
                    + "can rewrite it can make the schema lie about its own version")
                .isEmpty();

            assertThat(hasSchemaPrivilege(c, "USAGE"))
                .as("USAGE remains, or the enumerated table grants would be unreachable and "
                    + "the upgraded service would not run")
                .isTrue();
            assertThat(hasSchemaPrivilege(c, "CREATE"))
                .as("CREATE does not, or the runtime role could add a table and own it")
                .isFalse();

            assertThat(scalar(c, "SELECT count(*) FROM dispatch.selector"))
                .as("the declaration V5 wrote is still there — an upgrade that repaired the "
                    + "privileges by losing the data would pass every assertion above")
                .isEqualTo("2");
        }
    }

    // ------------------------------------------------------------------

    private static List<String> allRelations() {
        List<String> all = new ArrayList<>(DOMAIN_TABLES);
        all.add(HISTORY_TABLE);
        return all;
    }

    /**
     * Which of PostgreSQL's table privileges the runtime role holds, asked one
     * at a time through {@code has_table_privilege} — which answers for
     * ownership and for grants alike, and is therefore the only form that can
     * measure both states of this probe with one question.
     */
    private static List<String> heldPrivileges(Connection c, String relation)
            throws SQLException {
        List<String> held = new ArrayList<>();
        for (String privilege : FULL_PRIVILEGE_SET) {
            try (var st = c.prepareStatement("SELECT has_table_privilege(?, ?, ?)")) {
                st.setString(1, SubstrateDatabaseResource.SERVICE_ROLE);
                st.setString(2, "dispatch." + relation);
                st.setString(3, privilege);
                try (ResultSet rs = st.executeQuery()) {
                    rs.next();
                    if (rs.getBoolean(1)) {
                        held.add(privilege);
                    }
                }
            }
        }
        return held;
    }

    private static boolean hasSchemaPrivilege(Connection c, String privilege)
            throws SQLException {
        try (var st = c.prepareStatement("SELECT has_schema_privilege(?, 'dispatch', ?)")) {
            st.setString(1, SubstrateDatabaseResource.SERVICE_ROLE);
            st.setString(2, privilege);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    private static String ownerOfSchema(Connection c) throws SQLException {
        return scalar(c, "SELECT pg_get_userbyid(nspowner) FROM pg_namespace "
            + "WHERE nspname = 'dispatch'");
    }

    private static List<String> relationsNotOwnedBy(Connection c, String owner)
            throws SQLException {
        List<String> out = new ArrayList<>();
        try (var st = c.prepareStatement("""
                SELECT cl.relname || ' [owner ' || pg_get_userbyid(cl.relowner) || ']'
                FROM pg_class cl
                JOIN pg_namespace n ON n.oid = cl.relnamespace
                WHERE n.nspname = 'dispatch'
                  AND cl.relkind IN ('r','v','m','S','p')
                  AND pg_get_userbyid(cl.relowner) <> ?
                ORDER BY cl.relname
                """)) {
            st.setString(1, owner);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    /**
     * Walks the cause chain for a {@link SQLException} and reports its
     * SQLSTATE. Flyway wraps the driver's exception more than once, and the
     * depth is not something this probe should be asserting about.
     */
    private static String sqlStateOf(Throwable thrown) {
        for (Throwable t = thrown; t != null; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return "no SQLException in the cause chain of: " + thrown;
    }

    private static String scalar(Connection c, String sql) throws SQLException {
        try (var s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
