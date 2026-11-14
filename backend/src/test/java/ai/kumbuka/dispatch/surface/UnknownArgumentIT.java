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
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every surface refuses an argument it does not declare, at every depth, with
 * {@code ARGUMENT_UNKNOWN}; nothing is dropped silently (C7.8).
 *
 * <p>Every call {@code verb-surface.tsv} names, which is every call of the
 * declaration: on MCP an undeclared member at the top level and one inside
 * {@code fields}; on REST one in the query, and for a call whose route takes a
 * body ({@code POST}, {@code PATCH}) one at the top level of the body and one
 * inside its {@code fields}. For a call that declares no {@code fields}, the
 * member {@code fields} is itself undeclared and is refused as such. The
 * undeclared name is a marker no declaration holds, and the refusal must not
 * repeat it (DEC-0043 R19). No refused request writes anything.
 *
 * <p>The calls are read from the specification in the test tree, never from
 * the service; that they are the calls the service declares is checked against
 * the served tool list.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class UnknownArgumentIT {

    /** A name no declaration holds; the refusal names what is accepted, never this. */
    private static final String UNDECLARED = "zz_undeclared_7781";

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asConsole(identity);
    }

    /**
     * One request, as the check reads its answer.
     *
     * @param reason the refusal's code; absent where the call was answered
     */
    record Observed(String surface, String call, String where, @Nullable String reason,
                    String body) {
    }

    /**
     * Every request not refused as {@code ARGUMENT_UNKNOWN}, or refused with
     * the undeclared name repeated; empty where every one was.
     */
    static List<String> offences(List<Observed> observed) {
        List<String> found = new ArrayList<>();
        for (Observed one : observed) {
            String at = one.surface() + " " + one.call() + " (" + one.where() + ")";
            if (!"ARGUMENT_UNKNOWN".equals(one.reason())) {
                found.add(at + ": " + (one.reason() == null ? "answered" : one.reason()));
            } else if (one.body().contains(UNDECLARED)) {
                found.add(at + ": repeats the undeclared name");
            }
        }
        return found;
    }

    @Test
    void the_specification_names_every_call_of_the_tool_list() {
        List<String> tools = Mcp.rpc("tools/list", Map.of())
            .jsonPath().getList("result.tools.name", String.class);
        assertThat(VerbSurfaceSpecification.calls().stream().map(c -> "dispatch_" + c).toList())
            .as("the calls probed below are every call the service declares")
            .containsExactlyInAnyOrderElementsOf(tools);
    }

    @Test
    void every_call_refuses_an_undeclared_argument_on_both_surfaces_at_every_depth() {
        String id = SurfaceFixture.open("probed with undeclared arguments", "code");
        String before = Writes.snapshot();
        List<Observed> observed = new ArrayList<>();

        for (VerbSurfaceSpecification.Row row : VerbSurfaceSpecification.of("call")) {
            String tool = "dispatch_" + row.verb();
            Map<String, Object> target = "item".equals(row.depth())
                ? Map.of("address", SurfaceFixture.address(id))
                : Map.of("scope", SurfaceFixture.SCOPE, "selector", SurfaceFixture.SELECTOR);

            observed.add(overMcp(tool, "top level", with(target, UNDECLARED, "x")));
            observed.add(overMcp(tool, "inside fields",
                with(target, "fields", Map.of(UNDECLARED, "x"))));

            String path = row.path().replace("{scope}", SurfaceFixture.SCOPE)
                .replace("{selector}", SurfaceFixture.SELECTOR).replace("{id}", id);
            observed.add(overRest(row, "query",
                given().contentType(ContentType.JSON).queryParam(UNDECLARED, "x"), path));
            if ("POST".equals(row.method()) || "PATCH".equals(row.method())) {
                observed.add(overRest(row, "body, top level",
                    given().contentType(ContentType.JSON).body(Map.of(UNDECLARED, "x")), path));
                observed.add(overRest(row, "body, inside fields",
                    given().contentType(ContentType.JSON)
                        .body(Map.of("fields", Map.of(UNDECLARED, "x"))), path));
            }
        }

        assertThat(observed).as("every call was probed").hasSizeGreaterThan(100);
        assertThat(offences(observed))
            .as("an undeclared argument is refused as ARGUMENT_UNKNOWN on every call, both "
                + "surfaces, at the top level and inside fields; nothing is dropped (C7.8)")
            .isEmpty();
        assertThat(Writes.snapshot()).as("no refused request wrote anything").isEqualTo(before);
    }

    /** The red state, observed on every build: each kind of offence is reported. */
    @Test
    void the_checks_report_each_offence_they_exist_for() {
        assertThat(offences(List.of(
                new Observed("mcp", "dispatch_read", "top level", null, "{}"),
                new Observed("rest", "update", "body, inside fields", "ARGUMENT_INVALID", "{}"),
                new Observed("rest", "query", "query", "ARGUMENT_UNKNOWN",
                    "{\"offenders\":[\"" + UNDECLARED + "\"]}"),
                new Observed("mcp", "dispatch_create", "inside fields", "ARGUMENT_UNKNOWN",
                    "{\"accepts\":[\"fields.title\"]}"))))
            .as("RED STATE, observed: an undeclared argument dropped and the call answered, "
                + "one refused under another code, and a refusal repeating the name")
            .containsExactly("mcp dispatch_read (top level): answered",
                "rest update (body, inside fields): ARGUMENT_INVALID",
                "rest query (query): repeats the undeclared name");
    }

    private static Observed overMcp(String tool, String where, Map<String, Object> arguments) {
        Response answer = Mcp.call(tool, arguments);
        boolean refused = Boolean.TRUE.equals(answer.jsonPath().get("result.isError"));
        return new Observed("mcp", tool, where,
            refused ? answer.jsonPath().getString("result.structuredContent.reason") : null,
            answer.asString());
    }

    private static Observed overRest(VerbSurfaceSpecification.Row row, String where,
                                     RequestSpecification request, String path) {
        Response answer = request.request(row.method(), path);
        String reason = answer.statusCode() >= 400 ? answer.jsonPath().getString("reason") : null;
        return new Observed("rest", row.verb(), where, reason, answer.asString());
    }

    private static Map<String, Object> with(Map<String, Object> target, String name,
                                            Object value) {
        Map<String, Object> arguments = new LinkedHashMap<>(target);
        arguments.put(name, value);
        return arguments;
    }
}
