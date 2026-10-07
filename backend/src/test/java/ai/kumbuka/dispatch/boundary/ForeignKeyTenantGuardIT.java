package ai.kumbuka.dispatch.boundary;

import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every foreign key in this schema carries the tenant, on both sides.
 *
 * <p>A foreign key is checked with row-level security bypassed, so a key on
 * an id alone pairs a row with its target whatever tenant the target belongs
 * to. ADR-0042 requires {@code tenant_id} in every key: the referencing
 * column list includes it and the referenced column list includes it, at the
 * same position, so the store itself refuses a pairing across tenants.
 *
 * <p>Read from the catalogue as the container's administrator, so a key the
 * service role could not see still counts.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ForeignKeyTenantGuardIT {

    /**
     * Every foreign key of the schema whose two column lists do not both
     * carry {@code tenant_id} at the same position.
     */
    private static final String KEYS_WITHOUT_THE_TENANT = """
        SELECT c.conrelid::regclass::text || '.' || c.conname
        FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace
        WHERE n.nspname = 'dispatch' AND c.contype = 'f'
          AND NOT EXISTS (
              SELECT 1
              FROM unnest(c.conkey, c.confkey) AS k(own, target)
              JOIN pg_attribute a ON a.attrelid = c.conrelid  AND a.attnum = k.own
              JOIN pg_attribute b ON b.attrelid = c.confrelid AND b.attnum = k.target
              WHERE a.attname = 'tenant_id' AND b.attname = 'tenant_id')
        """;

    @Test
    void every_foreign_key_pairs_the_tenant_of_both_rows() throws SQLException {
        try (Connection c = admin()) {
            assertThat(keysWithoutTheTenant(c))
                .as("a key without tenant_id on both sides binds a row to a target in any "
                    + "tenant, because the key is checked with row-level security bypassed")
                .isEmpty();
            assertThat(foreignKeyCount(c))
                .as("the schema declares seven keys, three on the exchange store and four on "
                    + "the task store; finding none would mean the check reads the wrong schema "
                    + "and passes because of it")
                .isEqualTo(7);
        }
    }

    @Test
    void the_check_reports_a_planted_key_without_the_tenant() throws SQLException {
        try (Connection c = admin()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE dispatch.planted_reference ("
                    + "tenant_id uuid NOT NULL, task_id bigint REFERENCES dispatch.task (id))");
            }
            List<String> found = keysWithoutTheTenant(c);
            c.rollback();
            assertThat(found)
                .as("RED STATE, observed: a single-column key onto task.id, planted in the "
                    + "schema, must be reported, or the check above passes whatever it reads")
                .anyMatch(name -> name.startsWith("dispatch.planted_reference."));
        }
    }

    private static List<String> keysWithoutTheTenant(Connection c) throws SQLException {
        List<String> found = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(KEYS_WITHOUT_THE_TENANT)) {
            while (rs.next()) {
                found.add(rs.getString(1));
            }
        }
        return found;
    }

    private static int foreignKeyCount(Connection c) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("""
                 SELECT count(*) FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace
                 WHERE n.nspname = 'dispatch' AND c.contype = 'f'
                 """)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private static Connection admin() throws SQLException {
        Config config = ConfigProvider.getConfig();
        return DriverManager.getConnection(
            config.getValue("test.db.url", String.class),
            config.getValue("test.db.admin.username", String.class),
            config.getValue("test.db.admin.password", String.class));
    }
}
