/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.contract;

import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The declaration on the REST surface, held against the transcript
 * (ADR-0039): for every call its identity, effect class, arguments with
 * names, types, requiredness, defaults and closed sets of values each
 * described, the shape of its answer, the reasons it may be refused for and
 * its texts; for every reason its messages and remedy; and a version over the
 * whole.
 *
 * <p>The counter-probe imports nothing from the production packages. The
 * expectations are {@link DeclarationTranscript} and the contract copy, both
 * written by hand; the version is recomputed here, from the answer, by a
 * canonical form written here.
 *
 * <p>RED STATE, observed 2026-10-15: the effect class of {@code
 * dispatch_relate} declared as {@code GRAPH_ADD_OR_REMOVE} for the run turns
 * this red on that call alone, and so does a refusal code dropped from the
 * set of {@code dispatch_query}.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class DeclarationEndpointIT {

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void anyAuthenticatedCaller() {
        identity.setTestIdentity(QuarkusSecurityIdentity.builder()
            .setPrincipal(new QuarkusPrincipal("declaration-reader"))
            .build());
    }

    @Test
    void the_declaration_states_every_call_as_the_transcript_does() {
        Map<String, Object> declaration = read();

        assertThat(declaration.get("effect_classes"))
            .as("the effect classes are the router's closed set, by its names")
            .isEqualTo(DeclarationTranscript.EFFECT_CLASSES);

        List<Map<String, Object>> calls = maps(declaration.get("calls"));
        assertThat(calls.stream().map(c -> c.get("identity")).toList())
            .as("every call by its identity, dispatch_<verb>, in the order of the target")
            .containsExactlyElementsOf(DeclarationTranscript.CALLS.keySet());

        Map<String, String> described = Contract.describedCalls();
        List<String> wrong = new ArrayList<>();
        for (Map<String, Object> call : calls) {
            String identityOf = String.valueOf(call.get("identity"));
            DeclarationTranscript.Call expected = DeclarationTranscript.CALLS.get(identityOf);
            check(wrong, identityOf, "effect_class", call.get("effect_class"),
                expected.effectClass());
            check(wrong, identityOf, "answer.shape", map(call.get("answer")).get("shape"),
                expected.answerShape());
            assertThat(map(call.get("answer")).get("members")).as("%s answer members",
                identityOf).isInstanceOf(List.class);
            check(wrong, identityOf, "refusals", new TreeSet<>(strings(call.get("refusals"))),
                new TreeSet<>(expected.refusals()));
            check(wrong, identityOf, "description",
                Contract.collapsed(String.valueOf(call.get("description"))),
                described.get(identityOf));
            if (Boolean.TRUE.equals(call.get("transition"))) {
                assertThat(String.valueOf(call.get("next_step"))).as("%s next_step",
                    identityOf).isNotBlank();
            }
            List<String> arguments = new ArrayList<>();
            for (Map<String, Object> argument : maps(call.get("arguments"))) {
                arguments.add(argumentOf(argument));
                assertThat(String.valueOf(argument.get("description")))
                    .as("%s.%s is described", identityOf, argument.get("name")).isNotBlank();
                String setKey = identityOf.substring("dispatch_".length()) + "."
                    + argument.get("name");
                List<String> values = DeclarationTranscript.VALUES.get(setKey);
                if (values != null) {
                    check(wrong, identityOf, argument.get("name") + ".values",
                        argument.get("values"), values);
                    List<Map<String, Object>> describedValues =
                        maps(argument.get("value_descriptions"));
                    check(wrong, identityOf, argument.get("name") + ".value_descriptions",
                        describedValues.stream().map(v -> v.get("value")).toList(), values);
                    assertThat(describedValues).as("%s every value is described", setKey)
                        .allSatisfy(v -> assertThat(String.valueOf(v.get("description")))
                            .isNotBlank());
                }
            }
            check(wrong, identityOf, "arguments", arguments, expected.arguments());
        }
        assertThat(wrong).as("what the declaration states otherwise than the transcript")
            .isEmpty();
    }

    @Test
    void the_declaration_states_every_reason_with_its_messages_and_remedy() {
        List<Map<String, Object>> reasons = maps(read().get("reasons"));
        assertThat(reasons.stream().map(r -> r.get("reason")).toList())
            .containsExactlyInAnyOrderElementsOf(Contract.declaredReasons());
        Map<String, String[]> messages = Contract.messages();
        int declaredMessages = 0;
        for (Map<String, Object> reason : reasons) {
            String code = String.valueOf(reason.get("reason"));
            assertThat(reason.get("remedy")).as(code).isEqualTo(Contract.remedyOf(code));
            for (Map<String, Object> message : maps(reason.get("messages"))) {
                String key = String.valueOf(message.get("key"));
                assertThat(messages).as("%s states %s", code, key).containsKey(key);
                assertThat(messages.get(key)[0]).as("%s is raised under %s", key, code)
                    .isEqualTo(code);
                assertThat(message.get("template")).as(key).isEqualTo(messages.get(key)[1]);
                declaredMessages++;
            }
        }
        assertThat(declaredMessages).isEqualTo(messages.size());
    }

    @Test
    void the_declaration_carries_its_version() {
        Response rest = given().get("/api/declaration");
        assertThat(rest.statusCode()).isEqualTo(200);
        Map<String, Object> declaration = rest.jsonPath().getMap("$");
        String version = String.valueOf(declaration.get("version"));
        assertThat(version).matches("sha256:[0-9a-f]{64}");

        Map<String, Object> content = new LinkedHashMap<>(declaration);
        content.remove("version");
        assertThat(version)
            .as("the version is the digest of the declaration's canonical text, everything "
                + "but the version itself")
            .isEqualTo("sha256:" + sha256(canonical(content)));
        assertThat(rest.header("ETag")).as("and travels as the entity tag")
            .contains(version);

        assertThat(given().get("/mcp/declaration").statusCode())
            .as("the declaration is served at /api/declaration and nowhere else (C5.1)")
            .isEqualTo(404);
        assertThat(given().get("/api/declaration").jsonPath().getString("version"))
            .as("and a second read gives the same version").isEqualTo(version);
    }

    @Test
    void the_declaration_is_read_by_an_authenticated_caller_only() {
        identity.setTestIdentity(null);
        assertThat(given().get("/api/declaration").statusCode()).isEqualTo(401);
    }

    // -----------------------------------------------------------------------

    private static Map<String, Object> read() {
        Response answer = given().get("/api/declaration");
        assertThat(answer.statusCode()).as(answer.asString()).isEqualTo(200);
        return answer.jsonPath().getMap("$");
    }

    private static String argumentOf(Map<String, Object> argument) {
        boolean required = Boolean.TRUE.equals(argument.get("required"));
        Object fallback = argument.get("default");
        return argument.get("name") + ":" + argument.get("type") + ":" + argument.get("placement")
            + ":" + (required ? "required" : "optional" + (fallback == null ? "" : "=" + fallback));
    }

    private static void check(List<String> wrong, String call, String what, Object actual,
                              Object expected) {
        if (!String.valueOf(expected).equals(String.valueOf(actual))) {
            wrong.add(call + " " + what + ": " + actual + " where the transcript has "
                + expected);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> maps(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) {
        return (List<String>) value;
    }

    // -- the canonical form, written here and not imported -----------------

    private static String canonical(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((k, v) -> sorted.put(String.valueOf(k), v));
            List<String> members = new ArrayList<>();
            sorted.forEach((k, v) -> members.add(quoted(k) + ":" + canonical(v)));
            return "{" + String.join(",", members) + "}";
        }
        if (value instanceof List<?> list) {
            return "[" + String.join(",", list.stream().map(DeclarationEndpointIT::canonical)
                .toList()) + "]";
        }
        if (value instanceof String text) {
            return quoted(text);
        }
        return String.valueOf(value);
    }

    private static String quoted(String text) {
        StringBuilder out = new StringBuilder("\"");
        for (char c : text.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> out.append(c < 0x20 ? String.format("\\u%04x", (int) c)
                    : String.valueOf(c));
            }
        }
        return out.append('"').toString();
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
