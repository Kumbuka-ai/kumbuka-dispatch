package ai.kumbuka.dispatch.tenancy;

import org.eclipse.microprofile.config.ConfigProvider;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Witnesses the tenant-binding Flyway callback — the first time it is observed
 * directly rather than assumed.
 *
 * <h2>Why this needed its own test</h2>
 *
 * The Quarkus Flyway extension resolves callbacks from
 * {@code quarkus.flyway.callbacks} by class name and instantiates them
 * reflectively. It does <strong>not</strong> discover them as CDI beans. A
 * callback that is written, annotated and never named in that line is simply
 * never registered — with no warning, no error, and migrations that run
 * happily without it.
 *
 * <p>That failure is invisible while every migration is pure DDL, because
 * row-level security filters DML only. The service in which this pattern was
 * first written carried such a callback for a long time; its configuration key
 * was never set, and nothing noticed, because it had no migration that would
 * have needed it.
 *
 * <p>So the observation has to be made where it can fail: against a real
 * migration carrying real DML, run twice — once with the callback registered
 * and once without.
 *
 * <h2>Why Flyway is driven directly here</h2>
 *
 * The callback list is build-time configuration, and a running application
 * cannot un-register one. Driving Flyway against a container of this test's
 * own is what makes the negative case reachable at all — and it runs the
 * SAME migration files the service ships, so the thing being witnessed is the
 * real migration set rather than a fixture that resembles it.
 */
class MigrationCallbackWitnessIT {

    private static final String MIGRATOR = "witness_migrator";
    private static final String MIGRATOR_PASSWORD = "test-only-witness-password";

    private static PostgreSQLContainer<?> postgres;

    @BeforeAll
    static void startDatabase() throws SQLException {
        postgres = new PostgreSQLContainer<>(SubstrateDatabaseResource.POSTGRES_IMAGE)
            .withDatabaseName("kumbuka")
            .withUsername("postgres_admin")
            .withPassword("test-only-admin-password");
        postgres.start();

        // The migrating role: CREATEROLE and, critically, NOT BYPASSRLS. A
        // privileged migrator would walk past the policy, and the negative
        // case below would pass for the wrong reason — the DML would succeed
        // whether the callback ran or not.
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword());
             Statement s = c.createStatement()) {
            s.execute("CREATE ROLE " + MIGRATOR + " LOGIN CREATEROLE NOSUPERUSER "
                + "NOBYPASSRLS PASSWORD '" + MIGRATOR_PASSWORD + "'");
        }
    }

    /**
     * A database of this case's own.
     *
     * <p>Each case has to migrate from nothing. Sharing one database would let
     * whichever ran first apply the migration set, and the second would find
     * everything already applied, do nothing, and report success — so the
     * negative case would pass without ever attempting the write it is about.
     */
    private static String freshDatabase(String name) throws SQLException {
        try (Connection c = DriverManager.getConnection(postgres.getJdbcUrl(),
                postgres.getUsername(), postgres.getPassword());
             Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + name);
            s.execute("CREATE DATABASE " + name);
            s.execute("GRANT CREATE ON DATABASE " + name + " TO " + MIGRATOR);
        }
        return postgres.getJdbcUrl().replace("/kumbuka?", "/" + name + "?");
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    /**
     * The red state: without the callback, the migration carrying DML fails.
     *
     * <p>It fails at the policy, and the message says so. That is better than
     * the alternative the dispatch allowed for — writing zero rows and
     * reporting success — because a migration that succeeds while writing
     * nothing leaves the deployment looking healthy and the declaration
     * missing.
     */
    @Test
    void without_the_callback_the_dml_migration_is_refused_by_the_policy() throws SQLException {
        String url = freshDatabase("witness_without_callback");

        assertThatThrownBy(() -> migrate(url, false))
            .as("RED STATE, observed: with the callback absent from the configuration, "
                + "app.tenant_id is never bound, the WITH CHECK clause compares the "
                + "incoming row against nothing, and the declaration cannot be written. "
                + "This is the failure that stayed invisible for as long as every "
                + "migration was pure DDL")
            .isInstanceOf(FlywayException.class)
            .hasMessageContaining("row-level security");
    }

    /**
     * The green state: with the callback registered, the same migration set
     * applies and the declaration is there.
     *
     * <p>Both halves are the probe. The red state alone would hold against a
     * migration that is broken for some other reason entirely.
     */
    @Test
    void with_the_callback_the_same_migration_applies_and_declares_the_selectors()
            throws SQLException {
        String url = freshDatabase("witness_with_callback");
        migrate(url, true);

        try (Connection c = DriverManager.getConnection(url,
                postgres.getUsername(), postgres.getPassword());
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                 "SELECT name FROM dispatch.selector ORDER BY name")) {
            var declared = new java.util.ArrayList<String>();
            while (rs.next()) {
                declared.add(rs.getString(1));
            }
            assertThat(declared)
                .as("and with it registered the declaration lands — so the refusal above "
                    + "was the missing callback and not a broken migration")
                .containsExactly("satellite", "sprint");
        }
    }

    /**
     * Runs the service's real migration set against this test's container.
     *
     * @param url          the database of this case
     * @param withCallback whether to register the tenant-binding callback, which
     *                     is the single variable this probe changes
     */
    private static void migrate(String url, boolean withCallback) {
        var config = ConfigProvider.getConfig();
        var flyway = Flyway.configure()
            .dataSource(url, MIGRATOR, MIGRATOR_PASSWORD)
            .schemas("dispatch")
            .defaultSchema("dispatch")
            .createSchemas(true)
            .locations("classpath:db/migration")
            .placeholders(Map.of(
                "dispatchTenantId", config.getValue("dispatch.tenant-id", String.class),
                "dispatchScopeId", config.getValue("dispatch.scope-id", String.class)))
            // The ownership callback is registered in both cases: it is not the
            // variable under test, and without it the migrator would not hand
            // the schema over and the run would fail for an unrelated reason.
            .callbacks(withCallback
                ? new org.flywaydb.core.api.callback.Callback[] {
                    new TenantMigrationCallback(), new SchemaOwnershipCallback() }
                : new org.flywaydb.core.api.callback.Callback[] {
                    new SchemaOwnershipCallback() })
            .load();
        flyway.migrate();
    }
}
