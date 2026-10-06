package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.platform.PlatformFixture;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import ai.kumbuka.dispatch.tenancy.TenantContext;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static ai.kumbuka.dispatch.domain.TaskStage.C;
import static ai.kumbuka.dispatch.domain.TaskStage.H;
import static ai.kumbuka.dispatch.domain.TaskStage.K;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Holding (TAR-0004 section 5, concept section 2.1) against a running database
 * under the service role: the lease, the lapse that writes nothing, the count
 * of lapses and the parking after the third, the receipt on every write of an
 * executor, the deferral, and the lock on acceptance.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class TaskHoldingIT {

    static final UUID SCOPE = UUID.fromString("00000000-0000-0000-0000-000000000010");
    static final String SELECTOR = "sprint";

    @Inject TaskService tasks;
    @Inject TenantContext tenantContext;

    private AutoCloseable binding;
    private TaskStage stage;

    @BeforeEach
    void freshTenant() {
        UUID tenant = UUID.randomUUID();
        DomainFixture.declareSelector(tenant, SCOPE, SELECTOR);
        binding = tenantContext.bind(tenant);
        stage = new TaskStage(tasks, SCOPE);
    }

    @AfterEach
    void unbind() throws Exception {
        binding.close();
    }

    // =======================================================================
    // Criterion 4 -- the third lapse parks the task
    // =======================================================================

    @Test
    void the_third_lapse_parks_the_task_and_only_withdraw_takes_it_out() {
        TaskStage.Staged s = stage.parked(SELECTOR);

        TaskView head = tasks.read(SCOPE, s.address(), C);
        assertThat(head.state()).as("after the third lapse: on_hold").isEqualTo(TaskState.ON_HOLD);
        assertThat(head.holdReason()).as("with external").isEqualTo(HoldReason.EXTERNAL);
        assertThat(head.holder()).as("and without a holder").isEqualTo(HolderState.NOBODY);
        assertThat(TaskStage.row(s.identity()).lapseCount())
            .as("two lapses were recorded by the claims after them; the third is the one "
                + "being judged, and it is recorded by nobody")
            .isEqualTo(2);

        assertRefused(() -> tasks.act(SCOPE, s.address(), TaskVerb.RESUME,
                TaskCall.by(H).withReceipt(s.receipt())),
            DispatchException.Reason.LEASE_LAPSED);
        assertRefused(() -> tasks.act(SCOPE, s.address(), TaskVerb.RESUME,
                TaskCall.by(K).withReceipt(s.receipt())),
            DispatchException.Reason.CLAIM_REQUIRED);
        assertRefused(() -> tasks.claim(SCOPE, s.address(), TaskCall.by(K)),
            DispatchException.Reason.TRANSITION_NOT_PERMITTED);
        assertThat(head.next()).as("open to the commissioner: withdraw and nothing else")
            .containsExactly(TaskVerb.WITHDRAW);

        TaskView closed = tasks.act(SCOPE, s.address(), TaskVerb.WITHDRAW,
            TaskCall.by(C).withConflictToken(head.conflictToken()));
        assertThat(closed.state()).isEqualTo(TaskState.CLOSED);
        assertThat(closed.outcome()).isEqualTo(Outcome.WITHDRAWN);
        TaskStage.Row row = TaskStage.row(s.identity());
        assertThat(row.holder()).as("the parked holder is dropped with the close").isNull();
        assertThat(row.leaseExpiresAt()).isNull();
    }

    @Test
    void the_second_lapse_still_opens_the_task_and_the_claim_after_it_counts_it() {
        TaskStage.Staged s = stage.open(SELECTOR);
        s = stage.lapse(stage.claimedBy(s, H));
        s = stage.lapse(stage.claimedBy(s, H));

        TaskView head = tasks.read(SCOPE, s.address(), K);
        assertThat(head.state()).as("two lapses: open again").isEqualTo(TaskState.OPEN);
        assertThat(head.next()).contains(TaskVerb.CLAIM);

        tasks.claim(SCOPE, s.address(), TaskCall.by(K));
        assertThat(TaskStage.row(s.identity()).lapseCount())
            .as("each take-up after a lapse records it").isEqualTo(2);
    }

    // =======================================================================
    // The lapse writes nothing; the former holder is told so
    // =======================================================================

    @Test
    void a_lapse_writes_nothing_and_the_former_holder_is_refused_lease_lapsed() {
        TaskStage.Staged s = stage.lapse(stage.active(SELECTOR));

        TaskStage.Row stored = TaskStage.row(s.identity());
        assertThat(stored.state()).as("the row still says active").isEqualTo("active");
        assertThat(stored.holder()).as("and names the holder").isEqualTo(H.subject());
        assertThat(tasks.read(SCOPE, s.address(), H).state())
            .as("but what holds is open").isEqualTo(TaskState.OPEN);

        assertRefused(() -> tasks.act(SCOPE, s.address(), TaskVerb.DELIVER,
                TaskCall.by(H).withReceipt(s.receipt())
                    .with(new TaskInput.Delivery("late", null))),
            DispatchException.Reason.LEASE_LAPSED);
        assertThat(TaskStage.texts(s.identity()))
            .as("and the late answer was not written")
            .noneMatch(t -> t.startsWith("return"));

        TaskClaim next = tasks.claim(SCOPE, s.address(), TaskCall.by(K));
        TaskStage.Row taken = TaskStage.row(s.identity());
        assertThat(taken.receiptHash())
            .as("the row holds the receipt's hash and never the receipt")
            .isEqualTo(Receipt.hash(next.receipt()))
            .isNotEqualTo(next.receipt());
        byte[] material = java.util.Base64.getUrlDecoder().decode(next.receipt());
        String decoded = new String(material, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThat(material).as("the receipt is 32 random bytes and nothing besides").hasSize(32);
        assertThat(List.of(next.receipt(), decoded))
            .as("carrying neither the subject nor the address")
            .noneMatch(form -> form.contains(K.subject()) || form.contains(s.address().toString()));
        assertThat(taken.holder()).as("the next executor writes the transition").isEqualTo(K.subject());
        assertThat(taken.lapseCount()).isEqualTo(1);
        assertThat(next.receipt()).isNotEqualTo(s.receipt());
        assertRefused(() -> tasks.act(SCOPE, s.address(), TaskVerb.RELEASE,
                TaskCall.by(H).withReceipt(s.receipt())),
            DispatchException.Reason.CLAIM_REQUIRED);
    }

    // =======================================================================
    // The lease: default, stated, renewed, restarted by an answer
    // =======================================================================

    @Test
    void a_claim_without_a_duration_holds_thirty_minutes_and_a_stated_one_holds_as_stated() {
        TaskStage.Staged a = stage.open(SELECTOR);
        TaskView standard = tasks.claim(SCOPE, a.address(), TaskCall.by(H)).task();
        assertThat(standard.leaseExpiresAt())
            .isCloseTo(Instant.now().plus(Duration.ofMinutes(30)), within(Duration.ofSeconds(30)));

        TaskStage.Staged b = stage.open(SELECTOR);
        TaskView week = tasks.claim(SCOPE, b.address(),
            TaskCall.by(H).with(new TaskInput.Lease(Duration.ofDays(7)))).task();
        assertThat(week.leaseExpiresAt()).as("no upper bound")
            .isCloseTo(Instant.now().plus(Duration.ofDays(7)), within(Duration.ofSeconds(30)));
        assertThat(tasks.read(SCOPE, b.address(), K).leaseExpiresAt())
            .as("the end of the lease is told to the holder only").isNull();
    }

    @Test
    void renew_extends_and_an_answer_restarts_thirty_minutes_whatever_the_claim_said() {
        TaskStage.Staged s = stage.open(SELECTOR);
        TaskClaim claim = tasks.claim(SCOPE, s.address(),
            TaskCall.by(H).with(new TaskInput.Lease(Duration.ofHours(5))));
        TaskView renewed = tasks.act(SCOPE, s.address(), TaskVerb.RENEW,
            TaskCall.by(H).withReceipt(claim.receipt())
                .with(new TaskInput.Lease(Duration.ofHours(9))));
        assertThat(renewed.leaseExpiresAt())
            .isCloseTo(Instant.now().plus(Duration.ofHours(9)), within(Duration.ofSeconds(30)));
        assertThat(TaskStage.row(s.identity()).state()).isEqualTo("active");

        tasks.act(SCOPE, s.address(), TaskVerb.ASK, TaskCall.by(H).withReceipt(claim.receipt())
            .with(new TaskInput.Question("left or right?", List.of("left", "right"), false)));
        assertThat(TaskStage.row(s.identity()).leaseExpiresAt())
            .as("the lease runs only in active").isNull();
        TaskView asked = tasks.read(SCOPE, s.address(), C);
        tasks.act(SCOPE, s.address(), TaskVerb.ANSWER, TaskCall.by(C)
            .withConflictToken(asked.conflictToken()).with(new TaskInput.Reply("left", null)));

        TaskView answered = tasks.read(SCOPE, s.address(), H);
        assertThat(answered.state()).isEqualTo(TaskState.ACTIVE);
        assertThat(answered.leaseExpiresAt())
            .as("the commissioner does not set the holder's time: thirty minutes")
            .isCloseTo(Instant.now().plus(Duration.ofMinutes(30)), within(Duration.ofSeconds(30)));
        assertThat(answered.questionOptions()).as("the question is answered").isNull();
        assertThat(TaskStage.texts(s.identity())).contains("question=left or right?", "answer=left");
    }

    // =======================================================================
    // The receipt fences every write of an executor
    // =======================================================================

    @Test
    void a_write_of_the_holder_without_its_receipt_is_refused_and_writes_nothing() {
        TaskStage.Staged s = stage.active(SELECTOR);

        assertRefused(() -> tasks.act(SCOPE, s.address(), TaskVerb.DELIVER,
                TaskCall.by(H).with(new TaskInput.Delivery("an answer", null))),
            DispatchException.Reason.RECEIPT_ABSENT);
        assertRefused(() -> tasks.act(SCOPE, s.address(), TaskVerb.DELIVER,
                TaskCall.by(H).withReceipt("forged")
                    .with(new TaskInput.Delivery("an answer", null))),
            DispatchException.Reason.RECEIPT_MISMATCH);

        assertThat(TaskStage.row(s.identity()).state()).isEqualTo("active");
        assertThat(TaskStage.texts(s.identity())).noneMatch(t -> t.startsWith("return"));
    }

    // =======================================================================
    // Defer
    // =======================================================================

    @Test
    void a_deferred_task_is_not_claimed_before_its_instant_and_is_after_it() {
        TaskStage.Staged s = stage.deferred(SELECTOR);
        assertThat(TaskStage.row(s.identity()).holder()).as("defer drops the holder").isNull();
        assertThat(tasks.read(SCOPE, s.address(), K).notBefore())
            .as("read answers the instant").isNotNull();

        assertRefused(() -> tasks.claim(SCOPE, s.address(), TaskCall.by(K)),
            DispatchException.Reason.DEFERRAL_PENDING);
        assertRefused(() -> tasks.claimNext(SCOPE, SELECTOR, List.of("code"), TaskCall.by(K)),
            DispatchException.Reason.NOTHING_TO_CLAIM);

        PlatformFixture.run("UPDATE dispatch.task SET not_before = now() - interval '1 second' "
            + "WHERE uuid = '" + s.identity() + "'");
        tasks.claim(SCOPE, s.address(), TaskCall.by(K));
        assertThat(TaskStage.row(s.identity()).notBefore())
            .as("the instant goes with the state it belongs to").isNull();
    }

    // =======================================================================
    // Criterion 5 -- the lock on acceptance
    // =======================================================================

    @Test
    void the_identity_that_delivered_cannot_accept_even_with_the_commissioning_capacity() {
        TaskStage.Staged s = stage.stage("delivered", SELECTOR);
        Actor delivererAsCommissioner = new Actor(H.subject(), Actor.Kind.CONSOLE);
        String token = stage.token(s);

        assertRefused(() -> tasks.act(SCOPE, s.address(), TaskVerb.ACCEPT,
                TaskCall.by(delivererAsCommissioner).withConflictToken(token)),
            DispatchException.Reason.RATIFICATION_NOT_PERMITTED);
        assertThat(TaskStage.row(s.identity()).state()).isEqualTo("delivered");

        TaskView accepted = tasks.act(SCOPE, s.address(), TaskVerb.ACCEPT,
            TaskCall.by(C).withConflictToken(token));
        assertThat(accepted.outcome()).isEqualTo(Outcome.ACCEPTED);
        assertThat(TaskStage.row(s.identity()).stateChangedBy())
            .as("who accepted is the one who made the last transition").isEqualTo(C.subject());
    }

    @Test
    void rework_sends_the_answer_back_and_the_second_delivery_is_the_valid_one() {
        TaskStage.Staged s = stage.stage("delivered", SELECTOR);
        tasks.act(SCOPE, s.address(), TaskVerb.REWORK, TaskCall.by(C)
            .withConflictToken(stage.token(s)).with(new TaskInput.RequiredRemark("shorter")));
        assertThat(tasks.read(SCOPE, s.address(), H).holder())
            .as("same holder").isEqualTo(HolderState.SELF);
        tasks.act(SCOPE, s.address(), TaskVerb.DELIVER, TaskCall.by(H).withReceipt(s.receipt())
            .with(new TaskInput.Delivery("the shorter answer", null)));

        TaskTextView answer = tasks.readText(SCOPE, s.address(), TextPart.RETURN, C);
        assertThat(answer.entries()).extracting(TaskTextView.Entry::text)
            .containsExactly("the shorter answer");
        TaskTextView thread = tasks.readText(SCOPE, s.address(), TextPart.THREAD, C);
        assertThat(thread.entries()).extracting(TaskTextView.Entry::text)
            .containsExactly("the answer", "shorter");
    }

    // -----------------------------------------------------------------------

    static void assertRefused(Runnable call, DispatchException.Reason reason) {
        assertThatThrownBy(call::run)
            .isInstanceOfSatisfying(DispatchException.class,
                e -> assertThat(e.reason()).isEqualTo(reason));
    }
}
