package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import ai.kumbuka.dispatch.tenancy.TenantContext;
import io.quarkus.arc.Arc;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static ai.kumbuka.dispatch.domain.TaskStage.C;
import static ai.kumbuka.dispatch.domain.TaskStage.H;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The locks of a bracket under concurrency.
 *
 * <p>Two transactions meet in each case. The first is held open in the test
 * thread while the second runs on a thread of its own. The first commits only
 * once the database reports the second waiting on a lock the first holds
 * ({@link LockWaits}), so the overlap is established and not hoped for; no
 * fixed time decides it. Each case then asserts what the bracket holds once
 * both are done, which is the statement the locks exist for, and last that
 * the second did wait.
 *
 * <p>Red probes, observed: with {@code TaskService.requireOpenBracket} reading
 * the root without a lock, the first case ends with a closed root and a draft
 * child; with {@code TaskRepository.lockChildren} reading without a lock, the
 * second ends with the accepted child overwritten as withdrawn; with {@code
 * TaskRepository.lockSelector} answering the selector the creation read before
 * its lock, the second creation of the third case takes the first's number and
 * fails on the unique address of the task table.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class TaskBracketLockIT {

    static final UUID SCOPE = UUID.fromString("00000000-0000-0000-0000-000000000010");
    static final String SELECTOR = "sprint";

    @Inject TaskService tasks;
    @Inject EntityManager em;
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
    void a_child_created_while_its_root_closes_never_stays_unfinished_under_a_closed_root()
            throws Exception {
        TaskStage.Staged root = stage.open(SELECTOR);
        child(root);
        String confirmation = confirmationToWithdraw(root);

        ExecutorService thread = Executors.newSingleThreadExecutor();
        try {
            QuarkusTransaction.begin();
            Future<Object> creating;
            boolean waited;
            try {
                tasks.act(SCOPE, root.address(), TaskVerb.WITHDRAW, TaskCall.by(C)
                    .withConflictToken(stage.token(root)).withConfirmation(confirmation));
                int first = LockWaits.sessionOf(em);
                creating = thread.submit(onItsOwn(() -> tasks.create(SCOPE, SELECTOR,
                    root.address().number(), new TaskService.Draft("late", "code", null, null),
                    C, IdempotencyKey.NONE)));
                waited = LockWaits.waitsOn(first, creating);
            } finally {
                QuarkusTransaction.commit();
            }
            Object created = creating.get(30, TimeUnit.SECONDS);

            assertThat(TaskStage.row(root.identity()).state()).isEqualTo("closed");
            assertThat(TaskStage.unfinishedChildren(root.identity()))
                .as("a closed root has only closed children: the child that was being "
                    + "created waited for the root and found it closed")
                .isEmpty();
            assertThat(created).isInstanceOfSatisfying(DispatchException.class, refused ->
                assertThat(refused.reason())
                    .isEqualTo(DispatchException.Reason.TRANSITION_NOT_PERMITTED));
            assertThat(waited).as("the creation waited at the root the closing held").isTrue();
        } finally {
            thread.shutdownNow();
        }
    }

    @Test
    void a_child_accepted_by_a_second_transaction_before_the_root_closes_keeps_accepted()
            throws Exception {
        TaskStage.Staged root = stage.open(SELECTOR);
        TaskStage.Staged delivered = stage.claimedBy(child(root), H);
        tasks.act(SCOPE, delivered.address(), TaskVerb.DELIVER, TaskCall.by(H)
            .withReceipt(delivered.receipt()).with(new TaskInput.Delivery("done", null)));
        String confirmation = confirmationToWithdraw(root);

        ExecutorService thread = Executors.newSingleThreadExecutor();
        try {
            QuarkusTransaction.begin();
            Future<Object> closing;
            boolean waited;
            try {
                tasks.act(SCOPE, delivered.address(), TaskVerb.ACCEPT,
                    TaskCall.by(C).withConflictToken(stage.token(delivered)));
                int first = LockWaits.sessionOf(em);
                closing = thread.submit(onItsOwn(() -> tasks.act(SCOPE, root.address(),
                    TaskVerb.WITHDRAW, TaskCall.by(C).withConflictToken(stage.token(root))
                        .withConfirmation(confirmation))));
                waited = LockWaits.waitsOn(first, closing);
            } finally {
                QuarkusTransaction.commit();
            }
            closing.get(30, TimeUnit.SECONDS);

            TaskStage.Row child = TaskStage.row(delivered.identity());
            assertThat(child.state()).isEqualTo("closed");
            assertThat(child.outcome())
                .as("the closing of the root read the child under its lock, after the "
                    + "acceptance committed, and did not overwrite it")
                .isEqualTo("accepted");
            assertThat(TaskStage.unfinishedChildren(root.identity())).isEmpty();
            assertThat(waited).as("the closing waited at the child the acceptance held").isTrue();
        } finally {
            thread.shutdownNow();
        }
    }

    @Test
    void two_roots_created_at_once_take_consecutive_numbers() throws Exception {
        int before = TaskStage.nextNumber(tenant, SCOPE, SELECTOR);

        ExecutorService thread = Executors.newSingleThreadExecutor();
        try {
            QuarkusTransaction.begin();
            TaskView first;
            Future<Object> creating;
            boolean waited;
            try {
                first = tasks.create(SCOPE, SELECTOR, null,
                    new TaskService.Draft("first", "code", null, null), C, IdempotencyKey.NONE);
                int holder = LockWaits.sessionOf(em);
                creating = thread.submit(onItsOwn(() -> tasks.create(SCOPE, SELECTOR, null,
                    new TaskService.Draft("second", "code", null, null), C,
                    IdempotencyKey.NONE)));
                waited = LockWaits.waitsOn(holder, creating);
            } finally {
                QuarkusTransaction.commit();
            }
            Object second = creating.get(30, TimeUnit.SECONDS);

            assertThat(second)
                .as("the second creation read the selector before it waited for its lock, "
                    + "and took its number from the row under the lock")
                .isInstanceOf(TaskView.class);
            assertThat(first.address().number()).isEqualTo(before);
            assertThat(((TaskView) second).address().number()).isEqualTo(before + 1);
            assertThat(TaskStage.nextNumber(tenant, SCOPE, SELECTOR)).isEqualTo(before + 2);
            assertThat(waited).as("the second creation waited at the selector the first held")
                .isTrue();
        } finally {
            thread.shutdownNow();
        }
    }

    // -----------------------------------------------------------------------

    private String confirmationToWithdraw(TaskStage.Staged root) {
        return catchThrowableOfType(DispatchException.class, () ->
                tasks.act(SCOPE, root.address(), TaskVerb.WITHDRAW,
                    TaskCall.by(C).withConflictToken(stage.token(root))))
            .confirmation().orElseThrow();
    }

    /**
     * One call on its own thread, with a request context and tenant binding of
     * its own; answers what it returned, or the refusal it raised.
     */
    private Callable<Object> onItsOwn(Callable<?> call) {
        return () -> {
            var request = Arc.container().requestContext();
            request.activate();
            try (AutoCloseable ignored = tenantContext.bind(tenant)) {
                return call.call();
            } catch (DispatchException refused) {
                return refused;
            } finally {
                request.terminate();
            }
        };
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
