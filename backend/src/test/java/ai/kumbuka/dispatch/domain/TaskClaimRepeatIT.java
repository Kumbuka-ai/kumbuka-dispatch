package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import ai.kumbuka.dispatch.tenancy.TenantContext;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static ai.kumbuka.dispatch.domain.TaskStage.H;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * A claim repeated under its idempotency key (dispatch 200.7, part A2).
 *
 * <p>The repeat answers the same task with a new receipt; the earlier receipt
 * stops matching, because the service keeps only the hash and replaces it.
 * Nothing else moves: state, holder, lease end and lapse count stay, and a
 * repeated draw draws nothing. Where the caller no longer holds the task, the
 * repeat is refused with the reason the situation gives and takes nothing.
 *
 * <p>Red probe, observed: with {@code Task.reissue} not called, the earlier
 * receipt keeps matching and the first case turns red at its renewal.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class TaskClaimRepeatIT {

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

    @Test
    void a_repeated_claim_answers_the_same_task_with_a_new_receipt_and_moves_nothing() {
        TaskStage.Staged open = stage.open(SELECTOR);
        IdempotencyKey key = IdempotencyKey.of("claim-once");
        TaskCall call = TaskCall.by(H).with(new TaskInput.Lease(Duration.ofMinutes(20)));

        TaskClaim first = tasks.claim(SCOPE, open.address(), call, key);
        TaskStage.Row before = TaskStage.row(open.identity());

        TaskClaim again = tasks.claim(SCOPE, open.address(), call, key);
        TaskStage.Row after = TaskStage.row(open.identity());

        assertThat(again.task().address()).isEqualTo(first.task().address());
        assertThat(again.receipt()).as("a new receipt").isNotEqualTo(first.receipt());
        assertThat(after.state()).isEqualTo("active");
        assertThat(after.holder()).isEqualTo(before.holder());
        assertThat(after.leaseExpiresAt()).as("the lease end stays").isEqualTo(before.leaseExpiresAt());
        assertThat(after.lapseCount()).as("the count stays").isEqualTo(before.lapseCount());
        assertThat(after.receiptHash()).as("the stored hash is replaced")
            .isNotEqualTo(before.receiptHash());

        DispatchException earlier = catchThrowableOfType(DispatchException.class, () ->
            tasks.act(SCOPE, open.address(), TaskVerb.RENEW,
                TaskCall.by(H).withReceipt(first.receipt())));
        assertThat(earlier.reason()).as("the earlier receipt no longer holds")
            .isEqualTo(DispatchException.Reason.RECEIPT_MISMATCH);

        TaskView renewed = tasks.act(SCOPE, open.address(), TaskVerb.RENEW,
            TaskCall.by(H).withReceipt(again.receipt()));
        assertThat(renewed.state()).as("the new one does").isEqualTo(TaskState.ACTIVE);
    }

    @Test
    void a_repeated_draw_draws_nothing_second() {
        TaskStage.Staged first = stage.open(SELECTOR);
        TaskStage.Staged second = stage.open(SELECTOR);
        IdempotencyKey key = IdempotencyKey.of("draw-once");

        TaskClaim drawn = tasks.claimNext(SCOPE, SELECTOR, List.of("code"), TaskCall.by(H), key);
        TaskStage.Row before = TaskStage.row(first.identity());
        TaskClaim again = tasks.claimNext(SCOPE, SELECTOR, List.of("code"), TaskCall.by(H), key);

        assertThat(drawn.task().address()).isEqualTo(first.address());
        assertThat(again.task().address()).as("the same task").isEqualTo(first.address());
        assertThat(again.receipt()).isNotEqualTo(drawn.receipt());
        assertThat(TaskStage.row(second.identity()).state())
            .as("no second task is held").isEqualTo("open");
        assertThat(TaskStage.row(second.identity()).holder()).isNull();
        TaskStage.Row after = TaskStage.row(first.identity());
        assertThat(after.leaseExpiresAt()).isEqualTo(before.leaseExpiresAt());
        assertThat(after.lapseCount()).isEqualTo(before.lapseCount());
    }

    @Test
    void a_repeat_after_the_hold_ended_is_refused_and_takes_nothing() {
        TaskStage.Staged open = stage.open(SELECTOR);
        IdempotencyKey key = IdempotencyKey.of("released");
        TaskClaim first = tasks.claim(SCOPE, open.address(), TaskCall.by(H), key);
        tasks.act(SCOPE, open.address(), TaskVerb.RELEASE,
            TaskCall.by(H).withReceipt(first.receipt()));

        DispatchException refused = catchThrowableOfType(DispatchException.class, () ->
            tasks.claim(SCOPE, open.address(), TaskCall.by(H), key));

        assertThat(refused.reason()).isEqualTo(DispatchException.Reason.CLAIM_REQUIRED);
        TaskStage.Row row = TaskStage.row(open.identity());
        assertThat(row.state()).as("nothing was taken").isEqualTo("open");
        assertThat(row.holder()).isNull();
    }

    @Test
    void a_repeat_after_the_lease_lapsed_names_the_lapse_and_takes_nothing() {
        TaskStage.Staged open = stage.open(SELECTOR);
        IdempotencyKey key = IdempotencyKey.of("lapsed");
        TaskClaim first = tasks.claim(SCOPE, open.address(), TaskCall.by(H), key);
        stage.lapse(new TaskStage.Staged(open.address(), open.identity(), SELECTOR,
            first.receipt()));
        TaskStage.Row before = TaskStage.row(open.identity());

        DispatchException refused = catchThrowableOfType(DispatchException.class, () ->
            tasks.claim(SCOPE, open.address(), TaskCall.by(H), key));

        assertThat(refused.reason()).isEqualTo(DispatchException.Reason.LEASE_LAPSED);
        TaskStage.Row after = TaskStage.row(open.identity());
        assertThat(after.leaseExpiresAt()).as("nothing was taken")
            .isEqualTo(before.leaseExpiresAt());
        assertThat(after.lapseCount()).isEqualTo(before.lapseCount());
        assertThat(after.receiptHash()).isEqualTo(before.receiptHash());
    }

    @Test
    void the_same_key_on_another_call_or_other_arguments_is_refused() {
        TaskStage.Staged one = stage.open(SELECTOR);
        TaskStage.Staged other = stage.open(SELECTOR);
        IdempotencyKey key = IdempotencyKey.of("spent");
        tasks.claim(SCOPE, one.address(), TaskCall.by(H), key);

        DispatchException otherTask = catchThrowableOfType(DispatchException.class, () ->
            tasks.claim(SCOPE, other.address(), TaskCall.by(H), key));
        assertThat(otherTask.reason()).isEqualTo(DispatchException.Reason.IDEMPOTENCY_KEY_REUSED);
        assertThat(TaskStage.row(other.identity()).state()).isEqualTo("open");

        DispatchException otherLease = catchThrowableOfType(DispatchException.class, () ->
            tasks.claim(SCOPE, one.address(),
                TaskCall.by(H).with(new TaskInput.Lease(Duration.ofHours(2))), key));
        assertThat(otherLease.reason()).isEqualTo(DispatchException.Reason.IDEMPOTENCY_KEY_REUSED);

        DispatchException otherCall = catchThrowableOfType(DispatchException.class, () ->
            tasks.claimNext(SCOPE, SELECTOR, List.of("code"), TaskCall.by(H), key));
        assertThat(otherCall.reason()).isEqualTo(DispatchException.Reason.IDEMPOTENCY_KEY_REUSED);
        assertThat(TaskStage.row(other.identity()).state()).isEqualTo("open");

    }
}
