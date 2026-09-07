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
     * Metadata arrives as JSON and is carried through unchanged. The domain's
     * validator refuses anything that is not a String or a list of them —
     * numbers, booleans and nested objects among them.
     *
     * <p>An earlier construction of this adapter rendered every value through
     * {@code toString} so a number would land as the identifier "5". That was
     * an aperture in the doctrine that ate a real list value on the way in
     * ("[a, b]") and turned a number into an accepted identifier. The refusal
     * belongs where the shape is known.
     */
    @Test
    void a_number_as_a_metadata_value_is_refused_rather_than_coerced_to_text() {
        String address = createThroughMcp();

        Response answer = rpc("tools/call", Map.of("name", "send", "arguments", Map.of(
            "address", address,
            "metadata", Map.of("pr", 5))));

        assertThat(answer.jsonPath().getString("result.structuredContent.reason"))
            .as("cardinality widens to lists, and typefreedom does not: a number is a "
                + "different rule, and the doctrine says otherwise with a typed refusal")
            .isEqualTo("METADATA_REFUSED");
    }

    /**
     * A list value arrives through the adapter unchanged and is stored as a
     * list. This is the shape that the read model refused before BUG-52: a
     * key that carries several identifiers rather than one.
     */
    @Test
    void a_list_metadata_value_survives_the_send_gate_and_is_stored_as_a_list() {
        String address = createThroughMcp();

        Map<String, Object> sent = callTool("send", Map.of(
            "address", address,
            "metadata", Map.of("tracks", java.util.List.of("t1", "t2"))));

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
    // The conflict token projection
    //
    // The write verb `update` declares conflict-token repetition and refuses
    // a missing or stale one. Without a way to READ the token off the MCP
    // surface, `update` is unreachable through it and the sperre is what
    // makes the whole surface unusable for a body-carrying exchange rather
    // than what it defends against. The token is exposed as a field of the
    // shared read projection so both expositions carry it from the same
    // source, and the sperre is NOT relaxed as part of that exposure.
    // =======================================================================

    @Test
    void read_over_mcp_hands_out_the_conflict_token_as_a_field() {
        String address = createThroughMcp();

        Map<String, Object> read = callTool("read", Map.of("address", address));

        assertThat(structured(read).get("conflictToken"))
            .as("without the token on the wire, `update` over MCP has no source for its "
                + "receipt argument and the sperre becomes a wall the caller cannot pass")
            .isNotNull()
            .asString().isNotBlank();
    }

    @Test
    void query_over_mcp_carries_the_conflict_token_per_exchange() {
        String address = createThroughMcp();

        Map<String, Object> read = callTool("read", Map.of("address", address));
        // The response's address is the internal selector/N.M form; the MCP
        // tool argument is the dispatch://... form. Both name the same row.
        String internalAddress = (String) structured(read).get("address");

        Map<String, Object> listed = callTool("query", Map.of(
            "scope", SurfaceFixture.SCOPE, "selector", SurfaceFixture.SELECTOR));

        Map<String, Object> found = exchanges(listed).stream()
            .filter(e -> internalAddress.equals(e.get("address")))
            .findFirst().orElseThrow();

        assertThat(found.get("conflictToken"))
            .as("the projection is the SAME for read and query — a single source, so a "
                + "caller reading through one exposition and updating through the other "
                + "does not read one token and send another")
            .isEqualTo(structured(read).get("conflictToken"));
    }

    @Test
    void update_over_mcp_with_the_token_from_a_prior_read_replaces_the_draft() {
        String address = anActiveExchange();
        String receipt = takeUpAsExecutor(address);
        Map<String, Object> read = callTool("read", Map.of("address", address));
        String token = (String) structured(read).get("conflictToken");
        String internalAddress = (String) structured(read).get("address");

        Map<String, Object> updated = callTool("update", Map.of(
            "address", address, "conflict_token", token,
            "draft", "the answer", "receipt", receipt));

        assertThat(structured(updated).get("address"))
            .as("this is the WHOLE reason for exposing the token: an update through the "
                + "same surface that read the token can now succeed")
            .isEqualTo(internalAddress);
    }

    @Test
    void update_over_mcp_on_a_token_from_before_a_write_is_refused_as_stale() {
        String address = anActiveExchange();
        String receipt = takeUpAsExecutor(address);
        String stale = (String) structured(callTool("read", Map.of("address", address)))
            .get("conflictToken");

        // The first write rotates the token. Any caller still holding `stale`
        // is now holding a token from before that write.
        callTool("update", Map.of(
            "address", address, "conflict_token", stale,
            "draft", "the first answer", "receipt", receipt));

        Response answer = rpc("tools/call", Map.of("name", "update", "arguments", Map.of(
            "address", address, "conflict_token", stale,
            "draft", "the second answer", "receipt", receipt)));

        assertThat(answer.jsonPath().getBoolean("result.isError"))
            .as("visibility on the read side does NOT relax the sperre on the write side")
            .isTrue();
        assertThat(answer.jsonPath().getString("result.structuredContent.reason"))
            .isEqualTo("CONFLICT_TOKEN_STALE");
    }

    @Test
    void update_over_mcp_refuses_a_missing_conflict_token_argument() {
        String address = anActiveExchange();
        String receipt = takeUpAsExecutor(address);

        Response answer = rpc("tools/call", Map.of("name", "update", "arguments", Map.of(
            "address", address, "draft", "the answer", "receipt", receipt)));

        assertThat(answer.jsonPath().getBoolean("result.isError"))
            .as("the token stays a mandatory argument; an omitted one is a form error, not "
                + "a licence to write unguarded")
            .isTrue();
        assertThat(answer.jsonPath().getString("result.structuredContent.reason"))
            .isEqualTo("PAYLOAD_MALFORMED");
    }

    @Test
    void update_over_mcp_refuses_a_blank_conflict_token_argument() {
        String address = anActiveExchange();
        String receipt = takeUpAsExecutor(address);

        Response answer = rpc("tools/call", Map.of("name", "update", "arguments", Map.of(
            "address", address, "conflict_token", "   ",
            "draft", "the answer", "receipt", receipt)));

        assertThat(answer.jsonPath().getBoolean("result.isError"))
            .as("a blank value is not a licence either — sichtbarkeit macht den Parameter "
                + "nicht optional")
            .isTrue();
        assertThat(answer.jsonPath().getString("result.structuredContent.reason"))
            .isEqualTo("PAYLOAD_MALFORMED");
    }

    @Test
    void read_after_a_successful_update_returns_a_different_conflict_token() {
        String address = anActiveExchange();
        String receipt = takeUpAsExecutor(address);
        String before = (String) structured(callTool("read", Map.of("address", address)))
            .get("conflictToken");

        callTool("update", Map.of(
            "address", address, "conflict_token", before,
            "draft", "the answer", "receipt", receipt));

        String after = (String) structured(callTool("read", Map.of("address", address)))
            .get("conflictToken");

        assertThat(after)
            .as("a token that never rotates cannot distinguish two consecutive writes; the "
                + "monotone advance is what makes a stale value detectable")
            .isNotNull().isNotEqualTo(before);
    }

    // =======================================================================
    // Driving the exposition
    // =======================================================================

    /** An exchange sent and waiting for an executor to take it up. */
    private String anActiveExchange() {
        String address = createThroughMcp();
        callTool("send", Map.of("address", address));
        return address;
    }

    /**
     * Switches identity to the executor and takes up the address. Returns the
     * receipt handed out, which the update verb requires.
     */
    private String takeUpAsExecutor(String address) {
        SurfaceFixture.asExecutor(identity);
        Map<String, Object> claimed = callTool("claim",
            Map.of("address", address, "duration", "PT1H"));
        return (String) structured(claimed).get("receipt");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> exchanges(Map<String, Object> listing) {
        return (List<Map<String, Object>>) structured(listing).get("exchanges");
    }


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
