package ai.kumbuka.dispatch.tenancy;

import org.eclipse.microprofile.config.ConfigProvider;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * Raw JDBC under a named role, for probes that need to see what a role can
 * actually do.
 *
 * <p>The probes deliberately go around the ORM. Layer 1 — the Hibernate
 * tenant filter — rewrites every query it routes, so a statement that goes
 * through it can never demonstrate what layer 2 does on its own. Raw SQL is
 * the only way to ask the database directly, and asking the database directly
 * is the whole point of a policy that exists because raw SQL is possible.
 */
final class Db {

    private Db() {
    }

    /** The container superuser. Stages fixtures; never what the service uses. */
    static Connection asAdmin() throws SQLException {
        return connect(config("test.db.admin.username"), config("test.db.admin.password"));
    }

    /** CREATEROLE and nothing more — the role the migration set runs under. */
    static Connection asMigrator() throws SQLException {
        return connect(SubstrateDatabaseResource.MIGRATOR_ROLE,
            SubstrateDatabaseResource.MIGRATOR_PASSWORD);
    }

    static Connection asService() throws SQLException {
        return connect(SubstrateDatabaseResource.SERVICE_ROLE,
            SubstrateDatabaseResource.SERVICE_PASSWORD);
    }

    static Connection asProvider() throws SQLException {
        return connect(SubstrateDatabaseResource.PROVIDER_ROLE,
            SubstrateDatabaseResource.PROVIDER_PASSWORD);
    }

    private static Connection connect(String user, String password) throws SQLException {
        Connection c = DriverManager.getConnection(config("test.db.url"), user, password);
        c.setAutoCommit(false);
        return c;
    }

    private static String config(String key) {
        return ConfigProvider.getConfig().getValue(key, String.class);
    }

    /**
     * Bind, or deliberately fail to bind, the tenant GUC on this connection.
     * A null tenant resets it, which is how the fail-closed half of the
     * probes reaches the state a forgotten binding would produce.
     */
    static void bindTenant(Connection c, UUID tenant) throws SQLException {
        try (Statement s = c.createStatement()) {
            if (tenant == null) {
                s.execute("RESET app.tenant_id");
            } else {
                s.execute("SELECT set_config('app.tenant_id', '" + tenant + "', false)");
            }
        }
    }

    static long countScopes(Connection c) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM dispatch.scope")) {
            rs.next();
            return rs.getLong(1);
        }
    }

    static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    /**
     * Insert a scope row directly, bypassing the ORM, under whatever tenant
     * the GUC currently names. Used to plant rows a later read must or must
     * not see.
     */
    static UUID insertScope(Connection c, UUID tenant, String slug) throws SQLException {
        try (var st = c.prepareStatement(
                "INSERT INTO dispatch.scope (tenant_id, platform_scope_id, slug) "
              + "VALUES (?::uuid, gen_random_uuid(), ?) RETURNING id")) {
            st.setString(1, tenant.toString());
            st.setString(2, slug);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return UUID.fromString(rs.getString(1));
            }
        }
    }
}
