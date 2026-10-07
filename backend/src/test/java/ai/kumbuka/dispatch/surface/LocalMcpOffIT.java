package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.adapter.mcp.McpAdapter;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.arc.Arc;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The service built with {@code kumbuka.local-mcp.enabled=false}: it starts,
 * the assistant surface is not in the application, and REST answers as it
 * does with the surface on.
 *
 * <p>The switch is a build property, so the classes it governs are left out of
 * the application rather than refusing at runtime: {@code /mcp} finds no route.
 * The REST cases are the ones {@code RestSurfaceIT} asserts with the surface
 * on, with the same expectations.
 *
 * <p>With {@code Kumbuka-Surface: assistant} REST answers in the vocabulary of
 * the assistant surface here too: that vocabulary belongs to the call, not to
 * the adapter this build leaves out.
 *
 * <p>Red probes, observed: without the annotation on {@link McpAdapter},
 * {@code /mcp} answers and this class turns red; with the annotation on the
 * router both surfaces share, REST loses it and this class turns red.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@TestProfile(LocalMcpOffIT.Off.class)
class LocalMcpOffIT {

    /** The build without the service's own assistant surface. */
    public static class Off implements QuarkusTestProfile {

        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("kumbuka.local-mcp.enabled", "false");
        }
    }

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asConsole(identity);
    }

    @Test
    void the_assistant_surface_is_not_in_the_application() {
        Response rpc = given().contentType(ContentType.JSON)
            .body(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/list"))
            .post("/mcp");
        assertThat(rpc.statusCode()).as("no route serves /mcp").isEqualTo(404);
        assertThat(given().get("/mcp/declaration").statusCode()).isEqualTo(404);

        assertThat(Arc.container().instance(McpAdapter.class).isAvailable())
            .as("the resource is not a bean of this build").isFalse();
        assertThat(Arc.container().instance(DeclarationGuard.class).isAvailable())
            .as("nor is the start-up guard of the declaration").isFalse();
        assertThat(Arc.container().instance(CallRouter.class).isAvailable())
            .as("the router both surfaces share stays").isTrue();
    }

    @Test
    void a_write_and_a_read_over_rest_answer_as_with_the_surface_on() {
        Response created = SurfaceFixture.create(Map.of("title", "without mcp",
            "apparatus", "code", "text", "the commission"));
        assertThat(created.statusCode()).isEqualTo(201);
        String id = SurfaceFixture.idOf(created);
        assertThat(created.header("Location")).endsWith(SurfaceFixture.item(id));
        assertThat(created.jsonPath().getString("fields.state")).isEqualTo("draft");

        Response read = given().get(SurfaceFixture.item(id));
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(read.jsonPath().getString("fields.title")).isEqualTo("without mcp");
        assertThat(read.jsonPath().getList("next.call")).contains("send");
    }

    @Test
    void with_the_header_rest_names_the_assistant_tools_though_there_is_no_mcp() {
        Response created = SurfaceFixture.create(Map.of("title", "named for the assistant",
            "apparatus", "code"));
        String id = SurfaceFixture.idOf(created);

        Response read = given().header("Kumbuka-Surface", "assistant")
            .get(SurfaceFixture.item(id));
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(read.jsonPath().getList("next.call", String.class))
            .contains("dispatch_send")
            .allMatch(call -> call.startsWith(ProcessVerb.PREFIX));

        Response refused = given().header("Kumbuka-Surface", "assistant")
            .contentType(ContentType.JSON).body(Map.of("duration", "PT1H"))
            .post(SurfaceFixture.item(id) + ":claim");
        assertThat(refused.jsonPath().getString("reason")).isEqualTo("STATE_DOES_NOT_ALLOW");
        assertThat(refused.jsonPath().getString("message")).contains("dispatch_claim");
        assertThat(refused.jsonPath().getList("data.next.call", String.class))
            .isNotEmpty().allMatch(call -> call.startsWith(ProcessVerb.PREFIX));
    }

    @Test
    void an_undeclared_argument_is_refused_by_name_as_with_the_surface_on() {
        String before = Writes.snapshot();
        Response refused = given().contentType(ContentType.JSON)
            .body(Map.of("fields", Map.of("title", "t", "apparatus", "code"), "invented", 1))
            .post(SurfaceFixture.collection());

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(refused.jsonPath().getString("reason")).isEqualTo("ARGUMENT_UNKNOWN");
        assertThat(refused.jsonPath().getString("message")).contains("invented");
        assertThat(Writes.snapshot()).as("and it wrote nothing").isEqualTo(before);
    }
}
