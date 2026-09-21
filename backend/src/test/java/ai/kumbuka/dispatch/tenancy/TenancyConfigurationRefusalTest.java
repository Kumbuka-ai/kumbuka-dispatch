package ai.kumbuka.dispatch.tenancy;

import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The service refuses to start without a tenant and without a scope.
 *
 * <h2>Why this reads the shipped file rather than setting values</h2>
 *
 * The property under test is an ABSENCE — that
 * {@code application.properties} no longer carries a default tenant. A test
 * that assembled its own configuration would observe the guard and say
 * nothing at all about the file a deployment actually ships, and would stay
 * green on the day somebody puts the default back "so the container starts in
 * development". So the shipped file is loaded from disk, with no environment
 * and no system properties behind it, and the guard is run against exactly
 * that.
 *
 * <p>That is also what makes the red probe sharp: put
 * {@code dispatch.tenant-id=${DISPATCH_TENANT_ID:00000000-…-0001}} back and
 * the configuration below answers with the default, the guard falls silent,
 * and this test goes red — which is the dispatch's first red probe, in the
 * wording of its outcome.
 *
 * <h2>And why the synthetic cases are here too</h2>
 *
 * Four ways of leaving a setting out reach a deployment: absent, empty,
 * blank, and present-but-not-a-uuid. Only the first is observable from the
 * shipped file. The others are what an operator actually produces — a compose
 * variable with nothing after the colon, a secret that resolved to the empty
 * string — and each has to name the key rather than fail later inside a uuid
 * cast in V5.
 */
class TenancyConfigurationRefusalTest {

    private static final String A_UUID = "00000000-0000-0000-0000-000000000001";
    private static final String ANOTHER_UUID = "00000000-0000-0000-0000-000000000010";

    // =======================================================================
    // The shipped configuration carries neither value
    // =======================================================================

    /**
     * The file a deployment ships, with nothing behind it, does not start this
     * service.
     *
     * <p>No environment source and no system-property source are added, which
     * is the whole construction: whatever this machine happens to export, the
     * configuration under test is the file.
     */
    @Test
    void the_shipped_configuration_alone_does_not_start_the_service() {
        assertThatThrownBy(() -> TenancyConfigurationGuard.requireConfigured(shipped()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(TenancyConfigurationGuard.TENANT_KEY)
            .as("the message names the key, because the remedy is to set it and a "
                + "message that only said 'misconfigured' would send the reader looking")
            .hasMessageContaining("DISPATCH_TENANT_ID");
    }

    /**
     * And the scope, on its own.
     *
     * <p>Asserted apart from the tenant rather than trusting that one check
     * covers both: they are two settings with two environment variables, and
     * a guard that returned after the first would leave the second defaulted
     * with nothing to show for it.
     */
    @Test
    void the_shipped_configuration_does_not_carry_a_scope_either() {
        Config withTenant = withOverrides(Map.of(
            TenancyConfigurationGuard.TENANT_KEY, A_UUID));

        assertThatThrownBy(() -> TenancyConfigurationGuard.requireConfigured(withTenant))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(TenancyConfigurationGuard.SCOPE_KEY)
            .hasMessageContaining("DISPATCH_SCOPE_ID");
    }

    // =======================================================================
    // The four ways of leaving one out
    // =======================================================================

    @Test
    void an_empty_value_is_not_a_set_value() {
        assertThatThrownBy(() -> TenancyConfigurationGuard.requireConfigured(withOverrides(
            Map.of(TenancyConfigurationGuard.TENANT_KEY, "",
                   TenancyConfigurationGuard.SCOPE_KEY, ANOTHER_UUID))))
            .isInstanceOf(IllegalStateException.class)
            .as("an exported-but-empty variable is the ordinary shape of a misconfigured "
                + "deployment, and it arrives as a present key with a blank value")
            .hasMessageContaining(TenancyConfigurationGuard.TENANT_KEY);
    }

    @Test
    void a_blank_value_is_not_a_set_value_either() {
        assertThatThrownBy(() -> TenancyConfigurationGuard.requireConfigured(withOverrides(
            Map.of(TenancyConfigurationGuard.TENANT_KEY, A_UUID,
                   TenancyConfigurationGuard.SCOPE_KEY, "   "))))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(TenancyConfigurationGuard.SCOPE_KEY);
    }

    @Test
    void a_value_that_is_not_a_uuid_is_refused_where_it_is_set_and_not_where_it_is_used() {
        assertThatThrownBy(() -> TenancyConfigurationGuard.requireConfigured(withOverrides(
            Map.of(TenancyConfigurationGuard.TENANT_KEY, "the-production-tenant",
                   TenancyConfigurationGuard.SCOPE_KEY, ANOTHER_UUID))))
            .isInstanceOf(IllegalStateException.class)
            .as("it reaches V5 as a placeholder and is cast to uuid there. Failing at the "
                + "cast would report a SQL error and never mention the setting")
            .hasMessageContaining(TenancyConfigurationGuard.TENANT_KEY)
            .hasMessageContaining("uuid");
    }

    // =======================================================================
    // The other half: with both set, the guard says nothing
    // =======================================================================

    /**
     * Without this, every assertion above would hold just as well against a
     * guard that refuses unconditionally.
     */
    @Test
    void with_both_set_the_guard_passes() {
        TenancyConfigurationGuard.requireConfigured(withOverrides(Map.of(
            TenancyConfigurationGuard.TENANT_KEY, A_UUID,
            TenancyConfigurationGuard.SCOPE_KEY, ANOTHER_UUID)));
    }

    /**
     * The shipped file still routes the two values into the migration set.
     *
     * <p>Removing the defaults must not remove the placeholders: V5 writes the
     * selector declaration against them, and a file that dropped the lines
     * entirely would migrate with an unresolved placeholder rather than
     * refusing. Read as text, because this is a statement about the file.
     */
    @Test
    void the_shipped_file_still_feeds_both_values_to_flyway() {
        String text = shippedText();

        assertThat(text).contains("quarkus.flyway.placeholders.dispatchTenantId="
            + "${" + TenancyConfigurationGuard.TENANT_KEY + "}");
        assertThat(text).contains("quarkus.flyway.placeholders.dispatchScopeId="
            + "${" + TenancyConfigurationGuard.SCOPE_KEY + "}");
    }

    // =======================================================================
    // The configuration under test
    // =======================================================================

    /** The shipped file, and nothing else — no environment, no system properties. */
    private static Config shipped() {
        return withOverrides(Map.of());
    }

    /**
     * The shipped file with named values put on top.
     *
     * <p>The overrides go into a source of higher ordinal rather than being
     * edited into the file's own map, so what is under test stays the shipped
     * file in every case and the override is visibly an addition.
     */
    private static Config withOverrides(Map<String, String> overrides) {
        SmallRyeConfigBuilder builder = new SmallRyeConfigBuilder()
            .withSources(shippedSource());
        if (!overrides.isEmpty()) {
            builder.withSources(new PropertiesConfigSource(
                new HashMap<>(overrides), "the deployment's environment", 500));
        }
        return builder.build();
    }

    private static ConfigSource shippedSource() {
        try {
            return new PropertiesConfigSource(shippedPath().toUri().toURL(), 100);
        } catch (Exception e) {
            throw new IllegalStateException("could not read the shipped configuration", e);
        }
    }

    private static String shippedText() {
        try {
            return Files.readString(shippedPath());
        } catch (Exception e) {
            throw new IllegalStateException("could not read the shipped configuration", e);
        }
    }

    /** Same two-candidate form the source-tree guards use, for the same reason. */
    private static Path shippedPath() {
        Path direct = Paths.get("src", "main", "resources", "application.properties");
        Path fromRepoRoot = Paths.get("backend", "src", "main", "resources",
            "application.properties");
        Path path = Files.isRegularFile(direct) ? direct : fromRepoRoot;
        assertThat(Files.isRegularFile(path))
            .as("the shipped configuration %s must exist — run from the module directory",
                path)
            .isTrue();
        return path;
    }
}
