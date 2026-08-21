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
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The conformance probe: this service's role holds rights on its own schema
 * and on nothing else.
 *
 * <p>{@link MissingGrantProbeIT} observes one boundary against one
 * neighbouring table. This asks the question the architecture actually poses
 * — <em>does this role hold anything it should not</em> — of the whole
 * catalog, so it also covers the neighbour that does not exist yet, the view
 * somebody grants next year, and the schema this service has never heard of.
 * Each service carries a probe of this shape; this is that probe for this
 * service.
 *
 * <p>Two things are asserted rather than one. That the role holds nothing
 * outside its schema, and that it holds something inside it — a role granted
 * nothing anywhere would pass the first assertion perfectly while being
 * unable to run the service.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ServiceRoleConformanceIT {

    private static final String SERVICE_ROLE = SubstrateDatabaseResource.SERVICE_ROLE;

    /**
     * Schemas the role may hold something in. The service's own, and — when a
     * platform read contract is consumed — that one view and nothing beside
     * it. The list is here rather than inferred so that widening it is an
     * edit someone has to make and explain.
     */
    private static final List<String> PERMITTED_SCHEMAS = List.of("dispatch");

    @Test
    void the_service_role_holds_nothing_outside_its_own_schema() throws SQLException {
        assertThat(foreignHoldings())
            .as("the service role must hold nothing — owned or granted — outside %s. Anything "
                + "listed here is a route from this service into another service's data, and "
                + "the architecture's isolation rests on there being no such route rather than "
                + "on nobody taking it", PERMITTED_SCHEMAS)
            .isEmpty();
    }

    /**
     * The red state of the check above, observed on every build.
     *
     * <p>"The role holds nothing outside its schema" is a claim about an
     * empty query result, and an empty result is also what a query against
     * the wrong catalog, the wrong role name or the wrong column would
     * return. So a privilege is granted here that should not exist, the check
     * is required to name it, and it is revoked again.
     */
    @Test
    void the_check_finds_a_privilege_that_should_not_exist() throws SQLException {
        String neighbour = SubstrateDatabaseResource.NEIGHBOUR_SCHEMA
            + "." + SubstrateDatabaseResource.NEIGHBOUR_TABLE;
        try (Connection c = admin()) {
            try {
                c.createStatement().execute(
                    "GRANT SELECT ON " + neighbour + " TO " + SERVICE_ROLE);

                assertThat(foreignHoldings())
                    .as("RED STATE, observed: a single GRANT on a neighbouring service's "
                        + "table must show up here. If it did not, the empty result above "
                        + "would be a query that finds nothing rather than a role that "
                        + "holds nothing")
                    .anyMatch(holding -> holding.contains(SubstrateDatabaseResource.NEIGHBOUR_TABLE));
            } finally {
                c.createStatement().execute(
                    "REVOKE SELECT ON " + neighbour + " FROM " + SERVICE_ROLE);
            }
        }

        assertThat(foreignHoldings())
            .as("and gone again, so the red state was the grant and nothing else")
            .isEmpty();
    }

    /** Everything the service role owns or was granted outside its own schema. */
    private static List<String> foreignHoldings() throws SQLException {
        List<String> foreign = new ArrayList<>();

        try (Connection c = admin()) {
            // Ownership and explicit grants are two different ways to hold a
            // privilege, and asking about only one of them would miss the
            // other entirely. pg_class covers what the role owns; the
            // information_schema view covers what it was granted.
            try (var st = c.prepareStatement("""
                    SELECT n.nspname, cl.relname, 'owns'
                    FROM pg_class cl
                    JOIN pg_namespace n ON n.oid = cl.relnamespace
                    WHERE pg_get_userbyid(cl.relowner) = ?
                      AND n.nspname NOT IN ('pg_catalog', 'information_schema')
                      AND cl.relkind IN ('r','v','m','S','p')
                    UNION
                    SELECT g.table_schema, g.table_name, string_agg(DISTINCT g.privilege_type, ',')
                    FROM information_schema.role_table_grants g
                    WHERE g.grantee = ?
                      AND g.table_schema NOT IN ('pg_catalog', 'information_schema')
                    GROUP BY g.table_schema, g.table_name
                    """)) {
                st.setString(1, SERVICE_ROLE);
                st.setString(2, SERVICE_ROLE);
                try (ResultSet rs = st.executeQuery()) {
                    while (rs.next()) {
                        String schema = rs.getString(1);
                        if (!PERMITTED_SCHEMAS.contains(schema)) {
                            foreign.add(schema + "." + rs.getString(2) + " [" + rs.getString(3) + "]");
                        }
                    }
                }
            }
        }
        return foreign;
    }

    @Test
    void the_service_role_does_hold_its_own_schema() throws SQLException {
        try (Connection c = admin();
             var st = c.prepareStatement("""
                 SELECT count(*)
                 FROM pg_class cl
                 JOIN pg_namespace n ON n.oid = cl.relnamespace
                 WHERE n.nspname = 'dispatch'
                   AND cl.relkind = 'r'
                   AND pg_get_userbyid(cl.relowner) = ?
                 """)) {
            st.setString(1, SERVICE_ROLE);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                assertThat(rs.getLong(1))
                    .as("and it must hold its own — a role that owns nothing anywhere would "
                        + "satisfy the previous assertion and be unable to run the service, "
                        + "which is a green suite describing a broken deployment")
                    .isPositive();
            }
        }
    }

    private static Connection admin() throws SQLException {
        var config = ConfigProvider.getConfig();
        return DriverManager.getConnection(
            config.getValue("test.db.url", String.class),
            config.getValue("test.db.admin.username", String.class),
            config.getValue("test.db.admin.password", String.class));
    }
}
