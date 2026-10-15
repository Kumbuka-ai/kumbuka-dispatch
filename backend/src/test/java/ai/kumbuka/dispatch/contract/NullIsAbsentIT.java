/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.contract;

import ai.kumbuka.dispatch.surface.Mcp;
import ai.kumbuka.dispatch.surface.SurfaceFixture;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * JSON {@code null} on an argument is the argument not sent (DEC-0043 R17):
 * every call made once with {@code null} and once without the argument
 * answers alike, on the assistant surface and on REST.
 *
 * <p>Two halves. The first walks every call and every argument it declares
 * from the table of {@link TargetSurface} — the expectation is written by hand
 * from the concept, never read from the declaration — and compares the two
 * answers byte for byte. The calls are aimed where nothing can be written, at
 * a task or a scope that does not exist, so the two answers can be equal at
 * all; what they prove is that a {@code null} passes the checks of the
 * arguments exactly as an absence does: a required one is refused as missing
 * either way, and an optional one goes on to the same refusal of the address.
 * The second half shows the fallback is taken where it matters: a listing, a
 * claim's lease and a draft's text, each with the argument {@code null} and
 * left out, on the real scope.
 *
 * <p>REST carries arguments in the body only on POST and PATCH; a query
 * string or a header cannot carry a {@code null}, so the calls reached by GET
 * and DELETE are compared on the assistant surface alone.
 *
 * <p>RED STATE, observed 2026-10-15: with the line in {@code CallPayload}
 * that drops a {@code null} removed, this turns red on all twenty-five calls
 * over MCP, each for the undeclared name sent as {@code null}, which is then
 * refused as {@code ARGUMENT_UNKNOWN} where its absence goes on to the
 * refusal of the address. The declared arguments stayed green: their checks
 * already read a {@code null} as absent, which this probe now holds.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class NullIsAbsentIT {

    /** An address no task has, in the probe's own scope. */
    private static final String NOWHERE = SurfaceFixture.address("999999.0");

    /** A scope nobody is a member of. */
    private static final String NO_SCOPE = "zz-no-such-scope";

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asConsole(identity);
    }

    // =======================================================================
    // Every call, every argument: null and absent answer alike
    // =======================================================================

    @Test
    void every_argument_sent_as_null_answers_as_the_argument_left_out_on_both_surfaces() {
        List<String> differing = new ArrayList<>();
        for (Map.Entry<String, TargetSurface.Arguments> call : TargetSurface.ARGUMENTS.entrySet()) {
            String verb = call.getKey();
            for (String name : call.getValue().top()) {
                compare(verb, name, false, differing);
            }
            for (String name : call.getValue().fields()) {
                compare(verb, name, true, differing);
            }
            compare(verb, "zz_undeclared", false, differing);
            if (!call.getValue().fields().isEmpty()) {
                compareFieldsObject(verb, differing);
            }
        }
        assertThat(differing).as("calls whose answer differs between null and absent")
            .isEmpty();
    }

    private void compare(String verb, String name, boolean underFields, List<String> differing) {
        Map<String, Object> absent = complete(verb);
        Map<String, Object> nulled = complete(verb);
        Map<String, Object> level = underFields ? fieldsOf(nulled) : nulled;
        level.put(name, null);
        if (underFields) {
            fieldsOf(absent).remove(name);
        } else {
            absent.remove(name);
        }
        String where = verb + (underFields ? " fields." : " ") + name;
        String overMcpAbsent = Mcp.call("dispatch_" + verb, absent).asString();
        String overMcpNull = Mcp.call("dispatch_" + verb, nulled).asString();
        if (!overMcpAbsent.equals(overMcpNull)) {
            differing.add(where + " over MCP: " + overMcpAbsent + " / " + overMcpNull);
        }
        if (carriesABody(verb) && !pathArgument(verb, name)) {
            String overRestAbsent = rest(verb, absent).asString();
            String overRestNull = rest(verb, nulled).asString();
            if (!overRestAbsent.equals(overRestNull)) {
                differing.add(where + " over REST: " + overRestAbsent + " / " + overRestNull);
            }
        }
    }

    private void compareFieldsObject(String verb, List<String> differing) {
        Map<String, Object> absent = complete(verb);
        absent.remove("fields");
        Map<String, Object> nulled = complete(verb);
        nulled.put("fields", null);
        String overMcpAbsent = Mcp.call("dispatch_" + verb, absent).asString();
        String overMcpNull = Mcp.call("dispatch_" + verb, nulled).asString();
        if (!overMcpAbsent.equals(overMcpNull)) {
            differing.add(verb + " fields over MCP: " + overMcpAbsent + " / " + overMcpNull);
        }
    }

    // =======================================================================
    // The fallback is taken: a listing, a lease, a draft
    // =======================================================================

    @Test
    void a_listing_with_every_optional_argument_null_is_the_listing_with_none() {
        SurfaceFixture.open("listed", "code");
        Map<String, Object> plain = collection(SurfaceFixture.SCOPE);
        Map<String, Object> nulled = collection(SurfaceFixture.SCOPE);
        for (String name : List.of("state", "apparatus", "bracket", "address", "page_size",
                "after", "sort", "direction")) {
            nulled.put(name, null);
        }
        Response withNulls = Mcp.call("dispatch_query", nulled);
        Mcp.answer(withNulls);
        assertThat(withNulls.asString()).isEqualTo(Mcp.call("dispatch_query", plain).asString());
    }

    @Test
    void a_claim_with_a_null_duration_holds_for_the_fallback_of_thirty_minutes() {
        String first = SurfaceFixture.address(SurfaceFixture.open("first", "code"));
        String second = SurfaceFixture.address(SurfaceFixture.open("second", "code"));
        SurfaceFixture.asExecutor(identity);

        Map<String, Object> nulled = new LinkedHashMap<>();
        nulled.put("address", first);
        nulled.put("duration", null);
        nulled.put("idempotency_key", null);
        Instant before = Instant.now();
        Map<String, Object> withNull = Mcp.answer(Mcp.call("dispatch_claim", nulled));
        Map<String, Object> without = Mcp.answer(Mcp.call("dispatch_claim",
            Map.of("address", second)));

        Duration held = Duration.between(before, Instant.parse(Mcp.field(withNull,
            "lease_expires_at")));
        assertThat(held).isBetween(Duration.ofMinutes(29), Duration.ofMinutes(31));
        assertThat(Duration.between(Instant.parse(Mcp.field(withNull, "lease_expires_at")),
                Instant.parse(Mcp.field(without, "lease_expires_at"))).abs())
            .isLessThan(Duration.ofMinutes(1));
    }

    @Test
    void a_draft_created_with_a_null_text_and_parent_is_the_draft_created_without() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("title", "a draft");
        fields.put("apparatus", "code");
        fields.put("text", null);
        fields.put("metadata", null);
        Map<String, Object> nulled = collection(SurfaceFixture.SCOPE);
        nulled.put("parent", null);
        nulled.put("idempotency_key", null);
        nulled.put("fields", fields);

        Map<String, Object> withNull = Mcp.answer(Mcp.call("dispatch_create", nulled));
        Map<String, Object> without = Mcp.answer(Mcp.call("dispatch_create",
            with(collection(SurfaceFixture.SCOPE), "fields",
                Map.of("title", "a draft", "apparatus", "code"))));

        Map<String, Object> readNull = Mcp.answer(Mcp.call("dispatch_read",
            Map.of("address", withNull.get("address"))));
        Map<String, Object> readWithout = Mcp.answer(Mcp.call("dispatch_read",
            Map.of("address", without.get("address"))));
        assertThat(fieldsWithoutIdentity(readNull)).isEqualTo(fieldsWithoutIdentity(readWithout));
        assertThat(String.valueOf(withNull.get("address"))).endsWith(".0");
    }

    // -----------------------------------------------------------------------

    /**
     * A complete call aimed where nothing can be written: a task that does
     * not exist for the calls on a task, a scope nobody belongs to for the
     * calls on a bracket kind.
     */
    private static Map<String, Object> complete(String verb) {
        TargetSurface.Arguments declared = TargetSurface.ARGUMENTS.get(verb);
        Map<String, Object> call = new LinkedHashMap<>();
        for (String name : declared.top()) {
            call.put(name, valueFor(verb, name));
        }
        if (!declared.fields().isEmpty()) {
            Map<String, Object> fields = new LinkedHashMap<>();
            for (String name : declared.fields()) {
                fields.put(name, valueFor(verb, "fields." + name));
            }
            call.put("fields", fields);
        }
        return call;
    }

    private static Object valueFor(String verb, String name) {
        return switch (name) {
            case "scope" -> NO_SCOPE;
            case "selector" -> SurfaceFixture.SELECTOR;
            case "address" -> NOWHERE;
            case "parent" -> NOWHERE;
            case "conflict_token", "receipt", "confirmation", "idempotency_key", "after" -> "x";
            case "duration" -> "PT1H";
            case "apparatus" -> "claim_next".equals(verb) ? List.of("code") : "code";
            case "part" -> "dispatch";
            case "page_size" -> 10;
            case "sort" -> "id";
            case "direction" -> "asc";
            case "state" -> "open";
            case "bracket" -> "1";
            case "fields.not_before" -> "2030-01-01T00:00:00Z";
            case "fields.options" -> List.of("yes", "no");
            case "fields.free_text" -> false;
            case "fields.reason" -> "external";
            case "fields.metadata" -> Map.of("key", "value");
            case "fields.part" -> "dispatch";
            case "fields.curated_in" -> NOWHERE;
            case "fields.apparatus" -> "code";
            default -> "a value";
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fieldsOf(Map<String, Object> call) {
        Map<String, Object> fields = new LinkedHashMap<>((Map<String, Object>) call.get("fields"));
        call.put("fields", fields);
        return fields;
    }

    /** The REST calls whose arguments travel in a body: every POST and PATCH. */
    private static boolean carriesABody(String verb) {
        return !List.of("read", "read_text", "query", "delete").contains(verb);
    }

    /** The arguments REST takes from the path, which carries no null. */
    private static boolean pathArgument(String verb, String name) {
        return List.of("scope", "selector", "address").contains(name)
            && !("query".equals(verb) && "address".equals(name));
    }

    private static Response rest(String verb, Map<String, Object> call) {
        Map<String, Object> body = new LinkedHashMap<>(call);
        String path;
        if (body.containsKey("address") && !List.of("create", "claim_next", "query")
                .contains(verb)) {
            String address = String.valueOf(body.remove("address"));
            path = "/api/" + address.substring("dispatch://".length());
        } else {
            body.remove("scope");
            body.remove("selector");
            path = "/api/" + NO_SCOPE + "/" + SurfaceFixture.SELECTOR;
        }
        if (!"create".equals(verb) && !"update".equals(verb)) {
            path = path + ":" + verb;
        }
        var request = given().contentType(ContentType.JSON).body(body);
        return "update".equals(verb) ? request.patch(path) : request.post(path);
    }

    private static Map<String, Object> collection(String scope) {
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("scope", scope);
        call.put("selector", SurfaceFixture.SELECTOR);
        return call;
    }

    private static Map<String, Object> with(Map<String, Object> call, String name, Object value) {
        Map<String, Object> more = new LinkedHashMap<>(call);
        more.put(name, value);
        return more;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fieldsWithoutIdentity(Map<String, Object> answer) {
        Map<String, Object> fields = new LinkedHashMap<>((Map<String, Object>) answer.get("fields"));
        fields.remove("identity");
        return fields;
    }
}
