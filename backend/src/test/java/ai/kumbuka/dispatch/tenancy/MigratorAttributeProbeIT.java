package ai.kumbuka.dispatch.tenancy;

import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The migrating role carries no policy exemption, and the chain refuses to
 * apply under one that does.
 *
 * <h2>Why this is a mechanism and not a sentence in a comment</h2>
 *
 * Under enumerated grants the migrator owns every relation of this schema and
 * will own every view it ever gains. A view without {@code security_invoker}
 * reads its base tables with its OWNER's privileges, and a superuser or a
 * {@code BYPASSRLS} role is exempt from {@code FORCE ROW LEVEL SECURITY}
 * regardless. So the exemption question does not disappear when ownership
 * moves off the runtime role — it moves one role along, onto the migrator,
 * where its failure mode is worse: for a table a wrongly-privileged owner is a
 * loud privilege defect, for a view it is a SILENT confidentiality defect.
 * Rows come back, nothing is raised, every test is green.
 *
 * <p>V8 therefore reads {@code rolsuper} and {@code rolbypassrls} of the role
 * it is running as and REFUSES. It does not repair: stripping either attribute
 * is a superuser act, this chain migrates with CREATEROLE and nothing more,
 * and a migration that could quietly remove a security attribute could quietly
 * add one. Role lifecycle belongs to the bootstrap.
 *
 * <h2>What this probe stages, and what it deliberately does not</h2>
 *
 * The runtime role is created here rather than by V2. The cold start — V2
 * creating it against an empty database — is what {@link ColdStartIT}
 * observes; staging it here is what makes the two cases below independent of
 * each other's ordering, since roles are cluster-wide and only the first run
 * would otherwise create it and hold ADMIN OPTION on it.
 */
class MigratorAttributeProbeIT {

    /** Neither superuser nor BYPASSRLS: the shape a deployment supplies. */
    private static final String CLEAN_MIGRATOR = "attr_clean_migrator";

    /**
     * The throwaway role. CREATEROLE plus the one attribute under test —
     * BYPASSRLS rather than SUPERUSER, because it is the narrower of the two
     * and a refusal that fires on it fires on both.
     */
    private static final String EXEMPT_MIGRATOR = "attr_exempt_migrator";

    private static final String PASSWORD = "test-only-attribute-probe-password";

    /** The application-defined SQLSTATE V8 raises. Matched instead of message text. */
    private static final String REFUSAL_SQLSTATE = "KD001";

    private static MigrationHarness harness;

    @BeforeAll
    static void startDatabase() throws SQLException {
        harness = MigrationHarness.start();

        harness.createMigrator(CLEAN_MIGRATOR, PASSWORD, "CREATEROLE NOSUPERUSER NOBYPASSRLS");
        harness.createMigrator(EXEMPT_MIGRATOR, PASSWORD, "CREATEROLE NOSUPERUSER BYPASSRLS");

        // The runtime role, staged so that neither case depends on the other
        // having run first. V2 finds it present, checks its attributes and
        // takes the membership it needs; both migrators hold ADMIN OPTION so
        // that check passes for either of them.
        harness.asAdmin(
            "CREATE ROLE " + SubstrateDatabaseResource.SERVICE_ROLE + " LOGIN NOSUPERUSER "
                + "NOBYPASSRLS PASSWORD '" + SubstrateDatabaseResource.SERVICE_PASSWORD + "'",
            "GRANT " + SubstrateDatabaseResource.SERVICE_ROLE + " TO " + CLEAN_MIGRATOR
                + " WITH ADMIN OPTION",
            "GRANT " + SubstrateDatabaseResource.SERVICE_ROLE + " TO " + EXEMPT_MIGRATOR
                + " WITH ADMIN OPTION");
    }

    @AfterAll
    static void stopDatabase() {
        if (harness != null) {
            harness.close();
        }
    }

    /**
     * The green state: a migrator carrying neither attribute applies the whole
     * chain, and the schema it leaves behind is owned by it.
     *
     * <p>Both halves are the probe. The refusal below alone would hold equally
     * against a chain that is broken for some other reason entirely.
     */
    @Test
    void an_unprivileged_migrator_applies_the_chain_and_keeps_what_it_created()
            throws SQLException {
        String url = harness.freshDatabase("attr_clean", CLEAN_MIGRATOR);

        harness.migrate(url, CLEAN_MIGRATOR, PASSWORD, new TenantMigrationCallback());

        try (Connection c = harness.adminConnection(url)) {
            assertThat(scalar(c, "SELECT pg_get_userbyid(nspowner) FROM pg_namespace "
                + "WHERE nspname = 'dispatch'"))
                .as("the migrator keeps the schema — nothing hands it over any more")
                .isEqualTo(CLEAN_MIGRATOR);

            assertThat(scalar(c, """
                SELECT coalesce(string_agg(c.relname, ', '), '')
                FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'dispatch'
                  AND c.relkind IN ('r','v','m','S','p')
                  AND pg_get_userbyid(c.relowner) <> '%s'
                """.formatted(CLEAN_MIGRATOR)))
                .as("and every relation in it, the Flyway history table included")
                .isEmpty();

            assertThat(scalar(c, "SELECT has_table_privilege('"
                + SubstrateDatabaseResource.SERVICE_ROLE + "', 'dispatch.exchange', 'TRUNCATE')"))
                .as("while the runtime role holds only what V8 wrote out — TRUNCATE is "
                    + "the one an owner would have had implicitly")
                .isEqualTo("f");
        }
    }

    /**
     * The red state: the same chain, the same files, a migrator carrying
     * BYPASSRLS. The refusal must fire.
     *
     * <p>This is what makes point 7 of the target state a mechanism rather
     * than a description. Without it the check in V8 would be a block of SQL
     * nobody has ever seen do anything.
     */
    @Test
    void a_migrator_carrying_bypassrls_is_refused_by_the_chain() throws SQLException {
        String url = harness.freshDatabase("attr_exempt", EXEMPT_MIGRATOR);

        assertThatThrownBy(() -> harness.migrate(url, EXEMPT_MIGRATOR, PASSWORD,
                new TenantMigrationCallback()))
            .as("RED STATE, observed: a migrator that can bypass every policy owns every "
                + "view this schema will ever have, and a view reads its base tables with "
                + "its owner's privileges. The chain must stop rather than build a schema "
                + "whose isolation cannot hold")
            .isInstanceOf(FlywayException.class)
            .satisfies(thrown -> assertThat(sqlStateOf(thrown))
                .as("and it must be THIS refusal rather than any other failure: the "
                    + "application-defined SQLSTATE is what distinguishes it, because "
                    + "message text is localised and version-dependent")
                .isEqualTo(REFUSAL_SQLSTATE));

        // And the refusal left nothing half-built that a retry would trip over.
        try (Connection c = harness.adminConnection(url)) {
            assertThat(scalar(c, "SELECT count(*) FROM dispatch.flyway_schema_history "
                + "WHERE success AND version = '8'"))
                .as("V8 must not be recorded as applied — a chain that refuses and then "
                    + "marks itself done would refuse exactly once, ever")
                .isEqualTo("0");
        }
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
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
