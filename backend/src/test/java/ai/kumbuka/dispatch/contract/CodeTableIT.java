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
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every code and status of the shared table this service answers, raised on
 * the wire, on REST with its status and on MCP, each with the members of
 * {@code data} the table names (C8.7).
 *
 * <p>The rows the move to the table changed are each raised here: a missing
 * conflict token is {@code CONFLICT_TOKEN_MISSING} 428, an undeclared bracket
 * kind {@code SELECTOR_UNDECLARED} 422, an empty draw {@code DRAW_EMPTY} 409,
 * a verb not carried at an address {@code VERB_NOT_CARRIED} 405 with {@code
 * Allow} and {@code data.accepts}, a wrong receipt {@code RECEIPT_WRONG} 409,
 * a holder's call by a caller who holds nothing {@code CLAIM_HELD}, the state's
 * refusal or {@code LEASE_LAPSED}, each 409, and a lease that is no positive
 * duration {@code ARGUMENT_INVALID} 400. The codes of the service's own beside
 * the table keep their statuses: the call of another part and a scope read
 * only are 403. The statuses and members are written here from the table, not
 * read from the catalogue.
 *
 * <p>On MCP an unknown tool is no refused call but a fault of the protocol
 * (C4.3): the row of {@code VERB_NOT_CARRIED} is the REST surface's, and its
 * MCP counterpart is held in {@code McpProtocolIT}.
 *
 * <p>RED STATE, observed 2026-10-16 against the tree of the guards' commit, with
 * this class copied in: nine of its eleven cases, each on the code or the
 * status the table changed.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class CodeTableIT {

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asConsole(identity);
    }

    // =======================================================================
    // The rows of the table the move changed
    // =======================================================================

    @Test
    void a_call_without_its_conflict_token_is_conflict_token_missing_428() {
        String id = SurfaceFixture.idOf(SurfaceFixture.create(Map.of("title", "untokened",
            "apparatus", "code")));
        rest(given().contentType(ContentType.JSON).post(SurfaceFixture.item(id) + ":send"),
            "CONFLICT_TOKEN_MISSING", 428, "attempted");
        mcp(Mcp.call("dispatch_send", Map.of("address", SurfaceFixture.address(id))),
            "CONFLICT_TOKEN_MISSING", "attempted");
    }

    @Test
    void an_undeclared_bracket_kind_is_selector_undeclared_422() {
        rest(given().get("/api/" + SurfaceFixture.SCOPE + "/undeclared-kind"),
            "SELECTOR_UNDECLARED", 422, "attempted");
        mcp(Mcp.call("dispatch_query", Map.of("scope", SurfaceFixture.SCOPE,
            "selector", "undeclared-kind")), "SELECTOR_UNDECLARED", "attempted");
    }

    @Test
    void a_withdrawn_bracket_kind_is_selector_withdrawn_422() {
        rest(given().contentType(ContentType.JSON)
                .body(Map.of("fields", Map.of("title", "t", "apparatus", "code")))
                .post("/api/" + SurfaceFixture.SCOPE + "/" + SurfaceFixture.WITHDRAWN_SELECTOR),
            "SELECTOR_WITHDRAWN", 422, "attempted");
        mcp(Mcp.call("dispatch_create", Map.of("scope", SurfaceFixture.SCOPE,
                "selector", SurfaceFixture.WITHDRAWN_SELECTOR,
                "fields", Map.of("title", "t", "apparatus", "code"))),
            "SELECTOR_WITHDRAWN", "attempted");
    }

    @Test
    void a_draw_that_finds_nothing_is_draw_empty_409() {
        SurfaceFixture.asExecutor(identity);
        rest(given().contentType(ContentType.JSON)
                .body(Map.of("apparatus", List.of("nothing-draws-this")))
                .post(SurfaceFixture.collection() + ":claim_next"),
            "DRAW_EMPTY", 409, "attempted", "next");
        mcp(Mcp.call("dispatch_claim_next", Map.of("scope", SurfaceFixture.SCOPE,
                "selector", SurfaceFixture.SELECTOR, "apparatus", List.of("nothing-draws-this"))),
            "DRAW_EMPTY", "attempted", "next");
    }

    @Test
    void a_verb_not_carried_at_an_address_is_verb_not_carried_405_with_allow() {
        String id = SurfaceFixture.open("routed", "code");
        Response unknown = rest(given().contentType(ContentType.JSON)
            .post(SurfaceFixture.item(id) + ":takeup"), "VERB_NOT_CARRIED", 405, "accepts");
        assertThat(unknown.header("Allow")).isEqualTo("GET, PATCH, DELETE, POST");
        Response elsewhere = rest(given().contentType(ContentType.JSON)
            .post(SurfaceFixture.collection() + ":send"), "VERB_NOT_CARRIED", 405,
            "attempted", "accepts");
        assertThat(elsewhere.header("Allow")).isEqualTo("POST, GET");
        assertThat(elsewhere.jsonPath().getList("data.accepts", String.class))
            .containsExactly("create", "query", "claim_next");
    }

    @Test
    void a_receipt_that_is_not_the_holder_s_is_receipt_wrong_409() {
        String id = SurfaceFixture.open("receipted", "code");
        String address = SurfaceFixture.address(id);
        SurfaceFixture.asExecutor(identity);
        Mcp.answer(Mcp.call("dispatch_claim", Map.of("address", address)));
        rest(given().contentType(ContentType.JSON).body(Map.of("receipt", "not-the-holder-s"))
                .post(SurfaceFixture.item(id) + ":renew"), "RECEIPT_WRONG", 409, "attempted");
        mcp(Mcp.call("dispatch_renew", Map.of("address", address, "receipt", "not-the-holder-s")),
            "RECEIPT_WRONG", "attempted");
    }

    @Test
    void a_holder_s_call_by_a_caller_who_holds_nothing_is_told_by_who_holds_the_task() {
        String id = SurfaceFixture.open("held", "code");
        String address = SurfaceFixture.address(id);

        SurfaceFixture.asExecutor(identity);
        rest(given().get(SurfaceFixture.item(id) + ":read_text?part=dispatch"),
            "STATE_DOES_NOT_ALLOW", 409, "attempted", "state", "next");
        mcp(Mcp.call("dispatch_read_text", Map.of("address", address, "part", "dispatch")),
            "STATE_DOES_NOT_ALLOW", "attempted", "state", "next");

        String receipt = String.valueOf(Mcp.answer(Mcp.call("dispatch_claim",
            Map.of("address", address))).get("receipt"));
        SurfaceFixture.asOtherExecutor(identity);
        rest(given().contentType(ContentType.JSON).body(Map.of("receipt", receipt,
                "fields", Map.of("text", "mine"))).post(SurfaceFixture.item(id) + ":deliver"),
            "CLAIM_HELD", 409, "attempted", "state", "next");
        mcp(Mcp.call("dispatch_deliver", Map.of("address", address, "receipt", receipt,
            "fields", Map.of("text", "mine"))), "CLAIM_HELD", "attempted", "state", "next");

        SurfaceFixture.asExecutor(identity);
        lapse(address);
        rest(given().contentType(ContentType.JSON).body(Map.of("receipt", receipt))
                .post(SurfaceFixture.item(id) + ":renew"),
            "LEASE_LAPSED", 409, "attempted", "state", "next");
        mcp(Mcp.call("dispatch_renew", Map.of("address", address, "receipt", receipt)),
            "LEASE_LAPSED", "attempted", "state", "next");
    }

    @Test
    void a_lease_that_is_no_positive_duration_is_argument_invalid_400() {
        String id = SurfaceFixture.open("leased", "code");
        SurfaceFixture.asExecutor(identity);
        for (String duration : List.of("soon", "PT0S", "-PT1H")) {
            Response refused = rest(given().contentType(ContentType.JSON)
                    .body(Map.of("duration", duration)).post(SurfaceFixture.item(id) + ":claim"),
                "ARGUMENT_INVALID", 400, "attempted", "offenders");
            assertThat(refused.jsonPath().getList("data.offenders", String.class))
                .containsExactly("duration");
            mcp(Mcp.call("dispatch_claim", Map.of("address", SurfaceFixture.address(id),
                "duration", duration)), "ARGUMENT_INVALID", "attempted", "offenders");
        }
    }

    @Test
    void the_call_of_another_part_and_a_scope_read_only_stay_403() {
        String id = SurfaceFixture.open("parted", "code");
        SurfaceFixture.asExecutor(identity);
        rest(given().contentType(ContentType.JSON).header("If-Match", "\"t\"")
                .post(SurfaceFixture.item(id) + ":withdraw"),
            "ROLE_DOES_NOT_ALLOW", 403, "attempted");
        mcp(Mcp.call("dispatch_withdraw", Map.of("address", SurfaceFixture.address(id),
            "conflict_token", "t")), "ROLE_DOES_NOT_ALLOW", "attempted");

        SurfaceFixture.asMutedConsole(identity);
        rest(given().contentType(ContentType.JSON)
                .body(Map.of("fields", Map.of("title", "t", "apparatus", "code")))
                .post(SurfaceFixture.collection()), "SCOPE_READ_ONLY", 403, "attempted");
    }

    // =======================================================================
    // The other rows of the table this service carries
    // =======================================================================

    @Test
    void the_other_rows_answer_their_code_status_and_members() {
        rest(given().contentType(ContentType.JSON).body("[1]").post(SurfaceFixture.collection()),
            "PAYLOAD_MALFORMED", 400);
        rest(given().get("/api/" + SurfaceFixture.SCOPE + "/sprint/1"), "ADDRESS_MALFORMED", 400);
        mcp(Mcp.call("dispatch_read", Map.of("address", "sprint/1.0")), "ADDRESS_MALFORMED",
            "offenders");
        rest(given().queryParam("zz_unknown", "x").get(SurfaceFixture.collection()),
            "ARGUMENT_UNKNOWN", 400, "accepts");
        rest(given().contentType(ContentType.JSON).body(Map.of()).post(SurfaceFixture.collection()),
            "ARGUMENT_MISSING", 400, "offenders");
        rest(given().queryParam("page_size", 0).get(SurfaceFixture.collection()),
            "PAGE_SIZE_REJECTED", 400, "offenders");
        rest(given().queryParam("after", "no-cursor").get(SurfaceFixture.collection()),
            "CURSOR_MALFORMED", 400, "offenders");
        rest(given().queryParam("sort", "title").get(SurfaceFixture.collection()),
            "SORT_UNDECLARED", 400, "offenders");
        rest(given().get(NotFoundIdentityIT.path(
            "dispatch://" + SurfaceFixture.SCOPE + "/sprint/99999.0")), "NOT_FOUND", 404);

        String id = SurfaceFixture.open("stale", "code");
        Response stale = rest(given().contentType(ContentType.JSON).header("If-Match", "\"stale\"")
                .post(SurfaceFixture.item(id) + ":withdraw"),
            "CONFLICT_TOKEN_STALE", 412, "address", "conflict_token", "fields", "service");
        assertThat(stale.jsonPath().getString("data.service")).isEqualTo("dispatch");
        assertThat(stale.jsonPath().getString("data.address"))
            .isEqualTo(SurfaceFixture.address(id));
        assertThat(stale.jsonPath().getMap("data.fields"))
            .as("the fields the caller's read answers (DEC-0041)")
            .containsKeys("state", "title", "holder");

        String first = SurfaceFixture.address(SurfaceFixture.open("one", "code")) + ","
            + SurfaceFixture.address(SurfaceFixture.open("two", "code"));
        String cursor = given().queryParam("address", first).queryParam("page_size", 1)
            .get(SurfaceFixture.collection()).jsonPath().getString("next_cursor");
        rest(given().queryParam("address", first).queryParam("page_size", 1)
                .queryParam("sort", "created_at").queryParam("after", cursor)
                .get(SurfaceFixture.collection()),
            "CURSOR_FOREIGN", 400, "offenders");
    }

    @Test
    void not_found_carries_no_data_on_either_surface() {
        Response overRest = rest(given().get(NotFoundIdentityIT.path(
            "dispatch://" + SurfaceFixture.SCOPE + "/sprint/99999.0")), "NOT_FOUND", 404);
        assertThat(overRest.jsonPath().getMap("$")).doesNotContainKey("data");
        Map<String, Object> overMcp = Mcp.refusal(Mcp.call("dispatch_read", Map.of("address",
            "dispatch://" + SurfaceFixture.SCOPE + "/sprint/99999.0")));
        assertThat(overMcp).doesNotContainKey("data");
    }

    // -----------------------------------------------------------------------

    /** A refusal over REST: its code, its status, and the members of data the row names. */
    private static Response rest(Response answer, String code, int status, String... data) {
        assertThat(answer.jsonPath().getString("reason")).as(answer.asString()).isEqualTo(code);
        assertThat(answer.statusCode()).as("%s on REST", code).isEqualTo(status);
        if (data.length > 0) {
            assertThat(answer.jsonPath().getMap("data")).as("%s data", code).containsKeys(data);
        }
        return answer;
    }

    /** A refusal over MCP: its code and the members of data the row names. */
    @SuppressWarnings("unchecked")
    private static void mcp(Response answer, String code, String... data) {
        Map<String, Object> refusal = Mcp.refusal(answer);
        assertThat(refusal.get("reason")).as(answer.asString()).isEqualTo(code);
        if (data.length > 0) {
            assertThat((Map<String, Object>) refusal.get("data")).as("%s data", code)
                .containsKeys(data);
        }
    }

    /** Ends the lease of a task as the clock would. */
    private static void lapse(String address) {
        String identity = Mcp.field(Mcp.answer(Mcp.call("dispatch_read",
            Map.of("address", address))), "identity").substring("dispatch://".length());
        PlatformFixture.run("UPDATE dispatch.task SET lease_expires_at = now() - interval "
            + "'1 minute' WHERE uuid = '" + identity + "'");
    }
}
