package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.surface.VerbSurfaceSpecification;
import ai.kumbuka.dispatch.adapter.mcp.McpTools;
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

    /**
     * Every declared tool is reached, and none of them is an unknown name.
     *
     * <p>The coverage half of the probe for this exposition. Declaring a tool
     * and never routing it is the MCP-side version of a specified form nobody
     * can call, and {@code tools/list} cannot see the difference — it lists
     * what the catalogue says, not what the dispatcher answers.
     *
     * <p>The outcome of each call is not asserted: whether a transition
     * succeeds depends on the exchange's state, and pinning thirteen states
     * here would restate what {@code ExchangeSurfaceIT} already asserts on the
     * right one. What is asserted is that the call arrived at a verb — the one
     * thing a refusal of "not a tool of this server" would disprove.
     */
    @Test
    void every_declared_tool_is_routed_to_a_verb() {
        String address = createThroughMcp();

        List<String> unrouted = McpTools.declared().stream()
            .map(McpTools.Tool::name)
            .filter(tool -> isUnknownTool(tool, argumentsFor(tool, address)))
            .toList();

        assertThat(unrouted)
            .as("a tool the catalogue declares and the dispatcher does not know is a verb "
                + "no caller can reach, and tools/list cannot tell the difference")
            .isEmpty();
    }

    /**
     * The four uncarried verbs are routed here too, by name.
     *
     * <p>They are absent from {@code tools/list} — that is the omission — and
     * still answered when called, because "unknown tool" would send a caller
     * looking for a spelling instead of telling it the act does not exist.
     */
    @Test
    void every_uncarried_verb_is_answered_by_name_rather_than_as_unknown() {
        String address = createThroughMcp();

        for (VerbSurfaceSpecification.Row row : VerbSurfaceSpecification.of("uncarried")) {
            Response answer = rpc("tools/call", Map.of(
                "name", row.verb(), "arguments", argumentsFor(row.verb(), address)));

            assertThat(answer.jsonPath().getBoolean("result.isError"))
                .as("'%s' must be refused", row.verb())
                .isTrue();
            assertThat(answer.jsonPath().getString("result.structuredContent.reason"))
                .as("'%s' must be refused by name, not as an unknown tool", row.verb())
                .isNotEqualTo("PAYLOAD_MALFORMED");
        }
    }

    /**
     * A notification carries no id and takes no answer.
     *
     * <p>Answering one is a protocol error on our side rather than a
     * courtesy — a client that sent a notification is not reading a reply, so
     * one sent anyway desynchronises the stream.
     */
    @Test
    void a_notification_is_accepted_and_not_answered() {
        given().contentType(ContentType.JSON)
            .body(Map.of("jsonrpc", "2.0", "method", "notifications/initialized"))
            .post("/mcp")
            .then().statusCode(202).body(org.hamcrest.Matchers.emptyOrNullString());
    }

    @Test
    void an_envelope_that_is_not_json_rpc_is_refused_as_invalid_params() {
        given().contentType(ContentType.JSON).body(Map.of("method", "tools/list"))
            .post("/mcp")
            .then().statusCode(200).body("error.code", org.hamcrest.Matchers.equalTo(-32602));
    }

    /**
     * A child is created by naming its parent, and the two ways of saying
     * where it goes may not disagree.
     *
     * <p>Silently preferring one of them would decide by accident which of the
     * caller's two statements was meant.
     */
    @Test
    void creating_a_child_refuses_a_parent_that_contradicts_the_arguments() {
        String parent = createThroughMcp();

        Response answer = rpc("tools/call", Map.of("name", "create", "arguments", Map.of(
            "scope", SurfaceFixture.SCOPE, "selector", "satellite",
            "title", "a child", "apparatus", "code", "date", "2026-09-01",
            "parent", parent)));

        assertThat(answer.jsonPath().getString("result.structuredContent.reason"))
            .isEqualTo("PAYLOAD_MALFORMED");
    }

    @Test
    void creating_a_child_through_mcp_numbers_it_within_its_bracket() {
        String parent = createThroughMcp();

        Map<String, Object> child = callTool("create", Map.of(
            "scope", SurfaceFixture.SCOPE, "selector", SurfaceFixture.SELECTOR,
            "title", "a child", "apparatus", "code", "date", "2026-09-01",
            "parent", parent));

        assertThat(structured(child).get("sub")).isEqualTo(1);
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

    /**
     * Metadata arrives as JSON and is rendered to text before the domain sees
     * it.
     *
     * <p>The domain's metadata is string-to-string and validates what it
     * holds. Coercing here rather than refusing a number keeps the refusal in
     * one place: a value that must not be stored is refused by the validator
     * that knows why, not by a type mismatch in an adapter.
     */
    @Test
    void metadata_values_are_rendered_to_text_rather_than_refused_for_their_type() {
        String address = createThroughMcp();

        Map<String, Object> sent = callTool("send", Map.of(
            "address", address,
            "metadata", Map.of("pr", 5, "mirror", "https://example.invalid/pull/5")));

        assertThat(structured(sent).get("status")).isEqualTo("open");
    }

    /**
     * And a credential inside one is still refused, by the validator that
     * knows why.
     */
    @Test
    void metadata_carrying_a_credential_is_refused_on_this_exposition_too() {
        String address = createThroughMcp();

        Response answer = rpc("tools/call", Map.of("name", "send", "arguments", Map.of(
            "address", address,
            "metadata", Map.of("mirror", "https://user:secret@example.invalid/x"))));

        assertThat(answer.jsonPath().getString("result.structuredContent.reason"))
            .isEqualTo("METADATA_REFUSED");
    }

    // =======================================================================
    // What is not carried, and what is not a tool at all
    // =======================================================================

    @Test
    void an_uncarried_verb_is_answered_by_name_with_the_same_typed_reason() {
        Response answer = rpc("tools/call", Map.of(
            "name", "validate",
            "arguments", Map.of("address", SurfaceFixture.address("1.0"))));

        assertThat(answer.jsonPath().getBoolean("result.isError")).isTrue();
        assertThat(answer.jsonPath().getString("result.structuredContent.reason"))
            .as("absent from tools/list is what 'MCP omits' means, and it is not the same "
                + "as unknown. An unknown-tool reply would send the caller looking for a "
                + "spelling; a category error says the act does not exist here, and why")
            .isEqualTo("VERB_DEPTH_UNDECLARED");
    }

    /**
     * And a carried one is answered by doing it. The listing is a tool now,
     * and it carries the same projection the single read does.
     */
    @Test
    void the_listing_is_a_tool_and_keeps_the_projection() {
        Response answer = rpc("tools/call", Map.of(
            "name", "query",
            "arguments", Map.of("scope", SurfaceFixture.SCOPE,
                "selector", SurfaceFixture.SELECTOR)));

        assertThat(answer.jsonPath().getBoolean("result.isError"))
            .as("query is carried on both expositions now — MCP omits, and this is not "
                + "one of the omissions")
            .isFalse();
    }

    /** An undeclared filter field is refused here too, and names the field. */
    @Test
    void an_undeclared_filter_field_is_refused_on_the_tool_surface_as_well() {
        Response answer = rpc("tools/call", Map.of(
            "name", "query",
            "arguments", Map.of("scope", SurfaceFixture.SCOPE,
                "selector", SurfaceFixture.SELECTOR, "title", "anything")));

        assertThat(answer.jsonPath().getBoolean("result.isError")).isTrue();
        assertThat(answer.jsonPath().getString("result.structuredContent.reason"))
            .as("the filter model is the domain's, so both expositions refuse the same "
                + "field for the same reason rather than each carrying its own list")
            .isEqualTo("FILTER_FIELD_UNKNOWN");
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

    /**
     * Enough arguments for the call to reach its verb.
     *
     * <p>Not enough for it to succeed, which is the point: what is measured is
     * whether the tool is routed, not whether the exchange is in a state that
     * welcomes it.
     */
    private static Map<String, Object> argumentsFor(String tool, String address) {
        Map<String, Object> arguments = new LinkedHashMap<>();

        switch (tool) {
            case "create" -> {
                arguments.put("scope", SurfaceFixture.SCOPE);
                arguments.put("selector", SurfaceFixture.SELECTOR);
                arguments.put("title", "a probe");
                arguments.put("apparatus", "code");
                arguments.put("date", "2026-09-01");
            }
            case "query" -> {
                arguments.put("scope", SurfaceFixture.SCOPE);
                arguments.put("selector", SurfaceFixture.SELECTOR);
            }
            case "claim_next" -> {
                arguments.put("scope", SurfaceFixture.SCOPE);
                arguments.put("selector", SurfaceFixture.SELECTOR);
                arguments.put("duration", "PT1H");
            }
            case "append" -> {
                arguments.put("address", address);
                arguments.put("title", "a correction");
                arguments.put("apparatus", "code");
                arguments.put("date", "2026-09-01");
            }
            case "update" -> {
                arguments.put("address", address);
                arguments.put("conflict_token", "not-the-one-it-holds");
                arguments.put("draft", "a probe");
            }
            case "claim" -> {
                arguments.put("address", address);
                arguments.put("duration", "PT1H");
            }
            default -> arguments.put("address", address);
        }
        return arguments;
    }

    /**
     * Whether the dispatcher rejected this name as no tool of its own.
     *
     * <p>That refusal, and only that one, means the call never reached a verb.
     * Every other refusal is a verb having answered.
     */
    private static boolean isUnknownTool(String tool, Map<String, Object> arguments) {
        Response answer = rpc("tools/call", Map.of("name", tool, "arguments", arguments));
        return "PAYLOAD_MALFORMED"
                .equals(answer.jsonPath().getString("result.structuredContent.reason"))
            && String.valueOf(answer.jsonPath().getString("result.structuredContent.message"))
                .contains("not a tool of this server");
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
