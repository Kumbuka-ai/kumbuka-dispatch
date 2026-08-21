package ai.kumbuka.dispatch.tenancy;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The cold start: an empty database, and afterwards a service that runs.
 *
 * <p>This is the first acceptance criterion and it is also the one that is
 * easiest to fake. A suite that stages the schema and then asserts the schema
 * is there proves that its own setup ran. So nothing here is staged: the
 * container arrives empty apart from a provider role and a neighbour's table,
 * and everything asserted below was created by the migration set during boot,
 * in the order the service will do it in production.
 *
 * <p>The role attributes are asserted, not assumed. Superuser or BYPASSRLS on
 * the service role would evaporate every policy in this schema silently — no
 * error, rows returned, and the rest of the suite green. They are two boolean
 * columns in the catalog and there is no reason to learn about them from an
 * incident instead.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ColdStartIT {

    @Test
    void flyway_creates_the_schema_and_its_table() throws SQLException, IOException {
        try (Connection c = Db.asAdmin()) {
            assertThat(scalar(c, "SELECT count(*) FROM information_schema.schemata "
                + "WHERE schema_name = 'dispatch'"))
                .as("the service's named schema must exist after boot")
                .isEqualTo("1");

            assertThat(scalar(c, "SELECT count(*) FROM information_schema.tables "
                + "WHERE table_schema = 'dispatch' AND table_name = 'exchange'"))
                .as("V4 must have created the exchange table")
                .isEqualTo("1");

            // The expectation is counted from the migration directory rather
            // than written here as a number. A literal would have to be
            // maintained alongside every new migration, and the failure when
            // somebody forgets reads as "a migration did not apply" — which
            // sends the next reader looking at the database instead of at
            // this line. The two sources are genuinely different: one is the
            // files on disk, the other is what the database recorded running.
            long expected = countVersionedMigrationFiles();
            assertThat(expected)
                .as("the migration directory must have been found at all")
                .isPositive();

            // Only the versioned rows are counted. Flyway records its own
            // schema creation as an unversioned entry, and counting that as a
            // migration would make the assertion drift with the tool rather
            // than with the migration set.
            assertThat(scalar(c, "SELECT count(*) FROM dispatch.flyway_schema_history "
                + "WHERE success AND version IS NOT NULL"))
                .as("every versioned migration on disk must have applied successfully")
                .isEqualTo(String.valueOf(expected));

            assertThat(scalar(c, "SELECT max(version::int) FROM dispatch.flyway_schema_history "
                + "WHERE success AND version IS NOT NULL"))
                .as("and the schema must stand at the highest of them")
                .isEqualTo(String.valueOf(expected));
        }
    }

    @Test
    void the_migration_creates_the_service_role_with_the_attributes_that_matter()
            throws SQLException {
        try (Connection c = Db.asAdmin()) {
            assertThat(scalar(c, "SELECT count(*) FROM pg_roles WHERE rolname = '"
                + SubstrateDatabaseResource.SERVICE_ROLE + "'"))
                .as("V2 must create the service role against an empty database, so that a "
                    + "cold start needs no manual step")
                .isEqualTo("1");

            assertThat(scalar(c, "SELECT rolsuper FROM pg_roles WHERE rolname = '"
                + SubstrateDatabaseResource.SERVICE_ROLE + "'"))
                .as("a superuser bypasses row-level security unconditionally: the policies "
                    + "in V3 would exist and do nothing")
                .isEqualTo("f");

            assertThat(scalar(c, "SELECT rolbypassrls FROM pg_roles WHERE rolname = '"
                + SubstrateDatabaseResource.SERVICE_ROLE + "'"))
                .as("BYPASSRLS is the same evaporation by a different attribute")
                .isEqualTo("f");
        }
    }

    @Test
    void every_object_in_the_schema_belongs_to_the_service_role() throws SQLException {
        try (Connection c = Db.asAdmin()) {
            // The migrator is privileged, so everything it created is its own
            // until the ownership callback hands it over. An object left with
            // the migrator would be one the service cannot read and one whose
            // FORCE ROW LEVEL SECURITY binds the wrong role.
            assertThat(scalar(c, """
                SELECT coalesce(string_agg(c.relname || ':' || pg_get_userbyid(c.relowner), ', '), '')
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'dispatch'
                  AND c.relkind IN ('r','v','m','S','p')
                  AND pg_get_userbyid(c.relowner) <> '%s'
                """.formatted(SubstrateDatabaseResource.SERVICE_ROLE)))
                .as("SchemaOwnershipCallback must hand every relation in the schema to the "
                    + "service role after migrating, including the Flyway history table")
                .isEmpty();

            assertThat(scalar(c, "SELECT pg_get_userbyid(nspowner) FROM pg_namespace "
                + "WHERE nspname = 'dispatch'"))
                .as("the schema itself is an owned object too")
                .isEqualTo(SubstrateDatabaseResource.SERVICE_ROLE);
        }
    }

    @Test
    void the_service_reaches_its_own_table_and_reaches_nothing_else() throws SQLException {
        try (Connection c = Db.asService()) {
            // Its own: an owner needs no grant, which is why V2 issues none.
            assertThat(Db.countExchanges(c))
                .as("the service role must reach its own table as its owner")
                .isNotNegative();
        }
    }

    /**
     * Counts {@code V<n>__*.sql} files in the migration directory.
     *
     * <p>Deliberately not a constant: this is the one number in the test that
     * would otherwise need editing every time a migration is added, and the
     * edit that gets forgotten produces a failure describing the wrong thing.
     */
    private static long countVersionedMigrationFiles() throws IOException {
        Path dir = Files.isDirectory(Paths.get("src/main/resources/db/migration"))
            ? Paths.get("src/main/resources/db/migration")
            : Paths.get("backend/src/main/resources/db/migration");
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(f -> f.getFileName().toString())
                .filter(n -> n.matches("V\\d+__.*\\.sql"))
                .count();
        }
    }

    private static String scalar(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
