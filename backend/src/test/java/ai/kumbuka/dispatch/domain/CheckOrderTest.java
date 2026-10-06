package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.domain.Decision.Check;
import ai.kumbuka.dispatch.domain.DispatchException.Reason;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static ai.kumbuka.dispatch.domain.TransitionMatrixTest.C;
import static ai.kumbuka.dispatch.domain.TransitionMatrixTest.H;
import static ai.kumbuka.dispatch.domain.TransitionMatrixTest.NOW;
import static ai.kumbuka.dispatch.domain.TransitionMatrixTest.RECEIPT;
import static ai.kumbuka.dispatch.domain.TransitionMatrixTest.TOKEN;
import static ai.kumbuka.dispatch.domain.TransitionMatrixTest.situation;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance criteria 3 and 5: one fault answers with one reason, and the lock
 * on acceptance holds whatever capacity the deliverer carries.
 *
 * <p>The order is state, condition, relation, lock, proof, confirmation,
 * payload (concept section 2.3). For each pair of neighbours there is one call
 * that breaks both, and the answer must be the earlier one's: check and
 * reason. Swapping any two neighbours in {@link Decision#ORDER} turns the
 * case of that pair red.
 */
class CheckOrderTest {

    /** A console identity carrying the subject of the executor that delivered. */
    static final Actor DELIVERER_AS_COMMISSIONER = new Actor(H.subject(), Actor.Kind.CONSOLE);

    @Test
    void state_before_condition() {
        // answer applies in on_hold and only for a question; an active task
        // is in neither, so both checks fail.
        Decision d = Decision.of(TaskVerb.ANSWER, situation("active"),
            TaskCall.by(C).withConflictToken(TOKEN).with(new TaskInput.Reply("yes", null)));
        assertRefused(d, Check.STATE, Reason.TRANSITION_NOT_PERMITTED);
    }

    @Test
    void condition_before_relation() {
        // claim on a deferred task by a console identity: the deferral and the
        // capacity both refuse.
        Decision d = Decision.of(TaskVerb.CLAIM, situation("deferred"), TaskCall.by(C));
        assertRefused(d, Check.CONDITION, Reason.DEFERRAL_PENDING);
    }

    @Test
    void relation_before_lock() {
        // accept by the executor that delivered: not a commissioner, and the
        // deliverer.
        Decision d = Decision.of(TaskVerb.ACCEPT, situation("delivered"),
            TaskCall.by(H).withConflictToken(TOKEN));
        assertRefused(d, Check.RELATION, Reason.ACTOR_UNKNOWN);
    }

    @Test
    void lock_before_proof() {
        // the deliverer with the commissioning capacity, and a stale token.
        Decision d = Decision.of(TaskVerb.ACCEPT, situation("delivered"),
            TaskCall.by(DELIVERER_AS_COMMISSIONER).withConflictToken("stale"));
        assertRefused(d, Check.LOCK, Reason.RATIFICATION_NOT_PERMITTED);
    }

    @Test
    void proof_before_confirmation() {
        // withdraw on a root with an unfinished child, a stale token, no
        // confirmation.
        Decision d = Decision.of(TaskVerb.WITHDRAW, rootWithUnfinishedChild(),
            TaskCall.by(C).withConflictToken("stale"));
        assertRefused(d, Check.PROOF, Reason.CONFLICT_TOKEN_STALE);
    }

    @Test
    void confirmation_before_payload() {
        // withdraw on a root with an unfinished child, no confirmation, and a
        // payload of a shape withdraw does not take. The payload check would
        // not refuse but throw; that it never runs is the point.
        Decision d = Decision.of(TaskVerb.WITHDRAW, rootWithUnfinishedChild(),
            TaskCall.by(C).withConflictToken(TOKEN)
                .with(new TaskInput.Lease(Duration.ofMinutes(5))));
        assertRefused(d, Check.CONFIRMATION, Reason.CONFIRMATION_REQUIRED);
    }

    @Test
    void the_identity_that_delivered_never_accepts_even_with_the_commissioning_capacity() {
        Decision d = Decision.of(TaskVerb.ACCEPT, situation("delivered"),
            TaskCall.by(DELIVERER_AS_COMMISSIONER).withConflictToken(TOKEN));
        assertRefused(d, Check.LOCK, Reason.RATIFICATION_NOT_PERMITTED);
        assertThat(Decision.open(TaskVerb.ACCEPT, situation("delivered"),
                DELIVERER_AS_COMMISSIONER))
            .as("and accept is not listed as open to it")
            .isFalse();
        assertThat(Decision.of(TaskVerb.ACCEPT, situation("delivered"),
                TaskCall.by(C).withConflictToken(TOKEN)))
            .as("while another commissioner accepts")
            .isInstanceOf(Decision.Permitted.class);
    }

    @Test
    void a_write_of_the_holder_without_its_receipt_is_refused() {
        Decision without = Decision.of(TaskVerb.RELEASE, situation("active"), TaskCall.by(H));
        assertRefused(without, Check.PROOF, Reason.RECEIPT_ABSENT);
        Decision wrong = Decision.of(TaskVerb.RELEASE, situation("active"),
            TaskCall.by(H).withReceipt("another"));
        assertRefused(wrong, Check.PROOF, Reason.RECEIPT_MISMATCH);
        assertThat(Decision.of(TaskVerb.RELEASE, situation("active"),
                TaskCall.by(H).withReceipt(RECEIPT)))
            .isInstanceOf(Decision.Permitted.class);
    }

    @Test
    void an_answer_names_an_option_or_free_text_where_admitted() {
        TaskSituation asked = situation("asked");
        Decision none = Decision.of(TaskVerb.ANSWER, asked,
            TaskCall.by(C).withConflictToken(TOKEN).with(new TaskInput.Reply("maybe", null)));
        assertRefused(none, Check.PAYLOAD, Reason.ANSWER_NOT_AN_OPTION);
        Decision text = Decision.of(TaskVerb.ANSWER, asked,
            TaskCall.by(C).withConflictToken(TOKEN).with(new TaskInput.Reply(null, "free")));
        assertRefused(text, Check.PAYLOAD, Reason.ANSWER_NOT_AN_OPTION);
    }

    @Test
    void a_lease_is_positive() {
        Decision d = Decision.of(TaskVerb.CLAIM, situation("open"),
            TaskCall.by(H).with(new TaskInput.Lease(Duration.ZERO)));
        assertRefused(d, Check.PAYLOAD, Reason.CLAIM_DURATION_NOT_POSITIVE);
    }

    // -----------------------------------------------------------------------

    static TaskSituation rootWithUnfinishedChild() {
        TaskSituation open = situation("open");
        return new TaskSituation(open.identity(), open.address(), open.state(), null, null, null,
            null,
            false, null, TOKEN, null, null, true,
            List.of(new TaskSituation.Child(UUID.fromString("00000000-0000-0000-0000-0000000000bb"),
                ExchangeAddress.child("sprint", 1, 1), TaskState.ACTIVE)),
            NOW);
    }

    static void assertRefused(Decision d, Check check, Reason reason) {
        assertThat(d).isInstanceOf(Decision.Refused.class);
        Decision.Refused r = (Decision.Refused) d;
        assertThat(r.check()).as("the check that answers").isEqualTo(check);
        assertThat(r.reason()).as("its reason").isEqualTo(reason);
    }
}
