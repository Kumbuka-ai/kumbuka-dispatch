package ai.kumbuka.dispatch.contract;

import ai.kumbuka.dispatch.platform.PlatformFixture;
import ai.kumbuka.dispatch.surface.Mcp;
import ai.kumbuka.dispatch.surface.SurfaceFixture;
import ai.kumbuka.dispatch.surface.Writes;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every reason of the catalogue, raised on the wire, in the shape TAR-0004
 * section 7 fixes: the call as it was made, the state, the reason and the way
 * out.
 *
 * <p>The three refusals about a scope are raised in {@code ScopeIsolationIT};
 * {@code UNEXPECTED_FAILURE} has no rule a caller can break and is observed in
 * {@code UnexpectedFailureReferenceTest} and {@code CallRouterTest}.
 *
 * <p>Red probe, observed: with the current token left out of the refusal of a
 * stale conflict token, {@link #a_stale_conflict_token_is_refused_with_the_current_one}
 * turns red.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class EveryRefusalIT {

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asConsole(identity);
    }

    @Test
    void not_found_says_the_same_bytes_for_a_missing_task_and_an_unknown_scope() {
        Response missing = Mcp.call("dispatch_read",
            Map.of("address", SurfaceFixture.address("99999.0")));
        Response unknownScope = Mcp.call("dispatch_read",
            Map.of("address", "dispatch://no-such-scope/sprint/1.0"));
        assertThat(Mcp.reason(missing)).isEqualTo("NOT_FOUND");
        assertThat(Mcp.refusal(missing)).doesNotContainKey("data");
        assertThat(missing.jsonPath().getString("result.structuredContent"))
            .isEqualTo(unknownScope.jsonPath().getString("result.structuredContent"));
        given().get(SurfaceFixture.item("99999.0")).then().statusCode(404);
    }

    @Test
    void the_state_names_itself_and_the_way_out() {
        String address = SurfaceFixture.address(SurfaceFixture.open("sent already", "code"));
        Map<String, Object> refusal = Mcp.refusal(Mcp.call("dispatch_send", Map.of(
            "address", address, "conflict_token", token(address))));

        assertThat(refusal.get("reason")).isEqualTo("STATE_DOES_NOT_ALLOW");
        assertThat(String.valueOf(refusal.get("message")))
            .contains("dispatch_send", address, "open", "only in draft");
        Map<String, Object> data = data(refusal);
        assertThat(data.get("attempted")).isEqualTo("dispatch_send");
        assertThat(data.get("state")).isEqualTo("open");
        assertThat(String.valueOf(data.get("next"))).contains("dispatch_withdraw");
    }

    @Test
    void a_condition_on_the_hold_shares_the_state_s_code_and_its_text_names_both() {
        String address = SurfaceFixture.address(SurfaceFixture.open("asked", "code"));
        SurfaceFixture.asExecutor(identity);
        String receipt = receiptOf(Mcp.call("dispatch_claim", Map.of("address", address)));
        Mcp.answer(Mcp.call("dispatch_ask", Map.of("address", address, "receipt", receipt,
            "fields", Map.of("question", "which?", "free_text", true))));

        Map<String, Object> refusal = Mcp.refusal(Mcp.call("dispatch_resume",
            Map.of("address", address, "receipt", receipt)));

        assertThat(refusal.get("reason")).isEqualTo("STATE_DOES_NOT_ALLOW");
        assertThat(String.valueOf(refusal.get("message")))
            .contains("on_hold (question)", "dependency or something external");
        assertThat(String.valueOf(data(refusal).get("next")))
            .as("the holder can still fail the task while the question is pending")
            .contains("dispatch_fail");
    }

    @Test
    void a_closed_task_is_refused_as_finished() {
        String address = SurfaceFixture.address(SurfaceFixture.open("withdrawn", "code"));
        Mcp.answer(Mcp.call("dispatch_withdraw", Map.of("address", address,
            "conflict_token", token(address))));
        Map<String, Object> refusal = Mcp.refusal(Mcp.call("dispatch_send", Map.of(
            "address", address, "conflict_token", token(address))));
        assertThat(String.valueOf(refusal.get("message"))).contains("finished");
        assertThat(data(refusal).get("state")).isEqualTo("closed (withdrawn)");
    }

    @Test
    void a_call_of_the_other_part_is_refused_with_the_caller_s_part() {
        String address = SurfaceFixture.address(SurfaceFixture.open("not yours", "code"));
        SurfaceFixture.asExecutor(identity);
        Map<String, Object> refusal = Mcp.refusal(Mcp.call("dispatch_withdraw", Map.of(
            "address", address, "conflict_token", "any")));
        assertThat(refusal.get("reason")).isEqualTo("ROLE_DOES_NOT_ALLOW");
        assertThat(String.valueOf(refusal.get("message")))
            .contains("the commissioner", "candidate");
    }

    @Test
    void a_draw_by_a_commissioner_is_refused_for_the_part_and_not_as_not_found() {
        SurfaceFixture.open("drawable", "code");
        Map<String, Object> refusal = Mcp.refusal(Mcp.call("dispatch_claim_next", Map.of(
            "scope", SurfaceFixture.SCOPE, "selector", SurfaceFixture.SELECTOR,
            "apparatus", List.of("code"))));
        assertThat(refusal.get("reason")).isEqualTo("ROLE_DOES_NOT_ALLOW");
        assertThat(String.valueOf(refusal.get("message"))).contains("an executor");
    }

    @Test
    void the_holder_s_call_by_another_is_refused_and_so_is_one_after_the_lease() {
        String address = SurfaceFixture.address(SurfaceFixture.open("held", "code"));
        SurfaceFixture.asExecutor(identity);
        String receipt = receiptOf(Mcp.call("dispatch_claim", Map.of("address", address)));

        SurfaceFixture.asOtherExecutor(identity);
        Map<String, Object> other = Mcp.refusal(Mcp.call("dispatch_deliver", Map.of(
            "address", address, "receipt", receipt, "fields", Map.of("text", "mine"))));
        assertThat(other.get("reason")).isEqualTo("NOT_THE_HOLDER");
        assertThat(String.valueOf(other.get("message"))).contains("another executor holds it");

        SurfaceFixture.asExecutor(identity);
        lapse(address);
        Map<String, Object> lapsed = Mcp.refusal(Mcp.call("dispatch_renew", Map.of(
            "address", address, "receipt", receipt)));
        assertThat(lapsed.get("reason")).isEqualTo("NOT_THE_HOLDER");
        assertThat(String.valueOf(lapsed.get("message"))).contains("your lease on it ended");
    }

    @Test
    void a_wrong_receipt_is_refused() {
        String address = SurfaceFixture.address(SurfaceFixture.open("receipted", "code"));
        SurfaceFixture.asExecutor(identity);
        Mcp.answer(Mcp.call("dispatch_claim", Map.of("address", address)));
        Map<String, Object> refusal = Mcp.refusal(Mcp.call("dispatch_renew", Map.of(
            "address", address, "receipt", "not-the-receipt")));
        assertThat(refusal.get("reason")).isEqualTo("RECEIPT_WRONG");
    }

    @Test
    void a_root_with_unfinished_children_closes_only_on_its_confirmation() {
        String root = SurfaceFixture.address(SurfaceFixture.open("a root", "code"));
        Response child = Mcp.call("dispatch_create", Map.of("scope", SurfaceFixture.SCOPE,
            "selector", SurfaceFixture.SELECTOR, "parent", root,
            "fields", Map.of("title", "a child", "apparatus", "code")));
        String childAddress = String.valueOf(Mcp.answer(child).get("address"));

        Map<String, Object> refusal = Mcp.refusal(Mcp.call("dispatch_withdraw", Map.of(
            "address", root, "conflict_token", token(root))));
        assertThat(refusal.get("reason")).isEqualTo("CHILDREN_NOT_FINISHED");
        Map<String, Object> data = data(refusal);
        assertThat(String.valueOf(data.get("offenders"))).contains(childAddress, "draft");
        String confirmation = String.valueOf(data.get("confirmation"));

        Map<String, Object> closed = Mcp.answer(Mcp.call("dispatch_withdraw", Map.of(
            "address", root, "conflict_token", token(root), "confirmation", confirmation)));
        assertThat(Mcp.field(closed, "state")).isEqualTo("closed");
        assertThat(Mcp.field(Mcp.answer(Mcp.call("dispatch_read",
            Map.of("address", childAddress))), "outcome")).isEqualTo("withdrawn");
    }

    @Test
    void a_claim_before_the_deferral_passed_is_deferral_pending() {
        String address = SurfaceFixture.address(SurfaceFixture.open("deferred", "code"));
        SurfaceFixture.asExecutor(identity);
        String receipt = receiptOf(Mcp.call("dispatch_claim", Map.of("address", address)));
        String later = Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS)
            .toString();
        Mcp.answer(Mcp.call("dispatch_defer", Map.of("address", address, "receipt", receipt,
            "fields", Map.of("not_before", later))));

        SurfaceFixture.asOtherExecutor(identity);
        Map<String, Object> refusal = Mcp.refusal(Mcp.call("dispatch_claim",
            Map.of("address", address)));
        assertThat(refusal.get("reason")).isEqualTo("DEFERRAL_PENDING");
        assertThat(String.valueOf(refusal.get("message"))).contains(later);
    }

    @Test
    void a_draw_that_finds_nothing_is_nothing_to_take() {
        SurfaceFixture.asExecutor(identity);
        Map<String, Object> refusal = Mcp.refusal(Mcp.call("dispatch_claim_next", Map.of(
            "scope", SurfaceFixture.SCOPE, "selector", SurfaceFixture.SELECTOR,
            "apparatus", List.of("nobody-is-this"))));
        assertThat(refusal.get("reason")).isEqualTo("NOTHING_TO_TAKE");
        assertThat(String.valueOf(data(refusal).get("next"))).contains("dispatch_query");
    }

    @Test
    void a_duration_that_is_no_lease_is_refused() {
        String address = SurfaceFixture.address(SurfaceFixture.open("leased", "code"));
        SurfaceFixture.asExecutor(identity);
        String before = Writes.snapshot();
        assertThat(Mcp.reason(Mcp.call("dispatch_claim", Map.of("address", address,
            "duration", "soon")))).isEqualTo("CLAIM_DURATION_INVALID");
        assertThat(Mcp.reason(Mcp.call("dispatch_claim", Map.of("address", address,
            "duration", "PT0S")))).isEqualTo("CLAIM_DURATION_INVALID");
        assertThat(Writes.snapshot()).isEqualTo(before);
    }

    @Test
    void a_stale_conflict_token_is_refused_with_the_current_one() {
        String address = SurfaceFixture.address(SurfaceFixture.open("tokened", "code"));
        String current = token(address);
        Map<String, Object> refusal = Mcp.refusal(Mcp.call("dispatch_withdraw", Map.of(
            "address", address, "conflict_token", "2000-01-01T00:00:00Z")));
        assertThat(refusal.get("reason")).isEqualTo("CONFLICT_TOKEN_STALE");
        assertThat(data(refusal).get("conflict_token")).isEqualTo(current);
        assertThat(String.valueOf(refusal.get("message"))).contains(current);

        Response rest = given().header("If-Match", "\"2000-01-01T00:00:00Z\"")
            .post(SurfaceFixture.item(address.substring(address.lastIndexOf('/') + 1))
                + ":withdraw");
        assertThat(rest.statusCode()).isEqualTo(412);
        assertThat(rest.jsonPath().getString("data.conflict_token")).isEqualTo(current);
    }

    @Test
    void an_undeclared_bracket_kind_names_the_declared_ones() {
        Map<String, Object> refusal = Mcp.refusal(Mcp.call("dispatch_create", Map.of(
            "scope", SurfaceFixture.SCOPE, "selector", "no-such-kind",
            "fields", Map.of("title", "t", "apparatus", "code"))));
        assertThat(refusal.get("reason")).isEqualTo("SELECTOR_UNKNOWN");
        assertThat(String.valueOf(refusal.get("message"))).contains(SurfaceFixture.SELECTOR);
    }

    @Test
    void a_key_spent_on_other_arguments_is_refused() {
        Map<String, Object> first = Map.of("scope", SurfaceFixture.SCOPE,
            "selector", SurfaceFixture.SELECTOR, "idempotency_key", "every-refusal-key",
            "fields", Map.of("title", "first", "apparatus", "code"));
        Mcp.answer(Mcp.call("dispatch_create", first));
        Map<String, Object> second = Map.of("scope", SurfaceFixture.SCOPE,
            "selector", SurfaceFixture.SELECTOR, "idempotency_key", "every-refusal-key",
            "fields", Map.of("title", "second", "apparatus", "code"));
        assertThat(Mcp.reason(Mcp.call("dispatch_create", second)))
            .isEqualTo("IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    void a_call_at_an_address_it_does_not_apply_to_is_refused_with_allow() {
        Response refused = given().contentType(ContentType.JSON)
            .post(SurfaceFixture.collection() + ":send");
        assertThat(refused.statusCode()).isEqualTo(405);
        assertThat(refused.jsonPath().getString("reason")).isEqualTo("CALL_NOT_AT_THIS_ADDRESS");
        assertThat(refused.header("Allow")).isEqualTo("POST, GET");
    }

    @Test
    void the_argument_refusals_name_the_argument() {
        String address = SurfaceFixture.address(SurfaceFixture.open("argued", "code"));
        assertThat(Mcp.reason(Mcp.call("dispatch_send", Map.of("address", address))))
            .isEqualTo("ARGUMENT_MISSING");
        assertThat(Mcp.reason(Mcp.call("dispatch_read", Map.of("address", "sprint/1.0"))))
            .isEqualTo("ARGUMENT_INVALID");
        assertThat(Mcp.reason(Mcp.call("dispatch_read_text", Map.of("address", address,
            "part", "everything")))).isEqualTo("ARGUMENT_INVALID");
    }

    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(Map<String, Object> refusal) {
        return (Map<String, Object>) refusal.get("data");
    }

    private static String token(String address) {
        return String.valueOf(Mcp.answer(Mcp.call("dispatch_read",
            Map.of("address", address))).get("conflict_token"));
    }

    private static String receiptOf(Response claim) {
        return String.valueOf(Mcp.answer(claim).get("receipt"));
    }

    /** Lets the lease of a held task run out; writes nothing else. */
    private static void lapse(String address) {
        String identity = Mcp.field(Mcp.answer(Mcp.call("dispatch_read",
            Map.of("address", address))), "identity").substring("dispatch://".length());
        PlatformFixture.run("UPDATE dispatch.task SET lease_expires_at = now() - interval "
            + "'1 minute' WHERE uuid = '" + identity + "'");
    }
}
