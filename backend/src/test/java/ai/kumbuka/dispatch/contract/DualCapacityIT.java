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
 * Commissioning and executing are capacities of an identity, not kinds of
 * caller (TAR-0004 section 3, REQ-0034), on both transports of the verbs.
 *
 * <p>An identity holding both runs a task from create to accept by itself,
 * and its acceptance of its own answer is marked {@code self_accepted}, which
 * {@code read} and {@code query} answer. An acceptance by another commissioner
 * is stamped {@code self_accepted: false}. Where the stamp is unknown -- a task
 * not closed as accepted, or one accepted before the stamp existed -- the field
 * is absent, as every member without a value is (service contract C7.2). An identity holding the executing capacity
 * alone never accepts, its own answer or another's, and is refused alike on
 * MCP and on REST. That last case is the four-eyes point, and the one that
 * distinguishes the requirement from a plain permission check.
 *
 * <p>RED STATE, observed against the kernel of v0.11.0 with this class copied
 * in: every call of the identity holding both was refused with {@code
 * ROLE_INSUFFICIENT} ("... and exactly one of them"), so the two courses below
 * failed at their first call.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class DualCapacityIT {

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asBoth(identity);
    }

    // =======================================================================
    // Criteria 1 and 2 -- one identity, the whole course, marked
    // =======================================================================

    @Test
    void one_identity_holding_both_runs_the_course_over_mcp_and_its_acceptance_is_marked() {
        Map<String, Object> created = Mcp.answer(Mcp.call("dispatch_create", Map.of(
            "scope", SurfaceFixture.SCOPE, "selector", SurfaceFixture.SELECTOR,
            "fields", Map.of("title", "alone, over mcp", "apparatus", "code",
                "text", "the work to do"))));
        String address = String.valueOf(created.get("address"));
        Map<String, Object> sent = Mcp.answer(Mcp.call("dispatch_send", Map.of(
            "address", address, "conflict_token", created.get("conflict_token"))));
        assertThat(Mcp.field(sent, "state")).isEqualTo("open");
        assertThat(Mcp.nextCalls(sent))
            .as("on the open task it commissioned, the identity is a candidate as well")
            .contains("dispatch_claim", "dispatch_withdraw");

        Map<String, Object> claimed = Mcp.answer(Mcp.call("dispatch_claim",
            Map.of("address", address)));
        assertThat(Mcp.nextCalls(claimed))
            .as("after the claim it is the holder: read_text first, then deliver")
            .startsWith("dispatch_read_text").contains("dispatch_deliver");
        String receipt = String.valueOf(claimed.get("receipt"));
        Mcp.answer(Mcp.call("dispatch_deliver", Map.of("address", address, "receipt", receipt,
            "fields", Map.of("text", "the answer"))));

        Map<String, Object> delivered = Mcp.answer(Mcp.call("dispatch_read",
            Map.of("address", address)));
        assertThat(Mcp.nextCalls(delivered))
            .as("on its own delivered answer, accept is open to it")
            .contains("dispatch_accept");
        Map<String, Object> accepted = Mcp.answer(Mcp.call("dispatch_accept", Map.of(
            "address", address, "conflict_token", delivered.get("conflict_token"))));
        assertThat(Mcp.field(accepted, "outcome")).isEqualTo("accepted");
        assertThat(Mcp.field(accepted, "self_accepted")).isEqualTo("true");

        assertThat(Mcp.field(Mcp.answer(Mcp.call("dispatch_read",
                Map.of("address", address))), "self_accepted"))
            .as("read answers the mark").isEqualTo("true");
        assertThat(fields(onlyTask(Mcp.answer(Mcp.call("dispatch_query", Map.of(
                "scope", SurfaceFixture.SCOPE, "selector", SurfaceFixture.SELECTOR,
                "address", address))), address)).get("self_accepted"))
            .as("query answers the mark").isEqualTo(true);
    }

    @Test
    void one_identity_holding_both_runs_the_course_over_rest_and_its_acceptance_is_marked() {
        String id = SurfaceFixture.open("alone, over rest", "code");

        Response claimed = given().contentType(ContentType.JSON)
            .body(Map.of("duration", "PT1H")).post(SurfaceFixture.item(id) + ":claim");
        assertThat(claimed.statusCode()).as(claimed.asString()).isEqualTo(200);
        Response delivered = given().contentType(ContentType.JSON)
            .body(Map.of("receipt", claimed.jsonPath().getString("receipt"),
                "fields", Map.of("text", "the answer")))
            .post(SurfaceFixture.item(id) + ":deliver");
        assertThat(delivered.statusCode()).as(delivered.asString()).isEqualTo(200);

        Response head = given().get(SurfaceFixture.item(id));
        Response accepted = given().header("If-Match", head.header("ETag"))
            .post(SurfaceFixture.item(id) + ":accept");
        assertThat(accepted.statusCode()).as(accepted.asString()).isEqualTo(200);
        assertThat(accepted.jsonPath().getBoolean("fields.self_accepted")).isTrue();

        assertThat(given().get(SurfaceFixture.item(id)).jsonPath()
                .getBoolean("fields.self_accepted"))
            .as("read answers the mark").isTrue();
        String address = SurfaceFixture.address(id);
        assertThat(given().get(SurfaceFixture.collection() + "?address=" + address)
                .jsonPath().getList("objects.fields.self_accepted"))
            .as("query answers the mark").containsExactly(true);
    }

    // =======================================================================
    // Criterion 3 -- another commissioner accepts, unmarked
    // =======================================================================

    @Test
    void an_acceptance_by_another_commissioner_is_not_marked() {
        String id = SurfaceFixture.open("delivered by both, accepted by another", "code");
        String address = SurfaceFixture.address(id);
        claimAndDeliver(address);

        SurfaceFixture.asConsole(identity);
        Map<String, Object> head = Mcp.answer(Mcp.call("dispatch_read",
            Map.of("address", address)));
        Map<String, Object> accepted = Mcp.answer(Mcp.call("dispatch_accept", Map.of(
            "address", address, "conflict_token", head.get("conflict_token"))));

        assertThat(Mcp.field(accepted, "self_accepted")).isEqualTo("false");
        assertThat(given().get(SurfaceFixture.item(id)).jsonPath()
                .getBoolean("fields.self_accepted"))
            .as("and read over REST says the same").isFalse();
    }

    @Test
    void the_mark_stands_only_on_a_task_closed_as_accepted() {
        String address = SurfaceFixture.address(SurfaceFixture.open("still open", "code"));
        Map<String, Object> open = Mcp.answer(Mcp.call("dispatch_read",
            Map.of("address", address)));
        assertThat(fields(open)).doesNotContainKey("self_accepted");

        Map<String, Object> withdrawn = Mcp.answer(Mcp.call("dispatch_withdraw", Map.of(
            "address", address, "conflict_token", open.get("conflict_token"))));
        assertThat(Mcp.field(withdrawn, "outcome")).isEqualTo("withdrawn");
        assertThat(fields(withdrawn)).doesNotContainKey("self_accepted");
    }

    /**
     * A task accepted before the stamp existed holds NULL, and is answered as
     * unknown: the field is absent, never false. The acceptance of the earlier
     * service is staged by writing the closed state directly, as that service
     * wrote it, without the column it did not know.
     */
    @Test
    void a_task_accepted_before_the_stamp_existed_is_unknown_not_false() {
        String id = SurfaceFixture.open("accepted by the earlier service", "code");
        String address = SurfaceFixture.address(id);
        claimAndDeliver(address);
        PlatformFixture.run("UPDATE dispatch.task SET state = 'closed', outcome = 'accepted', "
            + "holder_subject = NULL, holder_receipt_hash = NULL "
            + "WHERE scope_id = '" + SubstrateDatabaseResource.SCOPE_ID + "' AND number = "
            + id.substring(0, id.indexOf('.')) + " AND sub = 0 AND selector_id = (SELECT id "
            + "FROM dispatch.selector WHERE scope_id = '" + SubstrateDatabaseResource.SCOPE_ID
            + "' AND name = '" + SurfaceFixture.SELECTOR + "')");

        Map<String, Object> read = Mcp.answer(Mcp.call("dispatch_read",
            Map.of("address", address)));
        assertThat(Mcp.field(read, "outcome")).isEqualTo("accepted");
        assertThat(fields(read)).doesNotContainKey("self_accepted");
        assertThat(given().get(SurfaceFixture.item(id)).jsonPath().getMap("fields"))
            .as("and over REST").doesNotContainKey("self_accepted");
    }

    // =======================================================================
    // Criterion 4 -- the executing capacity alone never accepts
    // =======================================================================

    /**
     * Neither its own answer nor another's, and alike on both transports. The
     * other half of the same separation is held beside it: an identity
     * holding the commissioning capacity alone never takes a task up, so a
     * reviewer granted that capacity alone never executes what it reviews.
     *
     * <p>RED PROBE: with the commissioner branch of {@code Checks.relation}
     * answering every caller, the executor accepts; with the candidate branch
     * answering every caller, the console identity claims. Either turns this
     * test red.
     */
    @Test
    void an_identity_holding_the_executing_capacity_alone_never_accepts() {
        SurfaceFixture.asConsole(identity);
        String own = SurfaceFixture.open("delivered by the executor", "code");
        String foreign = SurfaceFixture.open("delivered by the other executor", "code");
        SurfaceFixture.asExecutor(identity);
        claimAndDeliver(SurfaceFixture.address(own));
        SurfaceFixture.asOtherExecutor(identity);
        claimAndDeliver(SurfaceFixture.address(foreign));

        SurfaceFixture.asExecutor(identity);
        for (String id : List.of(own, foreign)) {
            String address = SurfaceFixture.address(id);
            Map<String, Object> head = Mcp.answer(Mcp.call("dispatch_read",
                Map.of("address", address)));
            assertThat(Mcp.nextCalls(head)).as("%s: accept is not listed", id)
                .doesNotContain("dispatch_accept");

            Map<String, Object> overMcp = Mcp.refusal(Mcp.call("dispatch_accept", Map.of(
                "address", address, "conflict_token", head.get("conflict_token"))));
            Response overRest = given().header("If-Match", "\"" + head.get("conflict_token")
                + "\"").post(SurfaceFixture.item(id) + ":accept");

            assertThat(overMcp.get("reason")).as("%s over MCP", id).isEqualTo("ROLE_INSUFFICIENT");
            assertThat(overRest.statusCode()).as("%s over REST: %s", id, overRest.asString())
                .isEqualTo(403);
            assertThat(sameRefusal(overRest))
                .as("%s: the same refusal on both transports", id)
                .isEqualTo(sameRefusal(overMcp));
            assertThat(given().get(SurfaceFixture.item(id)).jsonPath()
                    .getString("fields.state"))
                .as("%s: and nothing was accepted", id).isEqualTo("delivered");
        }

        SurfaceFixture.asConsole(identity);
        String open = SurfaceFixture.open("open to executors only", "code");
        String address = SurfaceFixture.address(open);
        assertThat(Mcp.nextCalls(Mcp.answer(Mcp.call("dispatch_read",
                Map.of("address", address)))))
            .as("claim is not listed for the console identity").doesNotContain("dispatch_claim");
        Map<String, Object> claimOverMcp = Mcp.refusal(Mcp.call("dispatch_claim",
            Map.of("address", address)));
        Response claimOverRest = given().contentType(ContentType.JSON).body(Map.of())
            .post(SurfaceFixture.item(open) + ":claim");
        assertThat(claimOverMcp.get("reason")).isEqualTo("ROLE_INSUFFICIENT");
        assertThat(sameRefusal(claimOverRest))
            .as("the same refusal on both transports: %s", claimOverRest.asString())
            .isEqualTo(sameRefusal(claimOverMcp));
    }

    // =======================================================================
    // Criterion 5 -- neither capacity is still refused
    // =======================================================================

    /**
     * The kernel's {@code ACTOR_UNKNOWN} ({@code CallerActorTest}), which the
     * wire carries as {@code ROLE_INSUFFICIENT} 403 with nothing beyond {@code
     * attempted}: the caller holds no capacity of the service.
     */
    @Test
    void a_token_carrying_neither_capacity_is_still_refused() {
        SurfaceFixture.asCapacitylessToken(identity);
        Map<String, Object> overMcp = Mcp.refusal(Mcp.call("dispatch_query", Map.of(
            "scope", SurfaceFixture.SCOPE, "selector", SurfaceFixture.SELECTOR)));
        assertThat(overMcp.get("reason")).isEqualTo("ROLE_INSUFFICIENT");
        assertThat(overMcp.get("data")).isEqualTo(Map.of("attempted", "dispatch_query"));
        Response overRest = given().get(SurfaceFixture.collection());
        assertThat(overRest.statusCode()).as(overRest.asString()).isEqualTo(403);
        assertThat(overRest.jsonPath().getString("reason")).isEqualTo("ROLE_INSUFFICIENT");
    }

    // -----------------------------------------------------------------------

    /**
     * A refusal as both transports must agree on it: the reason, and the
     * message with the call named as REST names it. The message names the
     * call by the caller's surface, {@code dispatch_accept} on MCP and {@code
     * accept} on REST; the rest of it is the same text.
     */
    private static List<String> sameRefusal(Map<String, Object> overMcp) {
        return List.of(String.valueOf(overMcp.get("reason")),
            String.valueOf(overMcp.get("message")).replaceFirst("^dispatch_", ""));
    }

    private static List<String> sameRefusal(Response overRest) {
        return List.of(overRest.jsonPath().getString("reason"),
            overRest.jsonPath().getString("message"));
    }

    /** Claims and delivers the task as the current identity, over MCP. */
    private static void claimAndDeliver(String address) {
        String receipt = String.valueOf(Mcp.answer(Mcp.call("dispatch_claim",
            Map.of("address", address))).get("receipt"));
        Mcp.answer(Mcp.call("dispatch_deliver", Map.of("address", address, "receipt", receipt,
            "fields", Map.of("text", "an answer"))));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> onlyTask(Map<String, Object> listing, String address) {
        List<Map<String, Object>> tasks = (List<Map<String, Object>>) listing.get("objects");
        assertThat(tasks).extracting(t -> t.get("address"))
            .as("the query narrowed to this task").containsExactly(address);
        return tasks.get(0);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> fields(Map<String, Object> answer) {
        return (Map<String, Object>) answer.get("fields");
    }
}
