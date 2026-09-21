package ai.kumbuka.dispatch.contract;

import ai.kumbuka.dispatch.surface.RefusalCode;
import ai.kumbuka.dispatch.surface.SurfaceFixture;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A5, the half a declaration cannot carry: every declared refusal is actually
 * raised, and none of them names the kernel.
 *
 * <p>{@code DeclarationConformanceTest} checks that the catalogue matches the
 * contract — that every code exists and every pattern names the values the
 * contract's does. That is a statement about data. This is the statement about
 * behaviour: each code is PROVOKED against a running service, and the answer is
 * read back.
 *
 * <p>Both halves are needed and neither implies the other. A catalogue can
 * declare a reason nothing raises — a published promise nothing keeps — and a
 * service can raise a reason whose wording drifted from the pattern it was
 * declared with.
 *
 * <h2>What is asserted of every one of them</h2>
 *
 * The reason on the wire is the one expected, and the message carries no kernel
 * name. The second is the whole of the measured defect: on 2026-09-18 a caller
 * was told its exchange "cannot takeup" — a verb it had not called, in a
 * vocabulary it had no way to look up.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@Tag("TST-0005")
class EveryRefusalIT {

    /**
     * The kernel's own transition names.
     *
     * <p>None of these may appear in any message a caller receives. They are
     * the names of the state machine's edges and they are correct — they are
     * simply not the caller's vocabulary, and a caller cannot act on a verb it
     * has no way to call.
     */
    private static final List<String> KERNEL_NAMES = List.of(
        "takeup", "ratify", "reject", "revert", "consume", "abandon");

    /**
     * The two codes no probe here raises, with the reason each is excluded.
     *
     * <p>Named rather than quietly skipped: a probe that silently covers
     * fifteen of seventeen reads as complete.
     */
    private static final Set<RefusalCode> NOT_PROVOKED_HERE = EnumSet.of(
        // Raised only when the service itself breaks. Provoking it would mean
        // injecting a fault into the domain, which would probe the injection
        // rather than the surface. Its shape is asserted where it is built.
        RefusalCode.UNEXPECTED_FAILURE,

        // The next two are unreachable ON THIS SURFACE, and section 4.4 now
        // says so in as many words: "On the assistant surface the receipt and
        // the conflict token are required arguments wherever a call takes
        // them, so their absence is answered as ARGUMENT_MISSING;
        // RECEIPT_MISSING and CONFLICT_TOKEN_MISSING are raised on the generic
        // surface only." The closed-schema check refuses the omission before
        // the surface looks at the value.
        //
        // They ARE provoked, over REST, by
        // GenericSurfaceFormIT.the_two_codes_this_surface_alone_can_raise —
        // which is a probe that exists. The predecessor's comment here named
        // probes that did not, and a comment asserting coverage is the one
        // kind of comment that makes a gap invisible.
        RefusalCode.RECEIPT_MISSING,
        RefusalCode.CONFLICT_TOKEN_MISSING);

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asConsole(identity);
    }

    @Test
    void every_declared_refusal_is_raised_by_something() {
        Map<RefusalCode, String> raised = provokeAll();

        for (RefusalCode code : RefusalCode.values()) {
            if (NOT_PROVOKED_HERE.contains(code)) {
                continue;
            }
            assertThat(raised)
                .as("%s is declared and nothing here raises it. A reason in the catalogue "
                    + "that no path produces is a promise to a caller that nothing keeps",
                    code)
                .containsKey(code);
        }
    }

    @Test
    void no_refusal_message_carries_a_kernel_name() {
        for (Map.Entry<RefusalCode, String> raised : provokeAll().entrySet()) {
            for (String kernel : KERNEL_NAMES) {
                assertThat(raised.getValue().toLowerCase(java.util.Locale.ROOT))
                    .as("%s's message names the kernel verb '%s'. Measured 2026-09-18: a "
                        + "caller was told its exchange 'cannot takeup' — a verb it had "
                        + "not called and could not look up", raised.getKey(), kernel)
                    .doesNotContain(kernel);
            }
        }
    }

    @Test
    void every_refusal_that_carries_data_carries_the_way_out() {
        String open = commission();

        // A state refusal: accept on an exchange with no delivered answer.
        Response refused = call("dispatch_accept_return", Map.of("address", open));

        assertThat((Object) refused.jsonPath().get("result.structuredContent.data"))
            .as("a refusal about a real exchange carries the data a caller acts on")
            .isNotNull();
        assertThat((Object) refused.jsonPath().get("result.structuredContent.data.next"))
            .as("and the way OUT travels with it. The measured refusal stated the way IN "
                + "— 'active is reachable only from [open]' — which is true and useless")
            .isNotNull();
        assertThat(refused.jsonPath().getString("result.structuredContent.data.attempted"))
            .as("the call the caller made, under the name it used")
            .isEqualTo("dispatch_accept_return");
    }

    // =======================================================================
    // Provoking each one
    // =======================================================================

    /**
     * One call per declared reason, and the message each answered with.
     *
     * <p>Built once per probe rather than shared, because several of these
     * leave the exchange in a state the next would read differently, and a
     * shared fixture would make the order of the probes matter.
     */
    private Map<RefusalCode, String> provokeAll() {
        Map<RefusalCode, String> raised = new EnumMap<>(RefusalCode.class);

        String open = commission();
        String root = commission();
        commissionChild(root);

        // NOT_FOUND — an address nothing occupies.
        note(raised, call("dispatch_read", Map.of("address",
            "dispatch://" + SurfaceFixture.SCOPE + "/" + SurfaceFixture.SELECTOR
                + "/99999.0")));

        // STATE_DOES_NOT_ALLOW — taking up what is already taken.
        //
        // Not "accepting an exchange with no answer": that one has its own
        // code now (NO_ANSWER_DELIVERED), which is the point of splitting it
        // out. A state refusal has to be provoked by a state that genuinely
        // does not permit the transition.
        String taken = commission();
        SurfaceFixture.asExecutor(identity);
        call("dispatch_take", Map.of("address", taken, "duration", "PT1H"));
        note(raised, call("dispatch_take",
            Map.of("address", taken, "duration", "PT1H")));
        SurfaceFixture.asConsole(identity);

        // CHILDREN_NOT_FINISHED — closing a bracket with an open child.
        note(raised, call("dispatch_close_bracket", Map.of("address", root)));

        // ARGUMENT_UNKNOWN — an argument no call declares.
        Map<String, Object> withExtra = new LinkedHashMap<>(commissionArguments());
        withExtra.put("no_call_declares_this", "x");
        note(raised, call("dispatch_commission", withExtra));

        // ARGUMENT_MISSING — a required value left out.
        note(raised, call("dispatch_read", Map.of()));

        // ARGUMENT_INVALID — a malformed address.
        note(raised, call("dispatch_read", Map.of("address", "not-an-address")));

        // CLAIM_DURATION_INVALID — a duration that is not one.
        note(raised, call("dispatch_take",
            Map.of("address", open, "duration", "half an hour")));

        // SELECTOR_UNKNOWN — a bracket kind this scope does not declare.
        note(raised, call("dispatch_query", Map.of(
            "scope", SurfaceFixture.SCOPE, "selector", "a-kind-nobody-declared")));

        // ARGUMENT_MISSING again, through a different door: a call that
        // repeats the conflict token, with the token left out. On this surface
        // the token is a REQUIRED argument, so the closed-schema check answers
        // first — which is why CONFLICT_TOKEN_MISSING is excluded above and
        // this line is not the provocation of it. The predecessor's comment
        // here said it was.
        note(raised, call("dispatch_cancel", Map.of(
            "address", open, "fields", Map.of("reason", "no longer wanted"))));

        // CALL_NOT_AT_THIS_ADDRESS — a bracket verb at a child.
        //
        // The address is well formed and names something the caller can see;
        // what does not fit is the pairing. The predecessor answered this with
        // ARGUMENT_INVALID, which sends a caller correcting an address that was
        // right.
        note(raised, call("dispatch_close_bracket",
            Map.of("address", firstChildOf(root))));

        // IDEMPOTENCY_KEY_REUSED — one key, two different commissions.
        Map<String, Object> first = new LinkedHashMap<>(commissionArguments());
        first.put("idempotency_key", "a-key-of-my-own");
        call("dispatch_commission", first);

        Map<String, Object> second = new LinkedHashMap<>(commissionArguments());
        second.put("idempotency_key", "a-key-of-my-own");
        second.put("fields", Map.of("title", "a different commission entirely",
            "apparatus", "code", "text", "with different text too"));
        note(raised, call("dispatch_commission", second));

        // CONFLICT_TOKEN_STALE — a token that is not the one it holds.
        note(raised, call("dispatch_cancel", Map.of(
            "address", open, "conflict_token", "1999-01-01T00:00:00Z",
            "fields", Map.of("reason", "no longer wanted"))));

        // ROLE_DOES_NOT_ALLOW — the executor cannot accept an answer.
        SurfaceFixture.asExecutor(identity);
        String receipt = receiptOf(call("dispatch_take",
            Map.of("address", open, "duration", "PT1H")));
        note(raised, call("dispatch_accept_return", Map.of("address", open)));

        // RECEIPT_WRONG — a receipt that is not the one it issued.
        note(raised, call("dispatch_deliver_return", Map.of(
            "address", open, "receipt", "not-the-one-it-holds",
            "fields", Map.of("text", "an answer"))));

        // NOT_THE_HOLDER — a second executor writing on somebody else's.
        SurfaceFixture.asOtherExecutor(identity);
        note(raised, call("dispatch_deliver_return", Map.of(
            "address", open, "receipt", receipt,
            "fields", Map.of("text", "an answer"))));

        // NOTHING_TO_TAKE — a draw from a set with nothing free.
        //
        // A declared bracket kind this probe has put nothing into, rather than
        // the one it has been commissioning in: the refusal says "the set is
        // there and empty of anything claimable", and a set that still holds an
        // open exchange would answer with that exchange instead.
        note(raised, call("dispatch_take_next", Map.of(
            "scope", SurfaceFixture.SCOPE, "selector", "satellite",
            "duration", "PT1H")));

        // NO_ANSWER_DELIVERED — the commissioner accepting a question.
        SurfaceFixture.asExecutor(identity);
        call("dispatch_ask_commissioner", Map.of(
            "address", open, "receipt", receipt,
            "fields", Map.of("question", "something I cannot decide")));
        SurfaceFixture.asConsole(identity);
        note(raised, call("dispatch_accept_return", Map.of("address", open)));

        return raised;
    }

    /**
     * Records the reason and message of an answer, if it was a refusal.
     *
     * <p>Not named {@code record}: that is a restricted identifier, and a
     * method that shadows one reads as a declaration to anybody skimming.
     */
    private static void note(Map<RefusalCode, String> into, Response answer) {
        if (!Boolean.TRUE.equals(answer.jsonPath().getBoolean("result.isError"))) {
            return;
        }
        String reason = answer.jsonPath().getString("result.structuredContent.reason");
        String message = answer.jsonPath().getString("result.structuredContent.message");
        if (reason != null) {
            into.putIfAbsent(RefusalCode.valueOf(reason), String.valueOf(message));
        }
    }

    // =======================================================================
    // Calling
    // =======================================================================

    private Map<String, Object> commissionArguments() {
        return Map.of(
            "scope", SurfaceFixture.SCOPE,
            "selector", SurfaceFixture.SELECTOR,
            "fields", Map.of("title", "a probe", "apparatus", "code",
                "text", "the body of the commission"));
    }

    private String commission() {
        return call("dispatch_commission", commissionArguments())
            .jsonPath().getString("result.structuredContent.address");
    }

    private String commissionChild(String parent) {
        Map<String, Object> arguments = new LinkedHashMap<>(commissionArguments());
        arguments.put("parent", parent);
        return call("dispatch_commission", arguments)
            .jsonPath().getString("result.structuredContent.address");
    }

    /** The child this probe put under a bracket, for the verb that is not for one. */
    private String firstChildOf(String root) {
        return childOf.computeIfAbsent(root, this::commissionChild);
    }

    /**
     * The children this probe has commissioned, by bracket root.
     *
     * <p>Remembered rather than re-commissioned: {@code provokeAll} runs twice
     * in this class, and a second child under the same root would change what
     * CHILDREN_NOT_FINISHED counts.
     */
    private final Map<String, String> childOf = new LinkedHashMap<>();

    private static String receiptOf(Response answer) {
        return answer.jsonPath().getString("result.structuredContent.receipt");
    }

    private Response call(String tool, Map<String, Object> arguments) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", 1);
        envelope.put("method", "tools/call");
        envelope.put("params", Map.of("name", tool, "arguments", arguments));
        return given().contentType(ContentType.JSON).body(envelope).when().post("/mcp");
    }
}
