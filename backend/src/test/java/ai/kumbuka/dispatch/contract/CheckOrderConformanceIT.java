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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The check order: grammar, then the scope's visibility, then the vocabulary,
 * then the object (C1.3, DEC-0042). A scope the caller cannot see answers
 * {@code NOT_FOUND} whatever else is wrong with the call, grammar excepted.
 *
 * <p>Each case is a call on a scope of another tenant that carries one further
 * defect, of every kind that is decided after the scope: a bracket kind the
 * scope does not declare, a stale conflict token, a missing one, the call of
 * another part, a receipt that is not the holder's, a cursor the service did
 * not hand out. Each answers {@code NOT_FOUND}, on both surfaces: answering the
 * defect instead would tell the caller the scope is there. The grammar case
 * stands beside them: an address out of the grammar is refused as such, which
 * says nothing about any scope.
 *
 * <p>RED STATE, observed 2026-10-16 against the tree of the guards' commit, with
 * this class copied in: a call without its conflict token on an invisible scope
 * was refused for the missing token before the scope was looked at, and an
 * address out of the grammar was refused as an invalid argument.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class CheckOrderConformanceIT {

    private static final String INVISIBLE = NotFoundIdentityIT.INVISIBLE_SCOPE;
    private static final String ADDRESS = "dispatch://" + INVISIBLE + "/"
        + SurfaceFixture.SELECTOR + "/1.0";

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        NotFoundIdentityIT.stageInvisibleScope();
        SurfaceFixture.asConsole(identity);
    }

    @Test
    void an_invisible_scope_answers_not_found_whatever_else_the_call_gets_wrong() {
        Map<String, Map<String, Object>> asConsole = new LinkedHashMap<>();
        asConsole.put("dispatch_create", Map.of("scope", INVISIBLE, "selector", "undeclared-kind",
            "fields", Map.of("title", "t", "apparatus", "code")));
        asConsole.put("dispatch_query", Map.of("scope", INVISIBLE,
            "selector", SurfaceFixture.SELECTOR, "after", "no-cursor"));
        asConsole.put("dispatch_withdraw", Map.of("address", ADDRESS, "conflict_token", "stale"));
        asConsole.put("dispatch_send", Map.of("address", ADDRESS));
        asConsole.put("dispatch_reject", Map.of("address", ADDRESS,
            "fields", Map.of("remark", "the executor's call")));
        assertNotFound(asConsole);

        SurfaceFixture.asExecutor(identity);
        Map<String, Map<String, Object>> asExecutor = new LinkedHashMap<>();
        asExecutor.put("dispatch_renew", Map.of("address", ADDRESS, "receipt", "not-the-holder"));
        asExecutor.put("dispatch_accept", Map.of("address", ADDRESS, "conflict_token", "t"));
        asExecutor.put("dispatch_claim_next", Map.of("scope", INVISIBLE,
            "selector", "undeclared-kind", "apparatus", List.of("code")));
        assertNotFound(asExecutor);
    }

    @Test
    void grammar_is_decided_before_the_scope_and_says_nothing_about_it() {
        Response overMcp = Mcp.call("dispatch_read",
            Map.of("address", "dispatch://" + INVISIBLE + "/" + SurfaceFixture.SELECTOR + "/1"));
        assertThat(Mcp.reason(overMcp)).isEqualTo("ADDRESS_MALFORMED");

        Response visible = Mcp.call("dispatch_read", Map.of("address",
            "dispatch://" + SurfaceFixture.SCOPE + "/" + SurfaceFixture.SELECTOR + "/1"));
        assertThat(Mcp.refusal(overMcp))
            .as("the same refusal whether the scope is visible or not")
            .isEqualTo(Mcp.refusal(visible));
    }

    /** Each call on both surfaces: NOT_FOUND, and on REST 404. */
    private static void assertNotFound(Map<String, Map<String, Object>> calls) {
        calls.forEach((tool, arguments) -> {
            assertThat(Mcp.reason(Mcp.call(tool, arguments))).as("%s over MCP", tool)
                .isEqualTo("NOT_FOUND");
            Response overRest = overRest(tool, arguments);
            assertThat(List.of(overRest.statusCode(), overRest.jsonPath().getString("reason")))
                .as("%s over REST: %s", tool, overRest.asString())
                .isEqualTo(List.of(404, "NOT_FOUND"));
        });
    }

    /** The same call on the REST surface: the route, the token as If-Match, the rest as body. */
    @SuppressWarnings("unchecked")
    private static Response overRest(String tool, Map<String, Object> arguments) {
        String verb = tool.substring("dispatch_".length());
        Map<String, Object> body = new LinkedHashMap<>(arguments);
        Object address = body.remove("address");
        Object scope = body.remove("scope");
        Object selector = body.remove("selector");
        Object token = body.remove("conflict_token");
        var request = given().contentType(ContentType.JSON);
        if (token != null) {
            request = request.header("If-Match", "\"" + token + "\"");
        }
        if (verb.equals("query")) {
            return request.queryParams(body).get("/api/" + scope + "/" + selector);
        }
        if (!body.isEmpty()) {
            request = request.body(body);
        }
        if (address == null) {
            String collection = "/api/" + scope + "/" + selector;
            return request.post(verb.equals("create") ? collection : collection + ":" + verb);
        }
        return request.post(NotFoundIdentityIT.path(String.valueOf(address)) + ":" + verb);
    }
}
