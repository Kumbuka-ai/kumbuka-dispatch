package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import ai.kumbuka.dispatch.tenancy.TenantContext;
import io.quarkus.arc.Arc;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static ai.kumbuka.dispatch.domain.TaskStage.C;
import static ai.kumbuka.dispatch.domain.TaskStage.H;
import static ai.kumbuka.dispatch.domain.TaskStage.K;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The kernel decides on the row as it stands once locked, even where the same
 * transaction read the task before it took the lock.
 *
 * <p>The first transaction reads a task without a lock, which puts the task
 * into its persistence context. A second transaction changes the row and
 * commits. Only then does the first call a kernel operation that locks and
 * decides. Nothing here waits on a lock: the second transaction has ended
 * before the first takes one, so the order is fixed by the test and not by a
 * timing.
 *
 * <p>Red probe, observed: with the refresh under the lock taken out of {@code
 * TaskRepository}, the first case lets K take over a task H holds and the
 * second overwrites an accepted child as withdrawn.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class TaskLockedRowIT {

    static final UUID SCOPE = UUID.fromString("00000000-0000-0000-0000-000000000010");
    static final String SELECTOR = "sprint";

    @Inject TaskService tasks;
    @Inject TenantContext tenantContext;

    private UUID tenant;
    private AutoCloseable binding;
    private TaskStage stage;

    @BeforeEach
    void freshTenant() {
        tenant = UUID.randomUUID();
        DomainFixture.declareSelector(tenant, SCOPE, SELECTOR);
        binding = tenantContext.bind(tenant);
        stage = new TaskStage(tasks, SCOPE);
    }

    @AfterEach
    void unbind() throws Exception {
        binding.close();
    }

    @Test
    void a_claim_after_an_unlocked_read_decides_on_the_claim_another_transaction_committed()
            throws Exception {
        TaskStage.Staged open = stage.open(SELECTOR);

        DispatchException refused;
        QuarkusTransaction.begin();
        try {
            assertThat(tasks.read(SCOPE, open.address(), C).state()).isEqualTo(TaskState.OPEN);
            onItsOwnThread(() -> tasks.claim(SCOPE, open.address(), TaskCall.by(H)));

            refused = catchThrowableOfType(DispatchException.class, () ->
                tasks.claim(SCOPE, open.address(), TaskCall.by(K)));
        } finally {
            end();
        }

        assertThat(refused)
            .as("K's claim decided on the row H's claim left, not on the open task read before")
            .isNotNull();
        assertThat(refused.reason()).isEqualTo(DispatchException.Reason.TRANSITION_NOT_PERMITTED);
        assertThat(TaskStage.row(open.identity()).holder()).isEqualTo(H.subject());
    }

    @Test
    void a_root_closing_after_an_unlocked_read_of_its_child_keeps_the_child_accepted()
            throws Exception {
        TaskStage.Staged root = stage.open(SELECTOR);
        TaskStage.Staged delivered = stage.claimedBy(child(root), H);
        tasks.act(SCOPE, delivered.address(), TaskVerb.DELIVER, TaskCall.by(H)
            .withReceipt(delivered.receipt()).with(new TaskInput.Delivery("done", null)));
        String confirmation = catchThrowableOfType(DispatchException.class, () ->
                tasks.act(SCOPE, root.address(), TaskVerb.WITHDRAW,
                    TaskCall.by(C).withConflictToken(stage.token(root))))
            .confirmation().orElseThrow();
        String childToken = stage.token(delivered);
        String rootToken = stage.token(root);

        QuarkusTransaction.begin();
        try {
            assertThat(tasks.read(SCOPE, delivered.address(), C).state())
                .isEqualTo(TaskState.DELIVERED);
            onItsOwnThread(() -> tasks.act(SCOPE, delivered.address(), TaskVerb.ACCEPT,
                TaskCall.by(C).withConflictToken(childToken)));

            tasks.act(SCOPE, root.address(), TaskVerb.WITHDRAW,
                TaskCall.by(C).withConflictToken(rootToken).withConfirmation(confirmation));
        } finally {
            end();
        }

        TaskStage.Row child = TaskStage.row(delivered.identity());
        assertThat(child.state()).isEqualTo("closed");
        assertThat(child.outcome())
            .as("the closing of the root read the child under its lock and found it accepted")
            .isEqualTo("accepted");
        assertThat(TaskStage.row(root.identity()).state()).isEqualTo("closed");
    }

    // -----------------------------------------------------------------------

    /** Commits the first transaction, or rolls it back where a refusal marked it. */
    private static void end() {
        if (QuarkusTransaction.isRollbackOnly()) {
            QuarkusTransaction.rollback();
        } else {
            QuarkusTransaction.commit();
        }
    }

    /**
     * Runs one call to its end in a transaction of its own, on a thread with a
     * request context and tenant binding of its own, and fails on a refusal.
     */
    private void onItsOwnThread(Callable<?> call) throws Exception {
        ExecutorService thread = Executors.newSingleThreadExecutor();
        try {
            thread.submit(() -> {
                var request = Arc.container().requestContext();
                request.activate();
                try (AutoCloseable ignored = tenantContext.bind(tenant)) {
                    return call.call();
                } finally {
                    request.terminate();
                }
            }).get(30, TimeUnit.SECONDS);
        } finally {
            thread.shutdownNow();
        }
    }

    private TaskStage.Staged child(TaskStage.Staged root) {
        TaskView v = tasks.create(SCOPE, SELECTOR, root.address().number(),
            new TaskService.Draft("a child", "code", "its commission", null), C,
            IdempotencyKey.NONE);
        TaskStage.Staged child = new TaskStage.Staged(v.address(), v.identity(), SELECTOR, null);
        tasks.act(SCOPE, child.address(), TaskVerb.SEND,
            TaskCall.by(C).withConflictToken(stage.token(child)));
        return child;
    }
}
