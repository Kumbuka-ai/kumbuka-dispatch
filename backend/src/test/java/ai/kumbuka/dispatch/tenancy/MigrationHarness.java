package ai.kumbuka.dispatch.tenancy;

import org.eclipse.microprofile.config.ConfigProvider;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.callback.Callback;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

/**
 * Drives the service's real migration set against a container of the caller's
 * own, under a role of the caller's choosing.
 *
 * <p>Two probes need this and neither can use the ordinary test datasource.
 * {@link MigrationCallbackWitnessIT} varies which callbacks are registered,
 * which is build-time configuration a running application cannot change.
 * {@link MigratorAttributeProbeIT} varies the ATTRIBUTES of the migrating
 * role, which is a property of a role the harness would otherwise have to
 * mutate underneath a booted service. Both therefore run Flyway directly —
 * over the same migration files the service ships, so what is observed is the
 * real migration set rather than a fixture resembling it.
 *
 * <p>Each case migrates a database of its own. Sharing one would let whichever
 * ran first apply the set, and the second would find everything applied, do
 * nothing, and report success — so a negative case would pass without ever
 * attempting the statement it is about.
 */
final class MigrationHarness implements AutoCloseable {

    private final PostgreSQLContainer<?> postgres;

    private MigrationHarness(PostgreSQLContainer<?> postgres) {
        this.postgres = postgres;
    }

    /** Starts an empty PostgreSQL of the production major. */
    static MigrationHarness start() {
        PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(SubstrateDatabaseResource.POSTGRES_IMAGE)
                .withDatabaseName("kumbuka")
                .withUsername("postgres_admin")
                .withPassword("test-only-admin-password");
        postgres.start();
        return new MigrationHarness(postgres);
    }

    /**
     * Creates a migrating role with the attributes named.
     *
     * @param attributes the role attributes verbatim, e.g.
     *                   {@code "CREATEROLE NOSUPERUSER NOBYPASSRLS"}. Written
     *                   out by the caller rather than defaulted, because in
     *                   one of these probes the attributes ARE the variable
     *                   under test and a default would hide it.
     */
    void createMigrator(String role, String password, String attributes) throws SQLException {
        asAdmin("CREATE ROLE " + role + " LOGIN " + attributes + " PASSWORD '" + password + "'");
    }

    /** Runs statements as the container superuser, for staging only. */
    void asAdmin(String... statements) throws SQLException {
        try (Connection c = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement s = c.createStatement()) {
            for (String statement : statements) {
                s.execute(statement);
            }
        }
    }

    /**
     * An empty database of this case's own, with the migrator able to create
     * in it. Returns its JDBC url.
     */
    String freshDatabase(String name, String migrator) throws SQLException {
        asAdmin("DROP DATABASE IF EXISTS " + name,
                "CREATE DATABASE " + name,
                "GRANT CREATE ON DATABASE " + name + " TO " + migrator);
        return postgres.getJdbcUrl().replace("/kumbuka?", "/" + name + "?");
    }

    /** The container superuser's own connection, for reading the catalog back. */
    Connection adminConnection(String url) throws SQLException {
        return DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
    }

    /**
     * Runs the service's migration set: the real files, the real placeholders,
     * and exactly the callbacks the caller names.
     */
    void migrate(String url, String migrator, String password, Callback... callbacks) {
        migrateTo(url, migrator, password, null, callbacks);
    }

    /**
     * The same, stopping at a named version.
     *
     * <p>One probe needs the chain to stop short: reproducing the state a
     * DEPLOYED database is in means applying what shipped and no more, then
     * staging what the shipped code did at runtime, and only then letting the
     * next migration run against it. Without a stopping point that probe would
     * have to describe the old state instead of standing in it.
     *
     * @param target the version to stop at, or null for all of them
     */
    void migrateTo(String url, String migrator, String password, String target,
                   Callback... callbacks) {
        var config = ConfigProvider.getConfig();
        var flyway = Flyway.configure()
            .dataSource(url, migrator, password)
            .schemas("dispatch")
            .defaultSchema("dispatch")
            .createSchemas(true)
            .locations("classpath:db/migration")
            .placeholders(Map.of(
                "dispatchTenantId", config.getValue("dispatch.tenant-id", String.class),
                "dispatchScopeId", config.getValue("dispatch.scope-id", String.class)))
            .callbacks(callbacks);
        if (target != null) {
            flyway = flyway.target(org.flywaydb.core.api.MigrationVersion.fromVersion(target));
        }
        flyway.load().migrate();
    }

    /** Runs statements as the migrating role, for staging a pre-upgrade state. */
    void asMigrator(String migrator, String password, String url, String... statements)
            throws SQLException {
        try (Connection c = DriverManager.getConnection(url, migrator, password);
             Statement s = c.createStatement()) {
            for (String statement : statements) {
                s.execute(statement);
            }
        }
    }

    @Override
    public void close() {
        postgres.stop();
    }
}
