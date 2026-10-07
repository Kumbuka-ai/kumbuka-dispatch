package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.contract.TargetSurface;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The assistant surface over the wire: the protocol, the tool list, and the
 * closure of every call.
 *
 * <p>Criterion 1 on MCP: {@code tools/list} is the twenty-five of TAR-0004
 * section 3, nothing missing and nothing extra. Criterion 3 on MCP: every call
 * with an invented argument, at the top and under {@code fields}, is refused
 * with the argument's name and writes nothing — observed as an unchanged
 * fingerprint of every table a call can write. Criterion 5: an undeclared
 * filter on {@code dispatch_query} is refused. Criterion 6 on MCP: a call
 * under an earlier name is refused as unknown.
 *
 * <p>Red probe, observed: with the closure of the {@code fields} level removed
 * from {@code CallArguments}, the invented field is no longer refused and
 * {@link #every_call_refuses_an_invented_argument_by_name_at_either_level_and_writes_nothing}
 * turns red.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class McpProjectionIT {

    @Inject TestIdentityAssociation identity;

    private String open;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asConsole(identity);
        open = SurfaceFixture.address(SurfaceFixture.open("the projection's task", "code"));
    }

    @Test
    void initialize_announces_the_tool_capability() {
        Response answer = Mcp.rpc("initialize", Map.of());
        assertThat(answer.jsonPath().getString("result.serverInfo.name"))
            .isEqualTo("kumbuka-dispatch");
        assertThat(answer.jsonPath().getMap("result.capabilities.tools")).isNotNull();
    }

    @Test
    void tools_list_is_exactly_the_calls_of_the_target() {
        List<String> served = Mcp.rpc("tools/list", Map.of()).jsonPath()
            .getList("result.tools.name");
        assertThat(served)
            .as("TAR-0004 section 3, nothing missing and nothing extra")
            .containsExactlyInAnyOrderElementsOf(TargetSurface.MCP);
    }

    @Test
    void every_served_schema_is_closed_at_both_levels() {
        List<Map<String, Object>> tools = Mcp.rpc("tools/list", Map.of()).jsonPath()
            .getList("result.tools");
        for (Map<String, Object> tool : tools) {
            @SuppressWarnings("unchecked")
            Map<String, Object> schema = (Map<String, Object>) tool.get("inputSchema");
            assertThat(schema.get("additionalProperties")).as("%s", tool.get("name"))
                .isEqualTo(false);
            @SuppressWarnings("unchecked")
            Map<String, Object> fields = (Map<String, Object>)
                ((Map<String, Object>) schema.get("properties")).get("fields");
            if (fields != null) {
                assertThat(fields.get("additionalProperties")).as("%s.fields", tool.get("name"))
                    .isEqualTo(false);
            }
        }
    }

    @Test
    void a_call_under_an_earlier_name_is_refused_as_unknown() {
        for (String earlier : List.of("dispatch_commission", "dispatch_add_correction",
                "dispatch_accept_return", "dispatch_curate_return", "dispatch_reply_to_executor",
                "dispatch_cancel", "dispatch_close_bracket", "dispatch_take", "dispatch_take_next",
                "dispatch_deliver_return", "dispatch_ask_commissioner", "dispatch_decline",
                "send", "claim")) {
            String before = Writes.snapshot();
            Map<String, Object> refusal = Mcp.refusal(Mcp.call(earlier,
                Map.of("address", open)));
            assertThat(refusal.get("reason")).as(earlier).isEqualTo("ARGUMENT_UNKNOWN");
            assertThat(String.valueOf(refusal.get("message")))
                .as("the refusal names the call it does not know, and the ones it does")
                .contains(earlier).contains("dispatch_claim");
            assertThat(Writes.snapshot()).as("%s wrote nothing", earlier).isEqualTo(before);
        }
    }

    @Test
    void every_call_refuses_an_invented_argument_by_name_at_either_level_and_writes_nothing() {
        for (ProcessVerb call : ProcessVerb.values()) {
            Map<String, Object> top = new LinkedHashMap<>(complete(call));
            top.put("invented", "x");
            String before = Writes.snapshot();
            Map<String, Object> atTop = Mcp.refusal(Mcp.call(call.call(), top));
            assertThat(atTop.get("reason")).as(call.call()).isEqualTo("ARGUMENT_UNKNOWN");
            assertThat(String.valueOf(atTop.get("message"))).as(call.call())
                .contains("invented");
            assertThat(Writes.snapshot()).as("%s wrote nothing", call.call()).isEqualTo(before);

            Map<String, Object> nested = new LinkedHashMap<>(complete(call));
            @SuppressWarnings("unchecked")
            Map<String, Object> fields = new LinkedHashMap<>(
                (Map<String, Object>) nested.getOrDefault("fields", Map.of()));
            fields.put("invented_field", "x");
            nested.put("fields", fields);
            Map<String, Object> below = Mcp.refusal(Mcp.call(call.call(), nested));
            assertThat(below.get("reason")).as("%s.fields", call.call())
                .isEqualTo("ARGUMENT_UNKNOWN");
            assertThat(String.valueOf(below.get("message"))).as("%s.fields", call.call())
                .contains(call.hasFields() ? "invented_field" : "fields");
            assertThat(Writes.snapshot()).as("%s.fields wrote nothing", call.call())
                .isEqualTo(before);
        }
    }

    @Test
    void every_undeclared_argument_of_a_level_is_named_in_one_refusal() {
        Map<String, Object> filters = new LinkedHashMap<>();
        filters.put("scope", SurfaceFixture.SCOPE);
        filters.put("selector", SurfaceFixture.SELECTOR);
        filters.put("status", "open");
        filters.put("holder", "self");
        Map<String, Object> atTop = Mcp.refusal(Mcp.call("dispatch_query", filters));
        assertThat(atTop.get("reason")).isEqualTo("ARGUMENT_UNKNOWN");
        assertThat(String.valueOf(atTop.get("message")))
            .as("both filters, in the order the call carried them, and the declared ones")
            .contains("named status, holder.")
            .contains("state, apparatus, bracket, address");

        String before = Writes.snapshot();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("title", "two made-up fields");
        fields.put("apparatus", "code");
        fields.put("colour", "red");
        fields.put("weight", "1");
        Map<String, Object> below = Mcp.refusal(Mcp.call("dispatch_create", Map.of(
            "scope", SurfaceFixture.SCOPE, "selector", SurfaceFixture.SELECTOR,
            "fields", fields)));
        assertThat(below.get("reason")).isEqualTo("ARGUMENT_UNKNOWN");
        assertThat(String.valueOf(below.get("message")))
            .contains("argument under fields named colour, weight.");
        assertThat(Writes.snapshot()).as("the create wrote nothing").isEqualTo(before);

        Map<String, Object> both = new LinkedHashMap<>();
        both.put("scope", SurfaceFixture.SCOPE);
        both.put("selector", SurfaceFixture.SELECTOR);
        both.put("stray", "x");
        both.put("fields", Map.of("title", "t", "apparatus", "code", "colour", "red"));
        Map<String, Object> topFirst = Mcp.refusal(Mcp.call("dispatch_create", both));
        assertThat(topFirst.get("reason")).isEqualTo("ARGUMENT_UNKNOWN");
        assertThat(String.valueOf(topFirst.get("message")))
            .as("the top level is refused first, and alone")
            .contains("argument named stray.")
            .doesNotContain("colour");
        assertThat(Writes.snapshot()).as("nor did the one with both").isEqualTo(before);
    }

    @Test
    void an_undeclared_filter_on_query_is_refused_and_a_declared_one_narrows() {
        Map<String, Object> refusal = Mcp.refusal(Mcp.call("dispatch_query", Map.of(
            "scope", SurfaceFixture.SCOPE, "selector", SurfaceFixture.SELECTOR,
            "holder", "self")));
        assertThat(refusal.get("reason")).isEqualTo("ARGUMENT_UNKNOWN");
        assertThat(String.valueOf(refusal.get("message"))).contains("holder")
            .as("and names the filters it has instead")
            .contains("state, apparatus, bracket, address");

        Map<String, Object> listed = Mcp.answer(Mcp.call("dispatch_query", Map.of(
            "scope", SurfaceFixture.SCOPE, "selector", SurfaceFixture.SELECTOR,
            "address", open, "state", "open")));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) listed.get("tasks");
        assertThat(tasks).extracting(t -> t.get("address")).containsExactly(open);
        assertThat(listed.get("cut")).isEqualTo(false);

        String draft = String.valueOf(Mcp.answer(Mcp.call("dispatch_create", Map.of(
            "scope", SurfaceFixture.SCOPE, "selector", SurfaceFixture.SELECTOR,
            "fields", Map.of("title", "a draft beside it", "apparatus", "code")))).get("address"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> both = (List<Map<String, Object>>) Mcp.answer(Mcp.call(
            "dispatch_query", Map.of("scope", SurfaceFixture.SCOPE,
                "selector", SurfaceFixture.SELECTOR, "address", open + "," + draft)))
            .get("tasks");
        assertThat(both).extracting(t -> t.get("address"), Mcp::nextCalls)
            .as("each entry carries its own next")
            .anySatisfy(t -> assertThat(t.toList()).containsExactly(draft,
                Mcp.nextCalls(Mcp.answer(Mcp.call("dispatch_read", Map.of("address", draft))))))
            .anySatisfy(t -> assertThat(t.toList()).containsExactly(open,
                Mcp.nextCalls(Mcp.answer(Mcp.call("dispatch_read", Map.of("address", open))))));
    }

    @Test
    void a_filter_value_the_field_cannot_take_is_refused_by_name() {
        Map<String, Object> refusal = Mcp.refusal(Mcp.call("dispatch_query", Map.of(
            "scope", SurfaceFixture.SCOPE, "selector", SurfaceFixture.SELECTOR,
            "state", "needs_input")));
        assertThat(refusal.get("reason")).isEqualTo("ARGUMENT_INVALID");
        assertThat(String.valueOf(refusal.get("message"))).contains("state");
        assertThat(Mcp.reason(Mcp.call("dispatch_query", Map.of(
                "scope", SurfaceFixture.SCOPE, "selector", SurfaceFixture.SELECTOR,
                "state", ""))))
            .as("an empty value is refused too").isEqualTo("ARGUMENT_INVALID");
    }

    // -----------------------------------------------------------------------

    /** Every mandatory argument of a call, with a value of the right shape. */
    private Map<String, Object> complete(ProcessVerb call) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        Map<String, Object> fields = new LinkedHashMap<>();
        for (Argument argument : call.arguments()) {
            if (!argument.required()) {
                continue;
            }
            Object value = switch (argument.type()) {
                case Argument.ARRAY -> List.of("code");
                case Argument.BOOLEAN -> Boolean.TRUE;
                case Argument.INTEGER -> 10;
                case Argument.OBJECT -> Map.of();
                default -> argument.values().isEmpty()
                    ? valueFor(argument.name())
                    : argument.values().get(0);
            };
            (argument.isField() ? fields : arguments).put(argument.name(), value);
        }
        if (!fields.isEmpty()) {
            arguments.put("fields", fields);
        }
        return arguments;
    }

    private String valueFor(String name) {
        return switch (name) {
            case "address", "curated_in" -> open;
            case "scope" -> SurfaceFixture.SCOPE;
            case "selector" -> SurfaceFixture.SELECTOR;
            case "not_before" -> "2026-10-07T09:00:00Z";
            default -> "a " + name;
        };
    }
}
