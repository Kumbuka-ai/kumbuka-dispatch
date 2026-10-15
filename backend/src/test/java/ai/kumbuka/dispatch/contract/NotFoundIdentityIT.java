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
 * <p>RED STATE, observed 2026-10-16 with a violation planted for the run: an
 * unresolved scope answered {@code NOT_FOUND} with a {@code data} member, and
 * all three cases turned red.
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
            assertThat(answer.jsonPath().getMap("$"))
                .as("NOT_FOUND carries no data")
                .containsOnlyKeys("reason", "message")
                .containsEntry("reason", "NOT_FOUND");
        }
        assertThat(answers.stream().map(Response::asString).distinct().toList())
            .as("one answer, byte for byte, for all three causes")
            .hasSize(1);
    }

    @Test
    void over_mcp_absent_invisible_and_unroutable_answer_the_same_bytes() {
        List<Response> answers = causes().stream()
            .map(address -> Mcp.call("dispatch_read", Map.of("address", address)))
            .toList();

        for (Response answer : answers) {
            assertThat(Mcp.refusal(answer)).containsOnlyKeys("reason", "message")
                .containsEntry("reason", "NOT_FOUND");
        }
        assertThat(answers.stream().map(Response::asString).distinct().toList())
            .as("one answer, byte for byte, for all three causes")
            .hasSize(1);
    }

    @Test
    void a_write_to_any_of_the_three_answers_the_same_bytes_as_a_read() {
        List<String> answers = causes().stream()
            .map(address -> Mcp.call("dispatch_withdraw", Map.of("address", address,
                "conflict_token", "t")).jsonPath().getString("result.structuredContent"))
            .distinct()
            .toList();
        assertThat(answers).hasSize(1);
    }

    /** The REST path of a complete address. */
    static String path(String address) {
        return "/api/" + address.substring("dispatch://".length());
    }
}
