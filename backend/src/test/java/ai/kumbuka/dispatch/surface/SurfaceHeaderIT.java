package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The REST surface with {@code Kumbuka-Surface: assistant}: it answers in the
 * vocabulary of the assistant surface, and that is all the header changes.
 *
 * <p>Each comparison is a JSON value against a JSON value — the body of the
 * REST answer against {@code structuredContent} of the service's own MCP
 * adapter, for the same call by the same identity at the same task. Where two
 * tasks are needed, because a transition happens once, only the address and the
 * conflict token are set aside.
 *
 * <p>Red probes, observed: with the header unread and the surface fixed to
 * REST, {@link #with_the_header_rest_answers_what_the_assistant_surface_answers}
 * turns red at its first comparison, on {@code next[0].call}; with an unknown
 * value taken like a missing one,
 * {@link #an_unknown_value_is_refused_by_name_and_writes_nothing} turns red.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class SurfaceHeaderIT {

    private static final String HEADER = "Kumbuka-Surface";
    private static final String ASSISTANT = "assistant";

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asConsole(identity);
    }

    @Test
    void with_the_header_rest_answers_what_the_assistant_surface_answers() {
        String id = SurfaceFixture.open("the header's task", "code");
        String address = SurfaceFixture.address(id);

        Response read = assistant().get(SurfaceFixture.item(id));
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(Mcp.nextCalls(body(read))).as("the read has calls to name").isNotEmpty();
        assertThat(body(read)).as("read")
            .isEqualTo(Mcp.answer(Mcp.call("dispatch_read", Map.of("address", address))));

        Response listed = assistant().get(SurfaceFixture.collection() + "?address=" + address);
        assertThat(listed.statusCode()).isEqualTo(200);
        assertThat(body(listed)).as("query")
            .isEqualTo(Mcp.answer(Mcp.call("dispatch_query", Map.of(
                "scope", SurfaceFixture.SCOPE, "selector", SurfaceFixture.SELECTOR,
                "address", address))));

        Response overRest = SurfaceFixture.create(draft());
        Response overMcp = SurfaceFixture.create(draft());
        Response sentOverRest = assistant().header("If-Match", overRest.header("ETag"))
            .post(SurfaceFixture.item(SurfaceFixture.idOf(overRest)) + ":send");
        assertThat(sentOverRest.statusCode()).isEqualTo(200);
        Map<String, Object> sentOverMcp = Mcp.answer(Mcp.call("dispatch_send", Map.of(
            "address", SurfaceFixture.address(SurfaceFixture.idOf(overMcp)),
            "conflict_token", overMcp.jsonPath().getString("conflict_token"))));
        assertThat(apartFromTheTask(body(sentOverRest))).as("send")
            .isEqualTo(apartFromTheTask(sentOverMcp));

        String token = read.jsonPath().getString("conflict_token");
        Response again = assistant().header("If-Match", "\"" + token + "\"")
            .post(SurfaceFixture.item(id) + ":send");
        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(again.jsonPath().getString("reason")).isEqualTo("STATE_DOES_NOT_ALLOW");
        assertThat(again.jsonPath().getString("message")).contains("dispatch_send");
        assertThat(again.jsonPath().getList("data.next.call", String.class))
            .isNotEmpty().allMatch(call -> call.startsWith(ProcessVerb.PREFIX));
        assertThat(body(again)).as("a refusal for the state")
            .isEqualTo(Mcp.refusal(Mcp.call("dispatch_send", Map.of(
                "address", address, "conflict_token", token))));

        Map<String, Object> invented = new LinkedHashMap<>();
        invented.put("invented", "x");
        invented.put("made_up", "y");
        Response unknown = assistant().contentType(ContentType.JSON).body(invented)
            .post(SurfaceFixture.item(id) + ":claim");
        assertThat(unknown.statusCode()).isEqualTo(400);
        assertThat(unknown.jsonPath().getString("reason")).isEqualTo("ARGUMENT_UNKNOWN");
        Map<String, Object> mcpArguments = new LinkedHashMap<>();
        mcpArguments.put("address", address);
        mcpArguments.putAll(invented);
        assertThat(body(unknown)).as("two invented arguments")
            .isEqualTo(Mcp.refusal(Mcp.call("dispatch_claim", mcpArguments)));

        String nowhere = SurfaceFixture.address("999999.0");
        Response missing = assistant().get(SurfaceFixture.item("999999.0"));
        assertThat(missing.statusCode()).isEqualTo(404);
        assertThat(body(missing)).as("NOT_FOUND")
            .isEqualTo(Mcp.refusal(Mcp.call("dispatch_read", Map.of("address", nowhere))));
    }

    @Test
    void an_unknown_value_is_refused_by_name_and_writes_nothing() {
        for (String value : List.of("mcp", "Assistant", "rest", "")) {
            String before = Writes.snapshot();
            Response refused = given().header(HEADER, value).contentType(ContentType.JSON)
                .body(Map.of("fields", draft()))
                .post(SurfaceFixture.collection());
            assertThat(refused.statusCode()).as("'%s'", value).isEqualTo(400);
            assertThat(refused.jsonPath().getString("reason")).as("'%s'", value)
                .isEqualTo("ARGUMENT_INVALID");
            assertThat(refused.jsonPath().getString("message")).as("'%s'", value)
                .contains(HEADER).contains(ASSISTANT);
            assertThat(Writes.snapshot()).as("'%s' wrote nothing", value).isEqualTo(before);
        }

        String before = Writes.snapshot();
        Response twice = given().header(HEADER, ASSISTANT, ASSISTANT)
            .contentType(ContentType.JSON).body(Map.of("fields", draft()))
            .post(SurfaceFixture.collection());
        assertThat(twice.statusCode()).as("the header twice").isEqualTo(400);
        assertThat(twice.jsonPath().getString("reason")).isEqualTo("ARGUMENT_INVALID");
        assertThat(Writes.snapshot()).as("twice wrote nothing").isEqualTo(before);
    }

    @Test
    void the_header_decides_nothing_but_the_names() {
        String id = SurfaceFixture.open("the role's task", "code");
        String before = Writes.snapshot();

        Response without = given().contentType(ContentType.JSON)
            .body(Map.of("duration", "PT1H")).post(SurfaceFixture.item(id) + ":claim");
        Response with = assistant().contentType(ContentType.JSON)
            .body(Map.of("duration", "PT1H")).post(SurfaceFixture.item(id) + ":claim");

        assertThat(without.jsonPath().getString("reason")).isEqualTo("ROLE_DOES_NOT_ALLOW");
        assertThat(with.statusCode()).isEqualTo(without.statusCode());
        assertThat(with.jsonPath().getString("reason"))
            .isEqualTo(without.jsonPath().getString("reason"));
        assertThat(with.asString()).as("the header changes the names").contains("dispatch_claim")
            .isNotEqualTo(without.asString());
        assertThat(with.asString().replace(ProcessVerb.PREFIX, ""))
            .as("and nothing but the names")
            .isEqualTo(without.asString());
        assertThat(Writes.snapshot()).as("neither wrote").isEqualTo(before);
    }

    // -----------------------------------------------------------------------

    private static RequestSpecification assistant() {
        return given().header(HEADER, ASSISTANT);
    }

    private static Map<String, Object> body(Response response) {
        return response.jsonPath().getMap("$");
    }

    private static Map<String, Object> draft() {
        return Map.of("title", "the header's draft", "apparatus", "code");
    }

    /** An answer with what names the task set aside: its address and its token. */
    private static Map<String, Object> apartFromTheTask(Map<String, Object> answer) {
        Map<String, Object> rest = new LinkedHashMap<>(answer);
        assertThat(rest.remove("address")).isNotNull();
        assertThat(rest.remove("conflict_token")).isNotNull();
        return rest;
    }
}
