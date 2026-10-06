package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.contract.TargetSurface;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestIdentityAssociation;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The service built with {@code kumbuka.local-mcp.enabled=true}, set
 * explicitly: the assistant surface is there, with the same tool list as a
 * build that does not name the setting, which every other test runs.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@TestProfile(LocalMcpOnIT.On.class)
class LocalMcpOnIT {

    /** The build with the setting named and on. */
    public static class On implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("kumbuka.local-mcp.enabled", "true");
        }
    }

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asConsole(identity);
    }

    @Test
    void the_assistant_surface_offers_the_calls_of_the_target() {
        List<String> served = Mcp.rpc("tools/list", Map.of()).jsonPath()
            .getList("result.tools.name");
        assertThat(served).containsExactlyInAnyOrderElementsOf(TargetSurface.MCP);
    }
}
