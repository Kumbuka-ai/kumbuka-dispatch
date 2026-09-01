package ai.kumbuka.dispatch.boundary;

import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Probe 2 — the boundary is a missing GRANT, and both of its states are
 * observed.
 *
 * <p>Three boundaries, one mechanism. Outward: this service's role holds
 * nothing on a neighbouring service's schema. Inward: the provider's role
 * holds nothing on this service's schema. And inside: this service's own role
 * cannot TRUNCATE its own table, which is the privilege ownership used to hand
 * it implicitly and the one row-level security cannot moderate. None is a rule
 * in application code, none is a filter, and none is row-level security. Each
 * is the absence of a privilege, and the enforcing artifact is a line that
 * does not exist.
 *
 * <p><strong>Why the distinction between a refusal and an empty result is the
 * whole point.</strong> An empty result means the query ran and the database
 * decided the caller may see nothing of what is there — a filter did its job.
 * {@code permission denied} means the query did not run at all. Only the
 * second is a boundary: a filter can be misconfigured into returning rows, and
 * a filter that fails open fails silently. A privilege that was never granted
 * has no failure mode of that shape. So every assertion below insists on the
 * refusal and would reject an empty result as a pass.
 *
 * <p>Each case then grants the missing privilege, watches the access succeed,
 * and revokes it again. Without that second half the tests would pass equally
 * against a database where the table simply does not exist, or is empty, or is
 * misspelled in the query — an assurance about an absence needs a witness that
 * the absence is what is doing the work.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class MissingGrantProbeIT {

    private static final String NEIGHBOUR = SubstrateDatabaseResource.NEIGHBOUR_SCHEMA
        + "." + SubstrateDatabaseResource.NEIGHBOUR_TABLE;

    /**
     * Outward: no service reaches into another service's schema. There is no
     * foreign key, no join and no view across the boundary — and, underneath
     * all three, no privilege that would make one possible.
     */
    @Test
    void the_service_role_cannot_read_a_neighbouring_services_table() throws SQLException {
        assertRefusedThenGrantedThenRefused(
            SubstrateDatabaseResource.SERVICE_ROLE,
            SubstrateDatabaseResource.SERVICE_PASSWORD,
            "SELECT count(*) FROM " + NEIGHBOUR,
            "GRANT SELECT ON " + NEIGHBOUR + " TO " + SubstrateDatabaseResource.SERVICE_ROLE,
            "REVOKE SELECT ON " + NEIGHBOUR + " FROM " + SubstrateDatabaseResource.SERVICE_ROLE,
            "this service must not be able to read a neighbouring service's data. The "
                + "refusal is what makes the architecture's no-cross-schema-reference rule "
                + "enforced rather than merely agreed");
    }

    /**
     * Inward: the provider cannot read a tenant's exchanges.
     *
     * <p>The provider role carries BYPASSRLS, and that is deliberate. It means
     * the refusal below cannot be attributed to row-level security: this role
     * walks past every policy in the database and is still refused, because
     * the privilege it would need was never granted. If the boundary were
     * built as a policy instead, this same role would read everything.
     */
    @Test
    void the_provider_role_cannot_read_this_services_table_even_with_bypassrls()
            throws SQLException {
        assertRefusedThenGrantedThenRefused(
            SubstrateDatabaseResource.PROVIDER_ROLE,
            SubstrateDatabaseResource.PROVIDER_PASSWORD,
            "SELECT count(*) FROM dispatch.exchange",
            "GRANT USAGE ON SCHEMA dispatch TO " + SubstrateDatabaseResource.PROVIDER_ROLE
                + "; GRANT SELECT ON dispatch.exchange TO " + SubstrateDatabaseResource.PROVIDER_ROLE,
            "REVOKE SELECT ON dispatch.exchange FROM " + SubstrateDatabaseResource.PROVIDER_ROLE
                + "; REVOKE USAGE ON SCHEMA dispatch FROM " + SubstrateDatabaseResource.PROVIDER_ROLE,
            "the operator has no read path to a tenant's exchanges. The role carries "
                + "BYPASSRLS, so this refusal cannot be row-level security doing the work — "
                + "it is the absent privilege, which is the only form of the guarantee that "
                + "cannot be switched off by a configuration mistake");
    }

    /**
     * Inward again, and against this service's OWN role: it cannot TRUNCATE
     * its own table.
     *
     * <p>This is the privilege the measured defect turned on. Ownership hands
     * a role the full ACL implicitly, and TRUNCATE is the member of it that
     * bypasses row-level security completely — independently of every policy
     * and of whether {@code app.tenant_id} is bound. A runtime role holding it
     * can empty a tenant-scoped table across the tenant boundary with no part
     * of the isolation apparatus seeing it happen.
     *
     * <p>The refusal is insisted on rather than an empty table: a TRUNCATE
     * that ran and found nothing to delete looks identical in a result set and
     * is the opposite of a boundary. And the grant half matters as much — a
     * role that could not truncate because the table did not exist would pass
     * the first assertion for the wrong reason.
     *
     * <p>The statement runs inside a transaction that is rolled back either
     * way, so a successful TRUNCATE in the granted half does not empty the
     * table for whatever else this suite is doing.
     */
    @Test
    void the_service_role_cannot_truncate_its_own_table() throws SQLException {
        String table = "dispatch.exchange";
        String role = SubstrateDatabaseResource.SERVICE_ROLE;
        String password = SubstrateDatabaseResource.SERVICE_PASSWORD;

        assertThat(attemptTruncate(role, password, table))
            .as("GREEN STATE: TRUNCATE is not among the privileges V8 grants, and no "
                + "ownership hands it over implicitly any more. It is the one privilege "
                + "row-level security cannot moderate, so the only place it can be stopped "
                + "is the grant that does not exist")
            .isInstanceOf(Refused.class);

        try {
            asAdmin("GRANT TRUNCATE ON " + table + " TO " + role);

            assertThat(attemptTruncate(role, password, table))
                .as("RED STATE, observed: with TRUNCATE granted the same role runs the same "
                    + "statement and the database carries it out. So the refusal above was "
                    + "the missing privilege and not a missing table or a locked one")
                .isNotInstanceOf(Refused.class);
        } finally {
            asAdmin("REVOKE TRUNCATE ON " + table + " FROM " + role);
        }

        assertThat(attemptTruncate(role, password, table))
            .as("and closed again, so the red state was that grant and nothing else")
            .isInstanceOf(Refused.class);
    }

    /**
     * Attempts a TRUNCATE under the given role and rolls it back regardless.
     *
     * <p>Separate from {@link #attempt} because a TRUNCATE has no result to
     * read and must not be allowed to commit: folding it into the query path
     * would mean the granted half of the probe destroys the table's contents
     * to prove that it could.
     */
    private Object attemptTruncate(String role, String password, String table)
            throws SQLException {
        try (Connection c = DriverManager.getConnection(url(), role, password)) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("TRUNCATE TABLE " + table);
                return "truncated";
            } finally {
                c.rollback();
            }
        } catch (SQLException e) {
            if ("42501".equals(e.getSQLState())) {
                return new Refused(e.getMessage());
            }
            throw e;
        }
    }

    /**
     * Runs one boundary through its two states: refused, then — with the
     * privilege temporarily granted — successful, then refused again.
     *
     * @param query   a read that must be refused, and must succeed once granted
     * @param grant   the statement that opens the boundary
     * @param revoke  the statement that closes it again
     */
    private void assertRefusedThenGrantedThenRefused(
            String role, String password, String query, String grant, String revoke, String why)
            throws SQLException {

        assertThat(attempt(role, password, query))
            .as("GREEN STATE. " + why)
            .isInstanceOf(Refused.class);

        try {
            asAdmin(grant);

            Object granted = attempt(role, password, query);
            assertThat(granted)
                .as("RED STATE, observed: with the privilege granted, the same role runs the "
                    + "same query and reads the data. So the refusal above was the missing "
                    + "privilege and not a missing table, a typo, or an empty result "
                    + "misread as a boundary")
                .isNotInstanceOf(Refused.class);
        } finally {
            asAdmin(revoke);
        }

        assertThat(attempt(role, password, query))
            .as("and closed again, so the red state was the grant and nothing else")
            .isInstanceOf(Refused.class);
    }

    /**
     * Runs the query under the given role and reports what happened: a
     * {@link Refused} when the database refused it, the scalar result
     * otherwise.
     *
     * <p>Distinguishing the two is the entire assertion, so it is done on the
     * SQLState — {@code 42501 insufficient_privilege} — rather than on message
     * text, which is localised and version-dependent. Any OTHER failure is
     * rethrown rather than folded into "refused": a typo in the query would
     * otherwise read as a boundary holding.
     */
    private Object attempt(String role, String password, String query) throws SQLException {
        try (Connection c = DriverManager.getConnection(url(), role, password);
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(query)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            if ("42501".equals(e.getSQLState())) {
                return new Refused(e.getMessage());
            }
            throw e;
        }
    }

    private void asAdmin(String statements) throws SQLException {
        try (Connection c = DriverManager.getConnection(url(),
                config("test.db.admin.username"), config("test.db.admin.password"));
             Statement s = c.createStatement()) {
            for (String statement : statements.split(";")) {
                if (!statement.isBlank()) {
                    s.execute(statement);
                }
            }
        }
    }

    private static String url() {
        return config("test.db.url");
    }

    private static String config(String key) {
        return ConfigProvider.getConfig().getValue(key, String.class);
    }

    /** The database refused the statement. Not an empty result — no result. */
    private record Refused(String message) {
    }
}
