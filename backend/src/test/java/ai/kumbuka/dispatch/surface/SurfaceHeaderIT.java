/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import io.restassured.http.Header;
import io.restassured.http.Headers;
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
 * <p>A value the header cannot take is {@code HEADER_INVALID} 400 with no
 * {@code data}, decided after the caller is authenticated and before anything
 * else: before the route, the address, the body and the task (C14.4, C14.5).
 * The MCP adapter does not read the header at all.
 *
 * <p>Red probes, observed: with the header unread and the surface fixed to
 * REST, {@link #with_the_header_rest_answers_what_the_assistant_surface_answers}
 * turns red at its first comparison, on {@code next[0].call}; with an unknown
 * value taken like a missing one,
 * {@link #an_unknown_value_is_header_invalid_and_writes_nothing} turns red; and
 * against the code that refused the header as {@code ARGUMENT_INVALID} after
 * the route was found, both that test and
 * {@link #the_header_is_decided_before_anything_else} turn red.
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
        assertThat(body(listed)).as("the page carries its objects under objects")
            .containsKey("objects").doesNotContainKey("tasks");
        assertThat(given().get(SurfaceFixture.collection() + "?address=" + address)
                .jsonPath().getMap("$")).as("and so does the page without the header")
            .containsKey("objects").doesNotContainKey("tasks");
        Response rejected = assistant().get(SurfaceFixture.collection() + "?page_size=abc");
        assertThat(rejected.statusCode()).isEqualTo(400);
        assertThat(rejected.jsonPath().getString("reason")).isEqualTo("PAGE_SIZE_REJECTED");
        assertThat(rejected.jsonPath().getList("data.offenders", String.class))
            .containsExactly("page_size");
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
        assertThat(again.jsonPath().getString("reason")).isEqualTo("TRANSITION_REFUSED");
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
    void an_unknown_value_is_header_invalid_and_writes_nothing() {
        for (String value : List.of("mcp", "Assistant", "rest", "")) {
            String before = Writes.snapshot();
            Response refused = given().header(HEADER, value).contentType(ContentType.JSON)
                .body(Map.of("fields", draft()))
                .post(SurfaceFixture.collection());
            headerInvalid(refused, "'" + value + "'");
            assertThat(Writes.snapshot()).as("'%s' wrote nothing", value).isEqualTo(before);
        }

        String before = Writes.snapshot();
        Response twice = given().header(HEADER, ASSISTANT, ASSISTANT)
            .contentType(ContentType.JSON).body(Map.of("fields", draft()))
            .post(SurfaceFixture.collection());
        headerInvalid(twice, "the header twice");
        assertThat(Writes.snapshot()).as("twice wrote nothing").isEqualTo(before);
    }

    @Test
    void the_header_is_decided_before_anything_else() {
        String id = SurfaceFixture.open("the header's first task", "code");
        RequestSpecification wrong = given().header(HEADER, "mcp");

        headerInvalid(wrong.post(SurfaceFixture.item(id) + ":invented"),
            "before the route: a verb not carried");
        headerInvalid(given().header(HEADER, "mcp").delete(SurfaceFixture.collection()),
            "before the route: a method not carried");
        headerInvalid(given().header(HEADER, "mcp").get(SurfaceFixture.item("999999.0")),
            "before the address: a task that is not there");
        headerInvalid(given().header(HEADER, "mcp").get(
                "/api/elsewhere/" + SurfaceFixture.SELECTOR + "/" + id),
            "before the scope: a scope the caller does not see");
        headerInvalid(given().header(HEADER, "mcp").contentType(ContentType.JSON)
                .body("[not json").post(SurfaceFixture.item(id) + ":claim"),
            "before the body: no JSON object");
        headerInvalid(given().header(HEADER, "mcp").contentType(ContentType.JSON)
                .body(Map.of("duration", "PT1H")).post(SurfaceFixture.item(id) + ":claim"),
            "before the caller's part");
    }

    @Test
    void the_mcp_adapter_does_not_read_the_header() {
        String id = SurfaceFixture.open("the adapter's task", "code");
        String address = SurfaceFixture.address(id);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", 1);
        envelope.put("method", "tools/call");
        envelope.put("params", Map.of("name", "dispatch_read",
            "arguments", Map.of("address", address)));

        for (Headers headers : List.of(new Headers(new Header(HEADER, "mcp")),
                new Headers(new Header(HEADER, ASSISTANT), new Header(HEADER, ASSISTANT)))) {
            Response read = given().headers(headers).contentType(ContentType.JSON)
                .body(envelope).post("/mcp");
            assertThat(read.statusCode()).as("%s", headers).isEqualTo(200);
            assertThat(Mcp.answer(read)).as("%s", headers)
                .isEqualTo(Mcp.answer(Mcp.call("dispatch_read", Map.of("address", address))));
        }
    }

    @Test
    void the_header_decides_nothing_but_the_names() {
        String id = SurfaceFixture.open("the role's task", "code");
        String before = Writes.snapshot();

        Response without = given().contentType(ContentType.JSON)
            .body(Map.of("duration", "PT1H")).post(SurfaceFixture.item(id) + ":claim");
        Response with = assistant().contentType(ContentType.JSON)
            .body(Map.of("duration", "PT1H")).post(SurfaceFixture.item(id) + ":claim");

        assertThat(without.jsonPath().getString("reason")).isEqualTo("ROLE_INSUFFICIENT");
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

    /** {@code HEADER_INVALID} 400: the header and its one value named, and no data. */
    private static void headerInvalid(Response refused, String what) {
        assertThat(refused.statusCode()).as(what).isEqualTo(400);
        assertThat(refused.jsonPath().getString("reason")).as(what).isEqualTo("HEADER_INVALID");
        assertThat(refused.jsonPath().getString("message")).as(what)
            .contains(HEADER).contains(ASSISTANT);
        assertThat(body(refused)).as(what + ": no data").doesNotContainKey("data");
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
