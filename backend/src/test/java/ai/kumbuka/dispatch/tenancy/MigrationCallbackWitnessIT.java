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
 * <p>There was a second callback in that configuration line until the
 * ownership model changed — {@code SchemaOwnershipCallback}, which handed the schema and every
 * relation in it to the runtime role after each run. It is deleted, and this
 * probe registers nothing in its place: the migrator now keeps what it
 * creates, which is what makes the DML in V5 subject to the policy in the
 * first place and therefore what this probe rests on.
 *
 * <h2>Why Flyway is driven directly here</h2>
 *
 * The callback list is build-time configuration, and a running application
 * cannot un-register one. Driving Flyway against a container of this test's
 * own is what makes the negative case reachable at all — and it runs the SAME
 * migration files the service ships, so the thing being witnessed is the real
 * migration set rather than a fixture that resembles it.
 */
class MigrationCallbackWitnessIT {

    /**
     * The migrating role. CREATEROLE and, critically, NOT BYPASSRLS. A
     * privileged migrator would walk past the policy, and the negative case
     * below would pass for the wrong reason — the DML would succeed whether
     * the callback ran or not. It would now also be refused
     * outright by V8, which is a different probe's business
     * ({@link MigratorAttributeProbeIT}).
     */
    private static final String MIGRATOR = "witness_migrator";
    private static final String MIGRATOR_PASSWORD = "test-only-witness-password";

    private static MigrationHarness harness;

    @BeforeAll
    static void startDatabase() throws SQLException {
        harness = MigrationHarness.start();
        harness.createMigrator(MIGRATOR, MIGRATOR_PASSWORD,
            "CREATEROLE NOSUPERUSER NOBYPASSRLS");
    }

    @AfterAll
    static void stopDatabase() {
        if (harness != null) {
            harness.close();
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
        String url = harness.freshDatabase("witness_without_callback", MIGRATOR);

        assertThatThrownBy(() -> harness.migrate(url, MIGRATOR, MIGRATOR_PASSWORD))
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
        String url = harness.freshDatabase("witness_with_callback", MIGRATOR);
        harness.migrate(url, MIGRATOR, MIGRATOR_PASSWORD, new TenantMigrationCallback());

        try (Connection c = harness.adminConnection(url);
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
}
