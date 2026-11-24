/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.fixture.LogLines;
import ai.kumbuka.dispatch.platform.PlatformFixture;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.LogRecord;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The five acts on an instance on the verb surface: who may make them on each
 * surface, what the surface offers, where every act says it came from, and
 * what the log keeps of them.
 *
 * <p>Every expectation is written here from the order and the interim mapping
 * of REA-0010: the console's realm role holds the permission of each of the
 * five calls and the executor's holds none of them; the tool list and the
 * declaration name the six calls of this change and no other; every act
 * carries the path {@code interface}, the token's subject as its actor and no
 * authentication level (D17); and no log line carries a title, a text, a
 * value or a reference value (D23).
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class InstanceCallsIT {

    private static final String START = "start";
    private static final String CONCLUDE = "conclude";
    private static final String WITHDRAW = "withdraw";
    private static final String ATTACH = "reference_attach";
    private static final String DETACH = "reference_detach";
    private static final String PREFIX = "dispatch_";
    private static final String START_TOOL = PREFIX + START;
    private static final String TRANSITION = "ITEM_TRANSITION";

    private static final List<String> SIX = List.of(PREFIX + "read_definition", START_TOOL,
        PREFIX + CONCLUDE, PREFIX + WITHDRAW, PREFIX + ATTACH, PREFIX + DETACH);

    private static final String ADDRESS = "address";
    private static final String FIELDS = "fields";
    private static final String TOKEN = "conflict_token";
    private static final String REFER = "dispatch.instance.refer";

    private static final String TITLE = "zz-title-7319";
    private static final String INPUT = "zz-input-7319";
    private static final String VALUE = "zz-value-7319";
    private static final String REFERENCE = "zz-reference-7319";
    private static final String RETURN = "zz-return-7319";

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        PlatformFixture.grantDirectoryAccess();
        InstanceWire.as(identity, InstanceWire.CONSOLE);
    }

    // -----------------------------------------------------------------------
    // Who may
    // -----------------------------------------------------------------------

    @Test
    void the_console_makes_each_call_on_both_surfaces_and_the_executor_none() {
        String selector = InstanceWire.plant("admission").selector();

        for (boolean rest : List.of(false, true)) {
            InstanceWire.as(identity, InstanceWire.EXECUTOR);
            assertRefused(start(selector, rest), rest, START);
            InstanceWire.as(identity, InstanceWire.CONSOLE);
            Map<String, Object> answer = answered(start(selector, rest), rest, START);
            String address = String.valueOf(answer.get(ADDRESS));

            for (String call : List.of(ATTACH, DETACH, CONCLUDE)) {
                Map<String, Object> arguments = arguments(call, InstanceWire.token(answer));
                InstanceWire.as(identity, InstanceWire.EXECUTOR);
                assertRefused(on(address, call, arguments, rest), rest, call);
                InstanceWire.as(identity, InstanceWire.CONSOLE);
                answer = answered(on(address, call, arguments, rest), rest, call);
            }

            Map<String, Object> second = answered(start(selector, rest), rest, START);
            Map<String, Object> withdrawal = arguments(WITHDRAW, InstanceWire.token(second));
            String other = String.valueOf(second.get(ADDRESS));
            InstanceWire.as(identity, InstanceWire.EXECUTOR);
            assertRefused(on(other, WITHDRAW, withdrawal, rest), rest, WITHDRAW);
            InstanceWire.as(identity, InstanceWire.CONSOLE);
            answered(on(other, WITHDRAW, withdrawal, rest), rest, WITHDRAW);
        }
    }

    // -----------------------------------------------------------------------
    // What the surface offers
    // -----------------------------------------------------------------------

    @Test
    void the_tool_list_and_the_declaration_name_exactly_the_six_calls() {
        assertThat(InstanceWire.rpc("tools/list", Map.of()).jsonPath()
                .getList("result.tools.name", String.class))
            .as("tools/list offers the six tools of this change and no other")
            .containsExactlyElementsOf(SIX);
        Response declaration = given().get("/api/declaration");
        assertThat(declaration.jsonPath().getList("calls.identity", String.class))
            .as("and the declaration names the six calls").containsExactlyElementsOf(SIX);
        assertThat(declaration.jsonPath().getList("calls.effect_class", String.class))
            .as("each in the form its effect class names: the start a custom method on a "
                + "collection, the four calls on an instance custom methods on it")
            .containsExactly("COLLECTION_READ", "COLLECTION_COMPOUND", TRANSITION, TRANSITION,
                TRANSITION, TRANSITION);
        assertThat(declaration.jsonPath().getList("calls.rest_name", String.class))
            .containsExactly("read_definition", START, CONCLUDE, WITHDRAW, ATTACH, DETACH);
    }

    // -----------------------------------------------------------------------
    // Where every act comes from, and what the log keeps
    // -----------------------------------------------------------------------

    @Test
    void every_act_carries_the_path_interface_the_subject_and_no_authentication_level() {
        String selector = InstanceWire.plant("provenance").selector();
        Map<String, Object> answer = answered(start(selector, false), false, START);
        String address = String.valueOf(answer.get(ADDRESS));
        for (String call : List.of(ATTACH, DETACH, CONCLUDE)) {
            answer = answered(on(address, call, arguments(call, InstanceWire.token(answer)),
                true), true, call);
        }

        List<Map<String, Object>> acts = InstanceWire.rows("SELECT kind, path, actor_subject, "
            + "actor_owner_subject, authentication_level, permission FROM dispatch.act "
            + "WHERE instance_id = ? ORDER BY sequence", InstanceWire.instanceId(address));
        assertThat(acts).extracting(act -> act.get("kind"))
            .containsExactly(START, "refer", "unrefer", CONCLUDE);
        assertThat(acts).as("each act names its permission, came over an interface, by the "
                + "token's subject, and carries no authentication level and no owner")
            .extracting(act -> List.of(String.valueOf(act.get("path")),
                String.valueOf(act.get("actor_subject")),
                String.valueOf(act.get("actor_owner_subject")),
                String.valueOf(act.get("authentication_level"))))
            .containsOnly(List.of("interface", InstanceWire.SUBJECT, "null", "null"));
        assertThat(acts).extracting(act -> act.get("permission"))
            .containsExactly("dispatch.instance.start", REFER, REFER,
                "dispatch.instance.conclude");
    }

    @Test
    void no_log_line_carries_a_title_a_text_a_value_or_a_reference() {
        String selector = InstanceWire.plant("log").selector();
        List<LogRecord> lines = LogLines.of(PlatformLog.class, () -> {
            Map<String, Object> fields = new HashMap<>();
            fields.put("title", TITLE);
            fields.put("input", INPUT);
            fields.put("trigger", REFERENCE);
            fields.put("items", List.of(REFERENCE + "-item"));
            fields.put("values", Map.of("amount", 1, "summary", VALUE));
            Map<String, Object> started = InstanceWire.answer(InstanceWire.mcp(START_TOOL,
                InstanceWire.startArguments(selector, fields)));
            String address = String.valueOf(started.get(ADDRESS));
            InstanceWire.refusal(InstanceWire.mcp(START_TOOL, InstanceWire.startArguments(
                selector, Map.of("title", TITLE, "values", Map.of("summary", VALUE)))));
            Map<String, Object> attached = InstanceWire.answer(InstanceWire.mcp(PREFIX + ATTACH,
                Map.of(ADDRESS, address, FIELDS, Map.of("kind", "reference", "value", REFERENCE))));
            InstanceWire.answer(InstanceWire.mcp(PREFIX + CONCLUDE, Map.of(ADDRESS, address,
                TOKEN, InstanceWire.token(attached), FIELDS, Map.of("text", RETURN))));
        });

        assertThat(lines).extracting(LogLines::text)
            .as("RED STATE, observed: the capture sees the lines of the writes, one per act, "
                + "so its emptiness below is no capture that saw nothing")
            .filteredOn(line -> line.startsWith("written: "))
            .hasSize(3);
        assertThat(lines).extracting(LogLines::text)
            .as("no line carries a title, an input text, a field value, a reference value or a "
                + "return text (D23, DEC-0051)")
            .noneMatch(line -> line.contains(TITLE) || line.contains(INPUT)
                || line.contains(VALUE) || line.contains(REFERENCE) || line.contains(RETURN)
                || line.contains(selector));
    }

    // -----------------------------------------------------------------------

    private static Response start(String selector, boolean rest) {
        if (rest) {
            return given().contentType(ContentType.JSON)
                .body(Map.of(FIELDS, InstanceWire.minimalFields("a title")))
                .post("/api/" + InstanceWire.SLUG + "/" + selector + ":" + START);
        }
        return InstanceWire.mcp(START_TOOL,
            InstanceWire.startArguments(selector, InstanceWire.minimalFields("a title")));
    }

    /** The arguments of one call on an instance, beside its address. */
    private static Map<String, Object> arguments(String call, String token) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        if (ATTACH.equals(call) || DETACH.equals(call)) {
            arguments.put(FIELDS, Map.of("kind", "item", "value", "item-1"));
        } else {
            arguments.put(TOKEN, token);
            arguments.put(FIELDS, Map.of("text", "done"));
        }
        return arguments;
    }

    private static Response on(String address, String call, Map<String, Object> arguments,
                               boolean rest) {
        if (rest) {
            Map<String, Object> body = new LinkedHashMap<>(arguments);
            Object token = body.remove(TOKEN);
            return InstanceWire.rest(address, call, body,
                token == null ? null : String.valueOf(token));
        }
        Map<String, Object> withAddress = new LinkedHashMap<>(arguments);
        withAddress.put(ADDRESS, address);
        return InstanceWire.mcp(PREFIX + call, withAddress);
    }

    private static Map<String, Object> answered(Response response, boolean rest, String call) {
        if (rest) {
            assertThat(response.statusCode()).as("%s over REST: %s", call, response.asString())
                .isIn(200, 201);
            return InstanceWire.object(response, "$");
        }
        return InstanceWire.answer(response);
    }

    private static void assertRefused(Response response, boolean rest, String call) {
        if (rest) {
            assertThat(response.statusCode()).as("%s over REST by the executor", call)
                .isEqualTo(403);
            assertThat(response.jsonPath().getString("reason")).isEqualTo("ROLE_INSUFFICIENT");
            assertThat(response.jsonPath().getString("data.attempted")).isEqualTo(call);
        } else {
            Map<String, Object> refusal = InstanceWire.refusal(response);
            assertThat(refusal.get("reason")).as("%s over MCP by the executor", call)
                .isEqualTo("ROLE_INSUFFICIENT");
            assertThat(InstanceWire.data(refusal).get("attempted")).isEqualTo(PREFIX + call);
        }
    }
}
