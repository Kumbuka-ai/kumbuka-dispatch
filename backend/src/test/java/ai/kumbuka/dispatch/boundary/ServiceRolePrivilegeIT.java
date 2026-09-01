package ai.kumbuka.dispatch.boundary;

import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the runtime role may do to each relation of its own schema, privilege
 * by privilege, read from {@code has_table_privilege} and from nothing else.
 *
 * <h2>What this was written against</h2>
 *
 * Measured 2026-08-23 in the deployment of v0.1.0: {@code kumbuka_dispatch}
 * held DELETE, INSERT, REFERENCES, SELECT, TRIGGER, TRUNCATE and UPDATE on
 * all four relations of this schema, {@code flyway_schema_history} included.
 * No GRANT anywhere in V1..V7 produced that. It came from OWNERSHIP: an owner
 * holds the full ACL on what it owns, implicitly, with no grant to show for
 * it — and the ownership sweep that ran after each migration took the Flyway
 * history table along with everything else, because that table lives in this
 * schema too.
 *
 * <p><strong>TRUNCATE is the one that is not a tidiness issue.</strong> It
 * bypasses row-level security completely, independently of every policy and
 * of whether {@code app.tenant_id} is bound, so a runtime role holding it can
 * empty a tenant-scoped table across the tenant boundary with no part of the
 * isolation apparatus seeing it. TRIGGER and REFERENCES are the same shape
 * with a smaller blast radius.
 *
 * <h2>Two properties, and why neither alone would do</h2>
 *
 * The privileges must be exactly the enumerated set, AND they must be grants
 * rather than ownership. Without the second half the first would be satisfied
 * by the very arrangement this replaces: an owner reports SELECT, INSERT and
 * UPDATE truthfully — and TRUNCATE truthfully as well, which is why the first
 * half is not redundant either.
 *
 * <h2>Where the expectation comes from</h2>
 *
 * {@link #EXPECTED} is written out here, table by table. It is deliberately
 * NOT derived from the running catalog: a probe that reads its expectation
 * out of the thing it is probing checks that two copies agree, never that
 * either is right — and the defect this exists to catch is precisely a
 * catalog that says something nobody wrote down.
 *
 * <p>The catalog is still read, for one thing the list cannot do on its own:
 * the set of relations must MATCH the expectation exactly, so a table added
 * by a later migration is a failure here rather than an object nobody checks.
 * That is the property the ownership arrangement was reaching for, kept
 * without paying its price.
 *
 * <p>The schema and the role come from {@code quarkus.flyway.default-schema}
 * and {@code quarkus.datasource.username} — the two settings the application
 * itself runs on, rather than a duplicate pair that could agree with each
 * other while disagreeing with the database.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ServiceRolePrivilegeIT {

    /** The migrator's own record of what it did. The runtime role holds nothing on it. */
    private static final String HISTORY_TABLE = "flyway_schema_history";

    /** What a domain table of this schema grants the runtime role. */
    private static final Set<String> DOMAIN_PRIVILEGES = Set.of("SELECT", "INSERT", "UPDATE");

    /**
     * The entitlement, relation by relation. Every relation of this schema
     * appears, including the one whose entitlement is nothing at all — an
     * absence stated is checkable, an absence omitted is not.
     *
     * <p>No DELETE. The thirteen verbs are create, read, update, append, send,
     * accept, claim, release, abandon, block, resume, close and consume, and
     * none of them deletes: {@code revert} discards an unratified handover
     * draft by nulling two columns, which is an UPDATE. No path in this
     * repository issues a DELETE against any of these tables.
     */
    private static final Map<String, Set<String>> EXPECTED = Map.of(
        "exchange", DOMAIN_PRIVILEGES,
        "selector", DOMAIN_PRIVILEGES,
        "number_circle", DOMAIN_PRIVILEGES,
        HISTORY_TABLE, Set.of());

    /**
     * Everything {@code has_table_privilege} can be asked about a table.
     *
     * <p>Written out rather than derived, because the question is "which of
     * the privileges PostgreSQL has does this role hold" and a derived list
     * would silently stop asking about one the day a catalog view changed
     * shape.
     */
    private static final List<String> ALL_TABLE_PRIVILEGES = List.of(
        "SELECT", "INSERT", "UPDATE", "DELETE", "TRUNCATE", "REFERENCES", "TRIGGER");

    private static String schema() {
        return ConfigProvider.getConfig().getValue("quarkus.flyway.default-schema", String.class);
    }

    private static String role() {
        return ConfigProvider.getConfig().getValue("quarkus.datasource.username", String.class);
    }

    // ------------------------------------------------------------------
    // What the migration left behind, read once before anything touches it.
    // ------------------------------------------------------------------

    /**
     * The catalog as the migration set left it, captured before any test in
     * this class has run.
     *
     * <p>Reading it live in the green case would make that case depend on
     * execution order, and silently: every red case below grants or revokes a
     * privilege and undoes it in a {@code finally}, and undoing a GRANT by
     * REVOKE does not restore a state where the privilege was ALREADY there.
     * So a red case running first would quietly repair a defect the migration
     * shipped, and the green case would then measure the repair.
     *
     * <p>That is not hypothetical: it was observed while building this probe.
     * A deliberately widened grant in V8 was caught by
     * {@code MissingGrantProbeIT} and missed here, for exactly this reason.
     */
    private static List<String> migratedRelations;
    private static List<String> migratedDefects;
    private static String migratedSchemaOwner;
    private static List<String> migratedForeignOwned;
    private static boolean migratedSchemaUsage;
    private static boolean migratedSchemaCreate;
    private static String migratedExchangeRead;

    @BeforeAll
    static void captureWhatTheMigrationLeft() throws SQLException {
        try (Connection c = admin()) {
            migratedRelations = relationsIn(c, schema());
            migratedDefects = privilegeDefects(c);
            migratedSchemaOwner = schemaOwner(c);
            migratedForeignOwned =
                relationsNotOwnedBy(c, SubstrateDatabaseResource.MIGRATOR_ROLE);
            migratedSchemaUsage = holdsOnSchema(c, "USAGE");
            migratedSchemaCreate = holdsOnSchema(c, "CREATE");
        }
        migratedExchangeRead = readExchangeAsRuntimeRole();
    }

    /**
     * Reads a table under the runtime role itself, because "the catalog says
     * it may" and "it can" are two different claims and only the second is the
     * one a deployment depends on.
     *
     * <p>A refusal is CAUGHT and returned as text rather than thrown. Thrown,
     * it would abort the capture and take every case in this class down with a
     * message about one query; returned, the case that is actually about
     * reachability fails with the refusal in its own report, and the case
     * about the privilege list still runs and says which grant is missing.
     * That is the difference between "the suite broke" and "V8 forgot a
     * GRANT", and the second is what the reader needs.
     */
    private static String readExchangeAsRuntimeRole() throws SQLException {
        try (Connection c = service();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM " + schema() + ".exchange")) {
            rs.next();
            return rs.getLong(1) + " rows";
        } catch (SQLException e) {
            if ("42501".equals(e.getSQLState())) {
                return "refused: " + e.getMessage();
            }
            throw e;
        }
    }

    // ------------------------------------------------------------------
    // Probe A — the privilege set.
    // ------------------------------------------------------------------

    @Test
    void the_runtime_role_holds_exactly_the_enumerated_privileges() {
        assertThat(migratedRelations)
            .as("the relations of %s must be exactly the ones the expectation names. A "
                + "relation the catalog has and this probe does not is one nobody is "
                + "checking; one this probe has and the catalog does not means the "
                + "expectation is describing a schema that no longer exists", schema())
            .containsExactlyInAnyOrderElementsOf(new TreeSet<>(EXPECTED.keySet()));

        assertThat(migratedDefects)
            .as("the runtime role %s may hold exactly %s on a domain table of schema %s and "
                + "nothing whatever on %s. TRUNCATE in particular bypasses row-level "
                + "security independently of every policy, so a role holding it crosses the "
                + "tenant boundary without any part of the isolation apparatus seeing it",
                role(), DOMAIN_PRIVILEGES, schema(), HISTORY_TABLE)
            .isEmpty();
    }

    /**
     * And the role must actually reach what it was granted.
     *
     * <p>Without this the suite could be green against a deployment that does
     * not run: every assertion above is satisfied by a role holding nothing at
     * all, and a service that cannot read its own table would be reported as
     * perfectly bounded.
     */
    @Test
    void the_runtime_role_reaches_its_own_tables() {
        assertThat(migratedSchemaUsage)
            .as("without USAGE on the schema every enumerated table grant is unreachable")
            .isTrue();
        assertThat(migratedSchemaCreate)
            .as("CREATE on its own schema would let the runtime role add a table and own "
                + "it, which is the enumeration defeated in one statement")
            .isFalse();
        assertThat(migratedExchangeRead)
            .as("and the role must actually read its own table — read under the role "
                + "itself, before any red case in this class had a chance to issue the "
                + "grant that would make it possible")
            .endsWith(" rows");
    }

    // ------------------------------------------------------------------
    // Probe B — the ownership.
    // ------------------------------------------------------------------

    @Test
    void the_migrator_owns_the_schema_and_every_relation_in_it() {
        assertThat(migratedSchemaOwner)
            .as("the migrator keeps the schema, so the runtime role holds no CREATE on "
                + "it and cannot add a table to its own entitlement")
            .isEqualTo(SubstrateDatabaseResource.MIGRATOR_ROLE);

        assertThat(migratedForeignOwned)
            .as("every relation of %s belongs to the migrator, the Flyway history table "
                + "included. An owner holds every privilege on what it owns, implicitly, "
                + "and can grant itself back anything revoked — so an enumerated list "
                + "over an owned table describes nothing that is enforced", schema())
            .isEmpty();
    }

    /** And nowhere else either: the runtime role owns nothing in this database. */
    @Test
    void the_runtime_role_owns_nothing_anywhere() throws SQLException {
        try (Connection c = admin();
             var st = c.prepareStatement("""
                 SELECT coalesce(string_agg(n.nspname || '.' || cl.relname, ', '), '')
                 FROM pg_class cl
                 JOIN pg_namespace n ON n.oid = cl.relnamespace
                 WHERE pg_get_userbyid(cl.relowner) = ?
                   AND cl.relkind IN ('r','v','m','S','p')
                 """)) {
            st.setString(1, role());
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                assertThat(rs.getString(1))
                    .as("a service role owns nothing. This is the assertion that says which "
                        + "of the two arrangements is in place, and it is the one that would "
                        + "have caught the defect on the day it shipped")
                    .isEmpty();
            }
        }
    }

    /**
     * Probe B's red state.
     *
     * <p>The dispatch asks for it by re-registering the ownership callback.
     * That class is deleted rather than disabled, so keeping a copy of it in
     * the tree to switch on for one assertion would put back exactly what the
     * change removes. What the red run does instead is produce the STATE the
     * callback produced — ownership on the runtime role — which is the thing
     * the probe is actually about, and it shows the two effects together: the
     * ownership assertion fails, and the privilege assertion fails with it,
     * because an owner acquires TRUNCATE and the rest without a grant.
     */
    @Test
    void the_probe_notices_ownership_handed_to_the_runtime_role() throws SQLException {
        String table = schema() + ".exchange";
        try (Connection c = admin()) {
            try {
                exec(c, "ALTER TABLE " + table + " OWNER TO " + role());

                assertThat(relationsNotOwnedBy(c, SubstrateDatabaseResource.MIGRATOR_ROLE))
                    .as("RED STATE, observed: with one relation handed to the runtime role "
                        + "the ownership assertion must name it, and must name its new "
                        + "owner — a report that only says 'wrong owner' sends the reader "
                        + "back to the catalog to find out whose")
                    .anySatisfy(relation -> assertThat(relation)
                        .contains("exchange")
                        .contains(role()));

                assertThat(privilegeDefects(c))
                    .as("RED STATE, observed: and the privilege probe must fail on the same "
                        + "relation without a single GRANT having been issued — which is "
                        + "the whole mechanism of the defect, in one assertion")
                    .anySatisfy(defect -> assertThat(defect)
                        .contains("exchange")
                        .contains("TRUNCATE"));
            } finally {
                exec(c, "ALTER TABLE " + table + " OWNER TO "
                    + SubstrateDatabaseResource.MIGRATOR_ROLE);
                // The owner change carries the implicit ACL with it and drops
                // the explicit grants, so they are re-issued exactly as V8
                // writes them.
                exec(c, "GRANT SELECT, INSERT, UPDATE ON " + table + " TO " + role());
            }

            assertThat(relationsNotOwnedBy(c, SubstrateDatabaseResource.MIGRATOR_ROLE))
                .as("and back, so the red state was that ownership and nothing else")
                .isEmpty();
            assertThat(privilegeDefects(c))
                .as("and the entitlement is the enumerated one again")
                .isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // Probe D — the drift, in both directions.
    // ------------------------------------------------------------------

    /**
     * A privilege too many. This is the direction the measured defect lay in,
     * and a probe that only checked for completeness would not see it at all.
     */
    @Test
    void the_probe_reports_a_privilege_that_is_held_and_must_not_be() throws SQLException {
        String table = schema() + ".exchange";
        try (Connection c = admin()) {
            try {
                exec(c, "GRANT TRUNCATE ON " + table + " TO " + role());

                assertThat(privilegeDefects(c))
                    .as("RED STATE, observed: with TRUNCATE granted the probe must report "
                        + "it, and must say which relation and which privilege — a defect "
                        + "report that only says 'wrong' sends the reader back to the "
                        + "catalog to find out what it meant")
                    .anySatisfy(defect -> assertThat(defect)
                        .contains("exchange")
                        .contains("TRUNCATE"));
            } finally {
                exec(c, "REVOKE TRUNCATE ON " + table + " FROM " + role());
            }

            assertThat(privilegeDefects(c))
                .as("and gone again, so the red state was that grant and nothing else")
                .isEmpty();
        }
    }

    /**
     * A privilege missing. Without this direction the probe would be green
     * against a schema with every grant revoked — a service that cannot read
     * its own tables, reported as perfectly bounded.
     */
    @Test
    void the_probe_reports_a_privilege_that_is_missing_and_must_be_held() throws SQLException {
        String table = schema() + ".exchange";
        try (Connection c = admin()) {
            try {
                exec(c, "REVOKE UPDATE ON " + table + " FROM " + role());

                assertThat(privilegeDefects(c))
                    .as("RED STATE, observed: a missing UPDATE is a defect too")
                    .anySatisfy(defect -> assertThat(defect)
                        .contains("exchange")
                        .contains("UPDATE"));
            } finally {
                exec(c, "GRANT UPDATE ON " + table + " TO " + role());
            }

            assertThat(privilegeDefects(c))
                .as("and restored")
                .isEmpty();
        }
    }

    /**
     * The history table's own red state.
     *
     * <p>A separate case because it fails a different rule: a domain table is
     * checked against a permitted set, and this one against nothing being
     * permitted at all. SELECT is the least alarming privilege there is, which
     * is exactly why a rule that only watched the alarming ones would let the
     * boundary erode.
     */
    @Test
    void the_probe_reports_any_privilege_at_all_on_the_history_table() throws SQLException {
        String table = schema() + "." + HISTORY_TABLE;
        try (Connection c = admin()) {
            try {
                exec(c, "GRANT SELECT ON " + table + " TO " + role());

                assertThat(privilegeDefects(c))
                    .as("RED STATE, observed: even SELECT on the migrator's history table "
                        + "must be reported. A runtime role that can read it has no verb "
                        + "that needs to, and one that can rewrite it can make the schema "
                        + "lie about its own version")
                    .anySatisfy(defect -> assertThat(defect)
                        .contains(HISTORY_TABLE)
                        .contains("SELECT"));
            } finally {
                exec(c, "REVOKE SELECT ON " + table + " FROM " + role());
            }

            assertThat(privilegeDefects(c))
                .as("and closed again")
                .isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // The detection itself, used by both the green and the red cases.
    // ------------------------------------------------------------------

    /**
     * Every deviation from the enumerated entitlement, one string per relation
     * and privilege, each naming both — in BOTH directions: a privilege held
     * that must not be, and one missing that must be held.
     */
    private static List<String> privilegeDefects(Connection c) throws SQLException {
        List<String> defects = new ArrayList<>();
        for (var entry : new TreeSet<>(EXPECTED.keySet())) {
            Set<String> permitted = EXPECTED.get(entry);
            for (String privilege : ALL_TABLE_PRIVILEGES) {
                boolean held = holds(c, entry, privilege);
                boolean expected = permitted.contains(privilege);
                if (held && !expected) {
                    defects.add(schema() + "." + entry + ": holds " + privilege
                        + " and must not" + (permitted.isEmpty()
                            ? " — the runtime role holds nothing on this relation"
                            : " — permitted here is exactly " + permitted));
                } else if (!held && expected) {
                    defects.add(schema() + "." + entry + ": lacks " + privilege
                        + " and must hold it — the service cannot run without it");
                }
            }
        }
        return defects;
    }

    /** Every relation of the schema that carries an owner, from the catalog. */
    private static List<String> relationsIn(Connection c, String schema) throws SQLException {
        List<String> out = new ArrayList<>();
        try (var st = c.prepareStatement("""
                SELECT cl.relname
                FROM pg_class cl
                JOIN pg_namespace n ON n.oid = cl.relnamespace
                WHERE n.nspname = ?
                  AND cl.relkind IN ('r','v','m','S','p')
                ORDER BY cl.relname
                """)) {
            st.setString(1, schema);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    private static List<String> relationsNotOwnedBy(Connection c, String owner)
            throws SQLException {
        List<String> out = new ArrayList<>();
        try (var st = c.prepareStatement("""
                SELECT cl.relname || ' [owner ' || pg_get_userbyid(cl.relowner) || ']'
                FROM pg_class cl
                JOIN pg_namespace n ON n.oid = cl.relnamespace
                WHERE n.nspname = ?
                  AND cl.relkind IN ('r','v','m','S','p')
                  AND pg_get_userbyid(cl.relowner) <> ?
                ORDER BY cl.relname
                """)) {
            st.setString(1, schema());
            st.setString(2, owner);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString(1));
                }
            }
        }
        return out;
    }

    private static String schemaOwner(Connection c) throws SQLException {
        try (var st = c.prepareStatement(
                "SELECT pg_get_userbyid(nspowner) FROM pg_namespace WHERE nspname = ?")) {
            st.setString(1, schema());
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    /** {@code has_schema_privilege}, read as a boolean rather than as text. */
    private static boolean holdsOnSchema(Connection c, String privilege) throws SQLException {
        try (var st = c.prepareStatement("SELECT has_schema_privilege(?, ?, ?)")) {
            st.setString(1, role());
            st.setString(2, schema());
            st.setString(3, privilege);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    /** {@code has_table_privilege}, asked one privilege at a time. */
    private static boolean holds(Connection c, String relation, String privilege)
            throws SQLException {
        try (var st = c.prepareStatement("SELECT has_table_privilege(?, ?, ?)")) {
            st.setString(1, role());
            st.setString(2, schema() + "." + relation);
            st.setString(3, privilege);
            try (ResultSet rs = st.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    private static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static Connection service() throws SQLException {
        return DriverManager.getConnection(config("test.db.url"),
            SubstrateDatabaseResource.SERVICE_ROLE, SubstrateDatabaseResource.SERVICE_PASSWORD);
    }

    private static Connection admin() throws SQLException {
        return DriverManager.getConnection(config("test.db.url"),
            config("test.db.admin.username"), config("test.db.admin.password"));
    }

    private static String config(String key) {
        return ConfigProvider.getConfig().getValue(key, String.class);
    }
}
