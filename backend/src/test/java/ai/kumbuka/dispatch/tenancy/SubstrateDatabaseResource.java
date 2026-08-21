package ai.kumbuka.dispatch.tenancy;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

/**
 * Boots the database the probes actually need: a real PostgreSQL, with the
 * real role shape the service runs under.
 *
 * <p><strong>Why not DevServices.</strong> A development datasource connects
 * as a superuser, and a superuser bypasses row-level security
 * unconditionally. Every isolation assertion made against it passes whether
 * the policies exist or not, so the suite would be green on a schema with the
 * security removed. The one thing this service most needs to prove is the one
 * thing that setup cannot prove.
 *
 * <p>So the container is started here and four roles are kept apart:
 *
 * <ul>
 *   <li><b>the administrator</b> — the container's own superuser. It stages
 *       the other roles and the neighbour, and the service never uses it.</li>
 *   <li><b>the migrator</b> — CREATEROLE, and deliberately nothing more.
 *       Creating the service role is the one privileged act the migration set
 *       performs; superuser would additionally hand it BYPASSRLS and make its
 *       DML untestable.</li>
 *   <li><b>the service role</b> — created by the migration itself, so the
 *       cold start is exercised rather than staged. Neither superuser nor
 *       BYPASSRLS; the policies bind it.</li>
 *   <li><b>the provider role</b> — created here, carrying BYPASSRLS, holding
 *       no grant on this service's schema. It is the operator boundary's
 *       counterparty, and BYPASSRLS is the point: a role that bypasses every
 *       policy still cannot read a table it was never granted, which is what
 *       makes the boundary a missing privilege rather than a filter.</li>
 * </ul>
 *
 * <p>A stand-in for a neighbouring service's schema is created as well — an
 * ordinary table in {@code public}, which is where the memory engine lives.
 * It is a stand-in and not a dependency: this service must not know that
 * service exists, and building a real one here would import exactly the
 * coupling the architecture forbids. What the stand-in makes observable is
 * one instance of a general claim; the general claim itself — that the
 * service role holds nothing outside its own schema — is asserted against the
 * whole catalog by {@code ServiceRoleConformanceIT}, which needs no stand-in
 * and would also see a real neighbour.
 */
public class SubstrateDatabaseResource implements QuarkusTestResourceLifecycleManager {

    /** Production major. Chosen over the newest so the gate tests what runs. */
    public static final String POSTGRES_IMAGE = "postgres:16";

    /**
     * The migrating role. CREATEROLE, and deliberately NOT a superuser.
     *
     * <p>A migrator needs exactly one privilege the service does not have —
     * the right to create the service's role — and CREATEROLE is that
     * privilege. Giving it superuser instead would hand it BYPASSRLS as a
     * side effect, and a migration that bypasses row-level security is one
     * whose DML cannot be observed failing when it forgets the tenant
     * binding. The template this service establishes therefore migrates
     * unprivileged, which is also the safer thing to hand to the next five
     * services.
     */
    public static final String MIGRATOR_ROLE = "kumbuka_dispatch_migrator";
    public static final String MIGRATOR_PASSWORD = "test-only-migrator-password";

    /** The service role. Created by V2 — NOT staged here, so the cold start is real. */
    public static final String SERVICE_ROLE = "kumbuka_dispatch";
    public static final String SERVICE_PASSWORD = "change-me-kumbuka-dispatch";

    /** The provider role. Deliberately BYPASSRLS, deliberately ungranted. */
    public static final String PROVIDER_ROLE = "kumbuka_operator";
    public static final String PROVIDER_PASSWORD = "test-only-operator-password";

    /** The neighbouring service's stand-in: schema and table it owns, we do not. */
    public static final String NEIGHBOUR_SCHEMA = "public";
    public static final String NEIGHBOUR_TABLE = "memory";

    private static PostgreSQLContainer<?> postgres;

    @Override
    public Map<String, String> start() {
        postgres = new PostgreSQLContainer<>(POSTGRES_IMAGE)
            .withDatabaseName("kumbuka")
            .withUsername("postgres_admin")
            .withPassword("test-only-admin-password");
        postgres.start();

        seedRolesAndNeighbour();

        Map<String, String> cfg = new HashMap<>();

        // The runtime connection: the service's own role, created by V2.
        cfg.put("quarkus.datasource.jdbc.url", postgres.getJdbcUrl());
        cfg.put("quarkus.datasource.username", SERVICE_ROLE);
        cfg.put("quarkus.datasource.password", SERVICE_PASSWORD);

        // The migrating connection: CREATEROLE, and nothing more than that.
        cfg.put("quarkus.flyway.jdbc-url", postgres.getJdbcUrl());
        cfg.put("quarkus.flyway.username", MIGRATOR_ROLE);
        cfg.put("quarkus.flyway.password", MIGRATOR_PASSWORD);

        // Raw-JDBC coordinates for the probes, which open their own
        // connections under each role to see what that role can actually do.
        cfg.put("test.db.url", postgres.getJdbcUrl());
        cfg.put("test.db.admin.username", postgres.getUsername());
        cfg.put("test.db.admin.password", postgres.getPassword());

        return cfg;
    }

    /**
     * Creates what the migration must not create: the migrating role itself,
     * the provider role, and a neighbouring service's table.
     *
     * <p>Neither belongs in this service's migration set. The provider role is
     * the platform's, and a service that created its own counterparty could
     * quietly grant it something. The neighbour is another service's schema,
     * and reaching into it from here is the coupling the architecture rules
     * out. Both are staged from the test harness because the assertions are
     * about relationships between things this service does not own.
     */
    private void seedRolesAndNeighbour() {
        try (Connection c = adminConnection(); Statement s = c.createStatement()) {
            // The migrator. CREATEROLE lets it create the service role in V2;
            // NOSUPERUSER NOBYPASSRLS mean its own DML is subject to the
            // policies, which is what makes a forgotten tenant binding in a
            // migration observable instead of accidentally harmless.
            s.execute("""
                DO $$ BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '%s') THEN
                        CREATE ROLE %s LOGIN CREATEROLE NOSUPERUSER NOBYPASSRLS PASSWORD '%s';
                    END IF;
                END $$;
                """.formatted(MIGRATOR_ROLE, MIGRATOR_ROLE, MIGRATOR_PASSWORD));
            s.execute("GRANT CREATE ON DATABASE " + postgres.getDatabaseName()
                + " TO " + MIGRATOR_ROLE);

            s.execute("""
                DO $$ BEGIN
                    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '%s') THEN
                        CREATE ROLE %s LOGIN BYPASSRLS PASSWORD '%s';
                    END IF;
                END $$;
                """.formatted(PROVIDER_ROLE, PROVIDER_ROLE, PROVIDER_PASSWORD));

            // The neighbour. Owned by the migrator, granted to nobody. Its
            // one row exists so that a successful read is distinguishable
            // from a permitted read that happens to find nothing.
            s.execute("CREATE TABLE IF NOT EXISTS " + NEIGHBOUR_SCHEMA + "." + NEIGHBOUR_TABLE
                + " (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), content text NOT NULL)");
            s.execute("INSERT INTO " + NEIGHBOUR_SCHEMA + "." + NEIGHBOUR_TABLE + " (content) "
                + "SELECT 'a neighbouring service owns this row' "
                + "WHERE NOT EXISTS (SELECT 1 FROM " + NEIGHBOUR_SCHEMA + "." + NEIGHBOUR_TABLE + ")");
        } catch (SQLException e) {
            throw new IllegalStateException("failed to stage the roles and the neighbour", e);
        }
    }

    private Connection adminConnection() throws SQLException {
        return DriverManager.getConnection(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @Override
    public void stop() {
        if (postgres != null) {
            postgres.stop();
            postgres = null;
        }
    }
}
