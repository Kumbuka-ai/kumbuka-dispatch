/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.contract;

import ai.kumbuka.dispatch.platform.PlatformFixture;
import ai.kumbuka.dispatch.surface.Mcp;
import ai.kumbuka.dispatch.surface.SurfaceFixture;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code NOT_FOUND} is one answer, byte for byte, whatever its cause (C8.4,
 * DEC-0042): a task that is not there, a scope the caller may not see, and an
 * address no scope answers to.
 *
 * <p>Three addresses, each well formed: a number nothing was created under in
 * the caller's own scope; a scope that exists in another tenant, which the
 * caller's directory never shows; and a scope that exists nowhere. On each
 * surface the three answers are compared with each other, byte for byte, and
 * the envelope carries no {@code data}: a difference of one byte would tell a
 * caller which of the three it hit.
 *
 * <p>The rule on the envelope is {@link #notFoundOffences}: {@code data} is
 * absent, not merely free of the caller's values. An empty {@code data}, or
 * one that carries only {@code attempted}, tells nothing of the cause and
 * still breaks the envelope C8.4 fixes, and it is how a service answers that
 * checks only for values.
 *
 * <p>RED STATE, observed 2026-10-16 with a violation planted for the run: an
 * unresolved scope answered {@code NOT_FOUND} with a {@code data} member, and
 * all three cases turned red. The rule's own red state runs on every build in
 * {@link #the_check_reports_a_not_found_that_carries_data}.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class NotFoundIdentityIT {

    /** A scope of another tenant: it exists, and this caller's directory does not show it. */
    static final String INVISIBLE_SCOPE = "probe-scope-elsewhere";
    static final String OTHER_TENANT = "00000000-0000-0000-0000-0000000000ee";
    static final String INVISIBLE_SCOPE_ID = "00000000-0000-0000-0000-0000000000e1";

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        stageInvisibleScope();
        SurfaceFixture.asConsole(identity);
    }

    /** The scope of another tenant, staged once; the row-level policy hides it from this one. */
    static void stageInvisibleScope() {
        PlatformFixture.run(
            "SELECT set_config('app.tenant_id', '" + OTHER_TENANT + "', false)",
            "INSERT INTO public.scope (id, tenant_id, slug, kind, locked) SELECT '"
                + INVISIBLE_SCOPE_ID + "', '" + OTHER_TENANT + "', '" + INVISIBLE_SCOPE
                + "', 'project', false WHERE NOT EXISTS (SELECT 1 FROM public.scope WHERE id = '"
                + INVISIBLE_SCOPE_ID + "')",
            "RESET app.tenant_id");
    }

    /** The three causes, as complete addresses of one bracket kind. */
    static List<String> causes() {
        return List.of(
            "dispatch://" + SurfaceFixture.SCOPE + "/" + SurfaceFixture.SELECTOR + "/99999.0",
            "dispatch://" + INVISIBLE_SCOPE + "/" + SurfaceFixture.SELECTOR + "/1.0",
            "dispatch://probe-scope-nowhere/" + SurfaceFixture.SELECTOR + "/1.0");
    }

    @Test
    void over_rest_absent_invisible_and_unroutable_answer_the_same_bytes() {
        List<Response> answers = causes().stream()
            .map(address -> given().get(path(address)))
            .toList();

        for (Response answer : answers) {
            assertThat(answer.statusCode()).isEqualTo(404);
            assertThat(notFoundOffences(answer.jsonPath().getMap("$")))
                .as("NOT_FOUND carries no data (C8.4)").isEmpty();
        }
        assertThat(answers.stream().map(Response::asString).distinct().toList())
            .as("one answer, byte for byte, for all three causes")
            .hasSize(1);
    }

    /**
     * A route no resource carries is the framework's 404, and it answers the
     * same bytes as an absent and an invisible task (C8.3, C8.4).
     *
     * <p>RED STATE, observed against the catch-all that handed the framework's
     * 404 back: its body was not the envelope.
     */
    @Test
    void the_framework_s_not_found_answers_the_same_bytes_as_the_three_causes() {
        Response framework = given().get("/api/nothing");
        Response absent = given().get(path(causes().get(0)));
        assertThat(framework.statusCode()).isEqualTo(404);
        assertThat(framework.asString()).as("one answer, byte for byte")
            .isEqualTo(absent.asString());
    }

    @Test
    void over_mcp_absent_invisible_and_unroutable_answer_the_same_bytes() {
        List<Response> answers = causes().stream()
            .map(address -> Mcp.call("dispatch_read", Map.of("address", address)))
            .toList();

        for (Response answer : answers) {
            assertThat(notFoundOffences(Mcp.refusal(answer)))
                .as("NOT_FOUND carries no data (C8.4)").isEmpty();
        }
        assertThat(answers.stream().map(Response::asString).distinct().toList())
            .as("one answer, byte for byte, for all three causes")
            .hasSize(1);
    }

    @Test
    void a_write_to_any_of_the_three_answers_the_same_bytes_as_a_read() {
        List<Response> writes = causes().stream()
            .map(address -> Mcp.call("dispatch_withdraw", Map.of("address", address,
                "conflict_token", "t")))
            .toList();
        for (Response write : writes) {
            assertThat(notFoundOffences(Mcp.refusal(write)))
                .as("a write's NOT_FOUND carries no data either (C8.4)").isEmpty();
        }
        assertThat(writes.stream()
                .map(write -> write.jsonPath().getString("result.structuredContent"))
                .distinct().toList())
            .hasSize(1);
    }

    /** The red state of the rule, observed on every build. */
    @Test
    void the_check_reports_a_not_found_that_carries_data() {
        Map<String, Object> planted = new LinkedHashMap<>();
        planted.put("reason", "NOT_FOUND");
        planted.put("message", "Nothing is here.");
        planted.put("data", Map.of("attempted", "dispatch_read"));
        assertThat(notFoundOffences(planted))
            .as("RED STATE, observed: a NOT_FOUND with data.attempted, which carries no value "
                + "of the caller, must be reported for the member itself")
            .containsExactly("NOT_FOUND carries data");
        assertThat(notFoundOffences(Map.of("reason", "NOT_FOUND", "message", "m",
                "data", Map.of())))
            .as("and so must an empty data").containsExactly("NOT_FOUND carries data");
    }

    /**
     * What is wrong with an envelope that should be {@code NOT_FOUND}: another
     * reason, a {@code data} member of any content, or a member beside the two.
     */
    static List<String> notFoundOffences(Map<String, Object> envelope) {
        List<String> offences = new ArrayList<>();
        if (!"NOT_FOUND".equals(envelope.get("reason"))) {
            offences.add("the reason is " + envelope.get("reason"));
        }
        if (envelope.containsKey("data")) {
            offences.add("NOT_FOUND carries data");
        }
        envelope.keySet().stream()
            .filter(key -> !List.of("reason", "message", "data").contains(key))
            .forEach(key -> offences.add("NOT_FOUND carries " + key));
        return offences;
    }

    /** The REST path of a complete address. */
    static String path(String address) {
        return "/api/" + address.substring("dispatch://".length());
    }
}
