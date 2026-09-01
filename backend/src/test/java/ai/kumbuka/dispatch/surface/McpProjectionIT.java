package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.api.VerbSurfaceSpecification;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The MCP exposition: a projection that omits and adds nothing.
 *
 * <p>What is asserted here is not that MCP works — that would be the same
 * verbs a second time — but the three things that make it a <em>projection</em>
 * rather than a second surface: it declares exactly the carried verbs; a verb
 * reached through it does the same act; and a verb the scheme does not carry
 * is answered by name with the same typed reason, rather than as an unknown
 * tool.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class McpProjectionIT {

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asConsole(identity);
    }

    // =======================================================================
    // The declaration
    // =======================================================================

    @Test
    void tools_list_declares_exactly_the_carried_verbs() {
        List<String> declared = rpc("tools/list", Map.of())
            .jsonPath().getList("result.tools.name");

        assertThat(declared)
            .as("MCP omits and never adds, and today there is no declared omission. A tool "
                + "with no verb behind it is an addition; a verb with no tool is an "
                + "omission nobody declared")
            .containsExactlyInAnyOrderElementsOf(VerbSurfaceSpecification.carriedVerbs());
    }

    @Test
    void initialize_announces_the_tool_capability_and_nothing_it_cannot_do() {
        rpc("initialize", Map.of()).then()
            .statusCode(200)
            .body("result.serverInfo.name", org.hamcrest.Matchers.equalTo("kumbuka-dispatch"))
            .body("result.capabilities.tools", org.hamcrest.Matchers.notNullValue());
    }

    // =======================================================================
    // The acts, through the other exposition
    // =======================================================================

    @Test
    void a_verb_reached_through_mcp_performs_the_same_act() {
        String address = createThroughMcp();

        Map<String, Object> sent = callTool("send", Map.of("address", address));
        assertThat(structured(sent).get("status"))
            .as("one verb, one act, two expositions. The two call the same layer, so they "
                + "cannot drift on what a verb does or in which order it checks")
            .isEqualTo("open");
    }

    @Test
    void the_address_arrives_complete_with_its_scheme() {
        Response answer = rpc("tools/call", Map.of(
            "name", "read",
            "arguments", Map.of("address", "probe-scope/sprint/1.0")));

        assertThat(answer.jsonPath().getBoolean("result.isError"))
            .as("MCP has no request line for an address to travel in, so it arrives whole "
                + "in the body — scheme included — and is validated here")
            .isTrue();
        assertThat(answer.jsonPath().getString("result.structuredContent.reason"))
            .isEqualTo("ADDRESS_MALFORMED");
    }

    @Test
    void claim_returns_the_receipt_through_this_exposition_too() {
        String address = createThroughMcp();
        callTool("send", Map.of("address", address));

        SurfaceFixture.asExecutor(identity);
        Map<String, Object> claimed = callTool("claim",
            Map.of("address", address, "duration", "PT1H"));

        assertThat(structured(claimed).get("receipt"))
            .as("the receipt is minted by the service and handed out once, on whichever "
                + "exposition asked for it")
            .isNotNull();
    }

    // =======================================================================
    // What is not carried, and what is not a tool at all
    // =======================================================================

    @Test
    void an_uncarried_verb_is_answered_by_name_with_the_same_typed_reason() {
        Response answer = rpc("tools/call", Map.of(
            "name", "query",
            "arguments", Map.of("scope", SurfaceFixture.SCOPE,
                "selector", SurfaceFixture.SELECTOR)));

        assertThat(answer.jsonPath().getBoolean("result.isError")).isTrue();
        assertThat(answer.jsonPath().getString("result.structuredContent.reason"))
            .as("absent from tools/list is what 'MCP omits' means, and it is not the same "
                + "as unknown. An unknown-tool reply would send the caller looking for a "
                + "spelling; a category error says the act does not exist here, and why")
            .isEqualTo("VERB_NOT_CARRIED");
    }

    @Test
    void withdraw_names_the_console_here_as_well() {
        String address = createThroughMcp();

        Response answer = rpc("tools/call", Map.of(
            "name", "withdraw", "arguments", Map.of("address", address)));

        assertThat(answer.jsonPath().getString("result.structuredContent.reason"))
            .isEqualTo("WITHDRAWAL_VIA_CONSOLE_ONLY");
    }

    @Test
    void a_tool_that_is_no_verb_at_all_is_refused_as_a_malformed_call() {
        Response answer = rpc("tools/call", Map.of(
            "name", "frobnicate", "arguments", Map.of()));

        assertThat(answer.jsonPath().getBoolean("result.isError")).isTrue();
        assertThat(answer.jsonPath().getString("result.structuredContent.reason"))
            .isEqualTo("PAYLOAD_MALFORMED");
    }

    @Test
    void a_refused_verb_is_a_tool_error_and_never_a_json_rpc_error() {
        String address = createThroughMcp();

        Response answer = rpc("tools/call", Map.of(
            "name", "consume", "arguments", Map.of("address", address)));

        assertThat(answer.jsonPath().getString("result.error")).isNull();
        assertThat(answer.jsonPath().getBoolean("result.isError"))
            .as("a JSON-RPC error says the call could not be made, and every refusal in "
                + "this service is a call that was made and answered")
            .isTrue();
    }

    @Test
    void a_method_this_server_does_not_speak_is_a_json_rpc_error() {
        assertThat(rpc("resources/list", Map.of()).jsonPath().getInt("error.code"))
            .as("the protocol's own fault classes are the protocol's, and this one is")
            .isEqualTo(-32601);
    }

    // =======================================================================
    // Driving the exposition
    // =======================================================================

    private String createThroughMcp() {
        Map<String, Object> created = callTool("create", Map.of(
            "scope", SurfaceFixture.SCOPE,
            "selector", SurfaceFixture.SELECTOR,
            "title", "a commission over MCP",
            "apparatus", "code",
            "date", "2026-09-01"));

        return SurfaceFixture.address(
            structured(created).get("number") + "." + structured(created).get("sub"));
    }

    /** One tool call, asserted to have succeeded, and its result. */
    private Map<String, Object> callTool(String tool, Map<String, Object> arguments) {
        Response answer = rpc("tools/call", Map.of("name", tool, "arguments", arguments));

        assertThat(answer.jsonPath().getBoolean("result.isError"))
            .as("'%s' was expected to succeed but was refused: %s", tool,
                answer.jsonPath().getString("result.structuredContent.message"))
            .isFalse();
        return answer.jsonPath().getMap("result");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> structured(Map<String, Object> result) {
        return (Map<String, Object>) result.get("structuredContent");
    }

    private static Response rpc(String method, Map<String, Object> params) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", 1);
        envelope.put("method", method);
        envelope.put("params", params);

        return given().contentType(ContentType.JSON).accept(ContentType.JSON)
            .body(envelope).post("/mcp");
    }
}
