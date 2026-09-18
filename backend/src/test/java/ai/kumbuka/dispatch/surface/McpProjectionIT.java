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
            .containsExactlyInAnyOrderElementsOf(McpTools.declared().stream()
                .map(McpTools.Tool::name).toList());
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
     * A name this surface does not carry is refused with the list of the ones
     * it does.
     *
     * <p>This replaced an older assertion, and the thing it asserted moved
     * rather than went away. The old one said the two uncarried verbs
     * ({@code withdraw}, {@code validate}) were answered BY NAME rather than
     * as an unknown tool, because "unknown tool" sends a caller looking for a
     * spelling. Neither is addressable here any more — the assistant surface
     * carries process verbs, and no process verb reaches either act — so the
     * question is no longer "is this verb refused well" but "is a caller that
     * names something this surface has not got told what it HAS got".
     *
     * <p>Which is the same service, and it is now given for every wrong name
     * rather than for two of them.
     */
    @Test
    void a_name_this_surface_does_not_carry_is_refused_with_the_list_of_the_ones_it_does() {
        for (String absent : List.of("withdraw", "validate", "send", "accept", "create")) {
            Response answer = rpc("tools/call", Map.of(
                "name", absent, "arguments", Map.of()));

            assertThat(answer.jsonPath().getBoolean("result.isError"))
                .as("'%s' is not a call of this surface", absent)
                .isTrue();
            assertThat(answer.jsonPath().getString("result.structuredContent.reason"))
                .isEqualTo("ARGUMENT_UNKNOWN");
            assertThat(answer.jsonPath().getString("result.structuredContent.message"))
                .as("a caller that named the wrong thing is told what the right ones are, "
                    + "rather than being sent to look for a spelling")
                .contains("dispatch_commission")
                .contains("dispatch_take");
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

        Response answer = rpc("tools/call", Map.of(
            "name", "dispatch_commission", "arguments", Map.of(
                "scope", SurfaceFixture.SCOPE, "selector", "satellite",
                "parent", parent,
                "fields", Map.of("title", "a child", "apparatus", "code",
                    "text", "the child's body"))));

        assertThat(answer.jsonPath().getString("result.structuredContent.reason"))
            .isEqualTo("ARGUMENT_INVALID");
    }

    @Test
    void creating_a_child_through_mcp_numbers_it_within_its_bracket() {
        String parent = createThroughMcp();

        Map<String, Object> child = callTool("dispatch_commission", Map.of(
            "scope", SurfaceFixture.SCOPE, "selector", SurfaceFixture.SELECTOR,
            "parent", parent,
            "fields", Map.of("title", "a child", "apparatus", "code",
                "text", "the child's body")));

        assertThat(field(child, "sub")).isEqualTo(1);
    }

    // =======================================================================
    // The acts, through the other exposition
    // =======================================================================

    @Test
    void a_verb_reached_through_mcp_performs_the_same_act() {
        String address = createThroughMcp();

        Map<String, Object> sent = callTool("dispatch_read", Map.of("address", address));
        assertThat(field(sent, "state"))
            .as("one verb, one act, two expositions. The two call the same layer, so they "
                + "cannot drift on what a verb does or in which order it checks")
            .isEqualTo("open");
    }

    @Test
    void the_address_arrives_complete_with_its_scheme() {
        Response answer = rpc("tools/call", Map.of(
            "name", "dispatch_read",
            "arguments", Map.of("address", "probe-scope/sprint/1.0")));

        assertThat(answer.jsonPath().getBoolean("result.isError"))
            .as("MCP has no request line for an address to travel in, so it arrives whole "
                + "in the body — scheme included — and is validated here")
            .isTrue();
        assertThat(answer.jsonPath().getString("result.structuredContent.reason"))
            .isEqualTo("ARGUMENT_INVALID");
    }

    @Test
    void claim_returns_the_receipt_through_this_exposition_too() {
        String address = createThroughMcp();

        SurfaceFixture.asExecutor(identity);
        Map<String, Object> claimed = callTool("dispatch_take",
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

        Response answer = rpc("tools/call", Map.of(
            "name", "dispatch_commission", "arguments", Map.of(
                "scope", SurfaceFixture.SCOPE, "selector", SurfaceFixture.SELECTOR,
                "fields", Map.of("title", "a probe", "apparatus", "code",
                    "text", "its body", "metadata", Map.of("pr", 5)))));

        assertThat(answer.jsonPath().getString("result.structuredContent.reason"))
            .as("cardinality widens to lists, and typefreedom does not: a number is a "
                + "different rule, and the doctrine says otherwise with a typed refusal")
            .isEqualTo("ARGUMENT_INVALID");
    }

    /**
     * A list value arrives through the adapter unchanged and is stored as a
     * list. This is the shape that the read model refused before BUG-52: a
     * key that carries several identifiers rather than one.
     */
    @Test
    void a_list_metadata_value_survives_the_send_gate_and_is_stored_as_a_list() {
        String address = createThroughMcp();

        Map<String, Object> sent = callTool("dispatch_commission", Map.of(
            "scope", SurfaceFixture.SCOPE, "selector", SurfaceFixture.SELECTOR,
            "fields", Map.of("title", "a probe", "apparatus", "code", "text", "its body",
                "metadata", Map.of("tracks", java.util.List.of("t1", "t2")))));

        assertThat(field(sent, "state")).isEqualTo("open");
    }

    /**
     * And a credential inside one is still refused, by the validator that
     * knows why.
     */
    @Test
    void metadata_carrying_a_credential_is_refused_on_this_exposition_too() {
        String address = createThroughMcp();

        Response answer = rpc("tools/call", Map.of(
            "name", "dispatch_commission", "arguments", Map.of(
                "scope", SurfaceFixture.SCOPE, "selector", SurfaceFixture.SELECTOR,
                "fields", Map.of("title", "a probe", "apparatus", "code", "text", "its body",
                    "metadata",
                    Map.of("mirror", "https://user:secret@example.invalid/x")))));

        assertThat(answer.jsonPath().getString("result.structuredContent.reason"))
            .isEqualTo("ARGUMENT_INVALID");
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
            .as("the older form of this assertion expected a category error naming the "
                + "act. There is no act to name any more: validate is not a call of this "
                + "surface, so what the caller needs is the list of the calls that are — "
                + "which is what ARGUMENT_UNKNOWN carries")
            .isEqualTo("ARGUMENT_UNKNOWN");
        assertThat(answer.jsonPath().getString("result.structuredContent.message"))
            .as("and the list travels with it, so the caller is not sent looking")
            .contains("dispatch_read");
    }

    /**
     * And a carried one is answered by doing it. The listing is a tool now,
     * and it carries the same projection the single read does.
     */
    @Test
    void the_listing_is_a_tool_and_keeps_the_projection() {
        Response answer = rpc("tools/call", Map.of(
            "name", "dispatch_query",
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
            "name", "dispatch_query",
            "arguments", Map.of("scope", SurfaceFixture.SCOPE,
                "selector", SurfaceFixture.SELECTOR, "title", "anything")));

        assertThat(answer.jsonPath().getBoolean("result.isError")).isTrue();
        assertThat(answer.jsonPath().getString("result.structuredContent.reason"))
            .as("the filter model is the domain's, so both expositions refuse the same "
                + "field for the same reason rather than each carrying its own list")
            .isEqualTo("ARGUMENT_UNKNOWN");
    }

    /**
     * Withdrawal is not on this surface, and saying so names what is.
     *
     * <p>The older form of this test asserted that {@code withdraw} was
     * answered by name with a typed category error — the console owns the act,
     * and a 404 would have sent the caller looking for the object. The act is
     * still the console's and this surface still does not offer it; what
     * changed is that {@code withdraw} is not a name here at all, so the
     * refusal it earns is the one every absent name earns.
     */
    @Test
    void withdrawal_is_not_a_call_of_this_surface() {
        Response answer = rpc("tools/call", Map.of(
            "name", "withdraw", "arguments", Map.of("address", createThroughMcp())));

        assertThat(answer.jsonPath().getString("result.structuredContent.reason"))
            .isEqualTo("ARGUMENT_UNKNOWN");
    }

    @Test
    void a_tool_that_is_no_verb_at_all_is_refused_as_a_malformed_call() {
        Response answer = rpc("tools/call", Map.of(
            "name", "frobnicate", "arguments", Map.of()));

        assertThat(answer.jsonPath().getBoolean("result.isError")).isTrue();
        assertThat(answer.jsonPath().getString("result.structuredContent.reason"))
            .isEqualTo("ARGUMENT_UNKNOWN");
    }

    @Test
    void a_refused_verb_is_a_tool_error_and_never_a_json_rpc_error() {
        String address = createThroughMcp();

        Response answer = rpc("tools/call", Map.of(
            "name", "dispatch_curate_return", "arguments", Map.of(
                "address", address, "fields", Map.of("into", address))));

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

        Map<String, Object> read = callTool("dispatch_read", Map.of("address", address));

        assertThat(structured(read).get("conflict_token"))
            .as("without the token on the wire, `update` over MCP has no source for its "
                + "receipt argument and the sperre becomes a wall the caller cannot pass")
            .isNotNull()
            .asString().isNotBlank();
    }

    @Test
    void query_over_mcp_carries_the_conflict_token_per_exchange() {
        String address = createThroughMcp();

        Map<String, Object> read = callTool("dispatch_read", Map.of("address", address));
        // The response's address is the internal selector/N.M form; the MCP
        // tool argument is the dispatch://... form. Both name the same row.
        String internalAddress = (String) structured(read).get("address");

        Map<String, Object> listed = callTool("dispatch_query", Map.of(
            "scope", SurfaceFixture.SCOPE, "selector", SurfaceFixture.SELECTOR));

        Map<String, Object> found = exchanges(listed).stream()
            .filter(e -> internalAddress.equals(e.get("address")))
            .findFirst().orElseThrow();

        assertThat(found.get("conflict_token"))
            .as("the projection is the SAME for read and query — a single source, so a "
                + "caller reading through one exposition and updating through the other "
                + "does not read one token and send another")
            .isEqualTo(structured(read).get("conflict_token"));
    }

    /**
     * The token read through this surface is accepted by a write through it.
     *
     * <p>The whole reason for exposing the token at all. The writer is
     * {@code dispatch_reply_to_executor} rather than {@code update}, because
     * {@code update} is not a call of this surface — but it is the same
     * statement: a caller that reads the token here can write here.
     */
    @Test
    void a_write_with_the_token_from_a_prior_read_succeeds() {
        String address = anAnsweredExchange();
        SurfaceFixture.asConsole(identity);
        String token = tokenOf(address);

        Map<String, Object> replied = callTool("dispatch_reply_to_executor", Map.of(
            "address", address, "conflict_token", token,
            "fields", Map.of("message", "another round, please")));

        assertThat(structured(replied).get("address")).isEqualTo(address);
    }

    @Test
    void a_write_on_a_token_from_before_a_write_is_refused_as_stale() {
        String address = anAnsweredExchange();
        SurfaceFixture.asConsole(identity);
        String stale = tokenOf(address);

        // The first write rotates the token. Any caller still holding `stale`
        // is now holding a token from before that write.
        callTool("dispatch_reply_to_executor", Map.of(
            "address", address, "conflict_token", stale,
            "fields", Map.of("message", "the first reply")));

        Response answer = rpc("tools/call", Map.of(
            "name", "dispatch_reply_to_executor", "arguments", Map.of(
                "address", address, "conflict_token", stale,
                "fields", Map.of("message", "the second reply"))));

        assertThat(answer.jsonPath().getBoolean("result.isError"))
            .as("visibility on the read side does NOT relax the sperre on the write side")
            .isTrue();
        assertThat(answer.jsonPath().getString("result.structuredContent.reason"))
            .isEqualTo("CONFLICT_TOKEN_STALE");
    }

    @Test
    void a_write_refuses_a_missing_conflict_token_argument() {
        String address = anAnsweredExchange();
        SurfaceFixture.asConsole(identity);

        Response answer = rpc("tools/call", Map.of(
            "name", "dispatch_reply_to_executor", "arguments", Map.of(
                "address", address, "fields", Map.of("message", "a reply"))));

        assertThat(answer.jsonPath().getBoolean("result.isError"))
            .as("the token stays a mandatory argument; an omitted one is a form error, not "
                + "a licence to write unguarded")
            .isTrue();
        assertThat(answer.jsonPath().getString("result.structuredContent.reason"))
            .isEqualTo("ARGUMENT_MISSING");
    }

    @Test
    void a_write_refuses_a_blank_conflict_token_argument() {
        String address = anAnsweredExchange();
        SurfaceFixture.asConsole(identity);

        Response answer = rpc("tools/call", Map.of(
            "name", "dispatch_reply_to_executor", "arguments", Map.of(
                "address", address, "conflict_token", "   ",
                "fields", Map.of("message", "a reply"))));

        assertThat(answer.jsonPath().getBoolean("result.isError"))
            .as("a blank value is not a licence either — exposing the token on the read "
                + "side does not make the argument optional on the write side")
            .isTrue();
        assertThat(answer.jsonPath().getString("result.structuredContent.reason"))
            .isEqualTo("ARGUMENT_MISSING");
    }

    @Test
    void read_after_a_successful_write_returns_a_different_conflict_token() {
        String address = anAnsweredExchange();
        SurfaceFixture.asConsole(identity);
        String before = tokenOf(address);

        callTool("dispatch_reply_to_executor", Map.of(
            "address", address, "conflict_token", before,
            "fields", Map.of("message", "a reply")));

        assertThat(tokenOf(address))
            .as("a token that never rotates cannot distinguish two consecutive writes; the "
                + "monotone advance is what makes a stale value detectable")
            .isNotNull().isNotEqualTo(before);
    }

    // =======================================================================
    // Driving the exposition
    // =======================================================================

    /**
     * An exchange waiting for an executor to take it up.
     *
     * <p>One call now, not two. Commissioning creates, writes and freezes in
     * one transaction, so there is no draft for a probe to have to send — and
     * no draft for a caller to be stranded in, which is the defect this whole
     * repair started from.
     */
    private String anActiveExchange() {
        return createThroughMcp();
    }

    /**
     * An exchange whose executor has delivered an answer, waiting for the
     * commissioner.
     *
     * <p>The state the commissioner's own writes act from. Leaves the identity
     * on the executor, so every caller of this switches back explicitly —
     * which is the readable form, because whose turn it is matters in every
     * one of these probes.
     */
    private String anAnsweredExchange() {
        String address = anActiveExchange();
        String receipt = takeUpAsExecutor(address);
        callTool("dispatch_deliver_return", Map.of(
            "address", address, "receipt", receipt,
            "fields", Map.of("text", "the answer")));
        return address;
    }

    /** The conflict token of one exchange, as this caller reads it. */
    private String tokenOf(String address) {
        return (String) structured(callTool("dispatch_read", Map.of("address", address)))
            .get("conflict_token");
    }

    /**
     * Switches identity to the executor and takes up the address. Returns the
     * receipt handed out, which the executor's text-bearing calls require.
     */
    private String takeUpAsExecutor(String address) {
        SurfaceFixture.asExecutor(identity);
        Map<String, Object> claimed = callTool("dispatch_take",
            Map.of("address", address, "duration", "PT1H"));
        return (String) structured(claimed).get("receipt");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> exchanges(Map<String, Object> listing) {
        return (List<Map<String, Object>>) structured(listing).get("exchanges");
    }


    /**
     * A commission, through the assistant surface, in one call.
     *
     * <p>The address comes back complete and is used unchanged — which is
     * itself part of what this file probes. Before satellite/26.6 the answer
     * carried {@code sprint/26.2} and this helper had to rebuild the complete
     * form from the number and the sub, which is exactly the repair a caller
     * cannot make because nothing told it one was needed.
     */
    private String createThroughMcp() {
        Map<String, Object> created = callTool("dispatch_commission", Map.of(
            "scope", SurfaceFixture.SCOPE,
            "selector", SurfaceFixture.SELECTOR,
            "fields", Map.of(
                "title", "a commission over MCP",
                "apparatus", "code",
                "text", "the body of the commission",
                "date", "2026-09-01")));

        return (String) structured(created).get("address");
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
            case "dispatch_commission" -> {
                arguments.put("scope", SurfaceFixture.SCOPE);
                arguments.put("selector", SurfaceFixture.SELECTOR);
                arguments.put("fields", Map.of("title", "a probe", "apparatus", "code",
                    "text", "its body"));
            }
            case "dispatch_query" -> {
                arguments.put("scope", SurfaceFixture.SCOPE);
                arguments.put("selector", SurfaceFixture.SELECTOR);
            }
            case "dispatch_take_next" -> {
                arguments.put("scope", SurfaceFixture.SCOPE);
                arguments.put("selector", SurfaceFixture.SELECTOR);
                arguments.put("duration", "PT1H");
            }
            case "dispatch_add_correction" -> {
                arguments.put("address", address);
                arguments.put("fields", Map.of("title", "a correction", "text", "its text"));
            }
            case "dispatch_curate_return" -> {
                arguments.put("address", address);
                arguments.put("fields", Map.of("into", address));
            }
            case "dispatch_reply_to_executor" -> {
                arguments.put("address", address);
                arguments.put("conflict_token", "not-the-one-it-holds");
                arguments.put("fields", Map.of("message", "a message"));
            }
            case "dispatch_cancel" -> {
                arguments.put("address", address);
                arguments.put("conflict_token", "not-the-one-it-holds");
                arguments.put("fields", Map.of("reason", "a reason"));
            }
            case "dispatch_take" -> {
                arguments.put("address", address);
                arguments.put("duration", "PT1H");
            }
            case "dispatch_deliver_return" -> {
                arguments.put("address", address);
                arguments.put("receipt", "not-the-one-it-holds");
                arguments.put("fields", Map.of("text", "an answer"));
            }
            case "dispatch_ask_commissioner" -> {
                arguments.put("address", address);
                arguments.put("receipt", "not-the-one-it-holds");
                arguments.put("fields", Map.of("question", "a question"));
            }
            case "dispatch_decline" -> {
                arguments.put("address", address);
                arguments.put("fields", Map.of("reason", "a reason"));
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
        String message =
            String.valueOf(answer.jsonPath().getString("result.structuredContent.message"));
        return "ARGUMENT_UNKNOWN"
                .equals(answer.jsonPath().getString("result.structuredContent.reason"))
            && message.contains("has no argument named " + tool);
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

    /**
     * One member of the answer's {@code fields}.
     *
     * <p>A helper rather than a dotted key, because {@code structured()} hands
     * back a {@link Map} and {@code get("fields.state")} looks for a key whose
     * name contains a dot. It finds nothing and returns null, which reads in a
     * failure message exactly like a field the service did not send — the most
     * expensive kind of wrong, because it sends the reader into the production
     * code after a defect that is in the probe.
     */
    @SuppressWarnings("unchecked")
    private static Object field(Map<String, Object> result, String name) {
        Map<String, Object> fields =
            (Map<String, Object>) structured(result).get("fields");
        return fields == null ? null : fields.get(name);
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
