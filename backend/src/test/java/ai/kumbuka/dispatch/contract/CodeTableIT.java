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
 * a holder's call by a caller who holds nothing {@code CLAIM_HELD} or {@code
 * CLAIM_ABSENT}, each 409, and a lease that is no positive duration {@code
 * ARGUMENT_INVALID} 400. The rows the contract change of 2026-10-16 replaced
 * are each raised with no member outside their row: {@code CLAIM_ABSENT} and
 * {@code TRANSITION_REFUSED} 409, {@code ROLE_INSUFFICIENT} 403 decided after
 * the address is resolved, {@code SCOPE_READ_ONLY} 403 and {@code
 * HEADER_INVALID} 400 with no {@code data}. This service's own codes on a
 * bracket, a question and a deferral are raised the same way: {@code
 * CHILDREN_UNFINISHED} with the complete addresses of the children and the
 * confirmation handed out now, {@code OPTION_UNOFFERED} with {@code
 * fields.option}, and {@code DEFERRAL_PENDING} with the task's situation, each
 * 409. The statuses and members are
 * written here from the table, not read from the catalogue.
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
        restRow(given().get(SurfaceFixture.item(id) + ":read_text?part=dispatch"),
            "CLAIM_ABSENT", 409, CLAIM_ABSENT_ROW, "attempted", "state", "next");
        mcpRow(Mcp.call("dispatch_read_text", Map.of("address", address, "part", "dispatch")),
            "CLAIM_ABSENT", CLAIM_ABSENT_ROW, "attempted", "state", "next");

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
        restRow(given().contentType(ContentType.JSON).body(Map.of("receipt", receipt))
                .post(SurfaceFixture.item(id) + ":renew"),
            "CLAIM_ABSENT", 409, CLAIM_ABSENT_ROW, "attempted", "state", "next");
        mcpRow(Mcp.call("dispatch_renew", Map.of("address", address, "receipt", "any")),
            "CLAIM_ABSENT", CLAIM_ABSENT_ROW, "attempted", "state", "next");
    }

    @Test
    void a_transition_the_state_refuses_is_transition_refused_409() {
        String id = SurfaceFixture.open("sent once", "code");
        String address = SurfaceFixture.address(id);
        String token = given().get(SurfaceFixture.item(id)).jsonPath()
            .getString("conflict_token");
        restRow(given().header("If-Match", "\"" + token + "\"")
                .post(SurfaceFixture.item(id) + ":send"),
            "TRANSITION_REFUSED", 409, TRANSITION_REFUSED_ROW, "attempted", "state", "next");
        mcpRow(Mcp.call("dispatch_send", Map.of("address", address, "conflict_token", token)),
            "TRANSITION_REFUSED", TRANSITION_REFUSED_ROW, "attempted", "state", "next");
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
    void the_call_of_another_part_and_a_scope_read_only_are_403() {
        String id = SurfaceFixture.open("parted", "code");
        SurfaceFixture.asExecutor(identity);
        restRow(given().contentType(ContentType.JSON).header("If-Match", "\"t\"")
                .post(SurfaceFixture.item(id) + ":withdraw"),
            "ROLE_INSUFFICIENT", 403, ROLE_INSUFFICIENT_ROW, "attempted", "next");
        mcpRow(Mcp.call("dispatch_withdraw", Map.of("address", SurfaceFixture.address(id),
                "conflict_token", "t")),
            "ROLE_INSUFFICIENT", ROLE_INSUFFICIENT_ROW, "attempted", "next");

        // Decided after the address is resolved: no object, no part to name.
        rest(given().contentType(ContentType.JSON).header("If-Match", "\"t\"")
            .post(SurfaceFixture.item("999999.0") + ":withdraw"), "NOT_FOUND", 404);
        mcp(Mcp.call("dispatch_withdraw", Map.of("address", SurfaceFixture.address("999999.0"),
            "conflict_token", "t")), "NOT_FOUND");

        SurfaceFixture.asMutedConsole(identity);
        Map<String, Object> draft = Map.of("title", "t", "apparatus", "code");
        restRow(given().contentType(ContentType.JSON).body(Map.of("fields", draft))
                .post(SurfaceFixture.collection()), "SCOPE_READ_ONLY", 403, List.of());
        mcpRow(Mcp.call("dispatch_create", Map.of("scope", SurfaceFixture.SCOPE,
                "selector", SurfaceFixture.SELECTOR, "fields", draft)),
            "SCOPE_READ_ONLY", List.of());
    }

    @Test
    void a_surface_header_it_cannot_take_is_header_invalid_400() {
        restRow(given().header("Kumbuka-Surface", "mcp").get(SurfaceFixture.collection()),
            "HEADER_INVALID", 400, List.of());
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

    @Test
    void a_root_with_unfinished_children_is_children_unfinished_409() {
        String root = SurfaceFixture.address(SurfaceFixture.open("a root", "code"));
        String child = String.valueOf(Mcp.answer(Mcp.call("dispatch_create", Map.of(
            "scope", SurfaceFixture.SCOPE, "selector", SurfaceFixture.SELECTOR, "parent", root,
            "fields", Map.of("title", "a child", "apparatus", "code")))).get("address"));

        Response overMcp = Mcp.call("dispatch_withdraw", Map.of("address", root,
            "conflict_token", token(root)));
        mcpRow(overMcp, "CHILDREN_UNFINISHED", CHILDREN_UNFINISHED_ROW,
            "attempted", "children", "confirmation");
        Map<String, Object> data = data(Mcp.refusal(overMcp));
        assertThat(data.get("children")).as("complete addresses, each of them")
            .isEqualTo(List.of(child));
        String handedOut = String.valueOf(data.get("confirmation"));

        Response stale = Mcp.call("dispatch_withdraw", Map.of("address", root,
            "conflict_token", token(root), "confirmation", "zz-sent-confirmation"));
        mcpRow(stale, "CHILDREN_UNFINISHED", CHILDREN_UNFINISHED_ROW,
            "attempted", "children", "confirmation");
        assertThat(data(Mcp.refusal(stale)).get("confirmation"))
            .as("the confirmation is the one handed out now, never the one sent")
            .isEqualTo(handedOut).isNotEqualTo("zz-sent-confirmation");

        restRow(given().header("If-Match", "\"" + token(root) + "\"")
                .post(SurfaceFixture.item(root.substring(root.lastIndexOf('/') + 1))
                    + ":withdraw"),
            "CHILDREN_UNFINISHED", 409, CHILDREN_UNFINISHED_ROW,
            "attempted", "children", "confirmation");
    }

    @Test
    void an_answer_the_question_does_not_offer_is_option_unoffered_409() {
        String id = SurfaceFixture.open("asked", "code");
        String address = SurfaceFixture.address(id);
        SurfaceFixture.asExecutor(identity);
        String receipt = String.valueOf(Mcp.answer(Mcp.call("dispatch_claim",
            Map.of("address", address))).get("receipt"));
        Mcp.answer(Mcp.call("dispatch_ask", Map.of("address", address, "receipt", receipt,
            "fields", Map.of("question", "which?", "options", List.of("left", "right")))));
        SurfaceFixture.asConsole(identity);

        Response overMcp = Mcp.call("dispatch_answer", Map.of("address", address,
            "conflict_token", token(address), "fields", Map.of("option", "middle")));
        mcpRow(overMcp, "OPTION_UNOFFERED", OPTION_UNOFFERED_ROW, "attempted", "offenders");
        assertThat(data(Mcp.refusal(overMcp)).get("offenders"))
            .isEqualTo(List.of("fields.option"));
        Response overRest = given().contentType(ContentType.JSON)
            .header("If-Match", "\"" + token(address) + "\"")
            .body(Map.of("fields", Map.of("option", "middle")))
            .post(SurfaceFixture.item(id) + ":answer");
        restRow(overRest, "OPTION_UNOFFERED", 409, OPTION_UNOFFERED_ROW,
            "attempted", "offenders");
    }

    @Test
    void a_claim_before_the_deferral_passed_is_deferral_pending_409() {
        String id = SurfaceFixture.open("deferred", "code");
        String address = SurfaceFixture.address(id);
        SurfaceFixture.asExecutor(identity);
        String receipt = String.valueOf(Mcp.answer(Mcp.call("dispatch_claim",
            Map.of("address", address))).get("receipt"));
        Mcp.answer(Mcp.call("dispatch_defer", Map.of("address", address, "receipt", receipt,
            "fields", Map.of("not_before", java.time.Instant.now().plusSeconds(7200)
                .truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()))));

        SurfaceFixture.asOtherExecutor(identity);
        mcpRow(Mcp.call("dispatch_claim", Map.of("address", address)), "DEFERRAL_PENDING",
            DEFERRAL_PENDING_ROW, "attempted", "state");
        restRow(given().contentType(ContentType.JSON).body(Map.of("duration", "PT1H"))
                .post(SurfaceFixture.item(id) + ":claim"),
            "DEFERRAL_PENDING", 409, DEFERRAL_PENDING_ROW, "attempted", "state");
    }

    // -----------------------------------------------------------------------

    /** The members of data the rows the contract change replaced name, as the table has them. */
    private static final List<String> CLAIM_ABSENT_ROW = List.of("attempted", "state", "next");
    private static final List<String> TRANSITION_REFUSED_ROW =
        List.of("attempted", "state", "next", "waiting_for");
    private static final List<String> ROLE_INSUFFICIENT_ROW = List.of("attempted", "next");
    private static final List<String> CHILDREN_UNFINISHED_ROW =
        List.of("attempted", "children", "confirmation");
    private static final List<String> OPTION_UNOFFERED_ROW = List.of("attempted", "offenders");
    private static final List<String> DEFERRAL_PENDING_ROW =
        List.of("attempted", "state", "next", "waiting_for");

    /** The task's current conflict token, as its read hands it out. */
    private static String token(String address) {
        return String.valueOf(Mcp.answer(Mcp.call("dispatch_read", Map.of("address", address)))
            .get("conflict_token"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> refusal) {
        return (Map<String, Object>) refusal.get("data");
    }

    /**
     * A refusal over REST under a row that names its members: its code, its
     * status, no member outside the row, and the members given as required.
     * An empty row is a refusal with no data.
     */
    private static void restRow(Response answer, String code, int status, List<String> row,
                                String... required) {
        rest(answer, code, status, required);
        row(answer.jsonPath().getMap("$"), code + " on REST", row);
    }

    /** The same over MCP. */
    private static void mcpRow(Response answer, String code, List<String> row,
                               String... required) {
        mcp(answer, code, required);
        row(Mcp.refusal(answer), code + " on MCP", row);
    }

    @SuppressWarnings("unchecked")
    private static void row(Map<String, Object> refusal, String what, List<String> row) {
        if (row.isEmpty()) {
            assertThat(refusal).as("%s carries no data", what).doesNotContainKey("data");
            return;
        }
        assertThat(((Map<String, Object>) refusal.get("data")).keySet())
            .as("%s carries no member outside its row", what).isSubsetOf(row);
    }

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
