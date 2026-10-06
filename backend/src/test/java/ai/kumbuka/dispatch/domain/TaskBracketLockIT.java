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
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static ai.kumbuka.dispatch.domain.TaskStage.C;
import static ai.kumbuka.dispatch.domain.TaskStage.H;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The locks of a bracket under concurrency (dispatch 200.7, part A1).
 *
 * <p>Two transactions meet in each case. The first is held open in the test
 * thread while the second runs on a thread of its own, so the second reaches
 * its locks while the first has not committed. It is given
 * {@link #WHILE_THE_FIRST_IS_OPEN} to get as far as it can, then the first
 * commits and the second is awaited. Neither case asserts that the second
 * blocked: they assert what the bracket holds once both are done, which is the
 * statement the locks exist for.
 *
 * <p>Red probes, observed: with {@code TaskService.requireOpenBracket} reading
 * the root without a lock, the first case ends with a closed root and a draft
 * child; with {@code TaskRepository.lockChildren} reading without a lock, the
 * second ends with the accepted child overwritten as withdrawn.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class TaskBracketLockIT {

    static final UUID SCOPE = UUID.fromString("00000000-0000-0000-0000-000000000010");
    static final String SELECTOR = "sprint";

    /** How long the second transaction is given while the first stays open. */
    private static final long WHILE_THE_FIRST_IS_OPEN = 1500;

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
    void a_child_created_while_its_root_closes_never_stays_unfinished_under_a_closed_root()
            throws Exception {
        TaskStage.Staged root = stage.open(SELECTOR);
        child(root);
        String confirmation = confirmationToWithdraw(root);

        ExecutorService thread = Executors.newSingleThreadExecutor();
        try {
            QuarkusTransaction.begin();
            Future<Object> creating;
            try {
                tasks.act(SCOPE, root.address(), TaskVerb.WITHDRAW, TaskCall.by(C)
                    .withConflictToken(stage.token(root)).withConfirmation(confirmation));
                creating = thread.submit(onItsOwn(() -> tasks.create(SCOPE, SELECTOR,
                    root.address().number(), new TaskService.Draft("late", "code", null, null),
                    C, IdempotencyKey.NONE)));
                awaitAWhile(creating);
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
            try {
                tasks.act(SCOPE, delivered.address(), TaskVerb.ACCEPT,
                    TaskCall.by(C).withConflictToken(stage.token(delivered)));
                closing = thread.submit(onItsOwn(() -> tasks.act(SCOPE, root.address(),
                    TaskVerb.WITHDRAW, TaskCall.by(C).withConflictToken(stage.token(root))
                        .withConfirmation(confirmation))));
                awaitAWhile(closing);
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

    /** Gives the second transaction time to reach its locks; it may or may not finish. */
    private static void awaitAWhile(Future<Object> second) throws Exception {
        try {
            second.get(WHILE_THE_FIRST_IS_OPEN, TimeUnit.MILLISECONDS);
        } catch (TimeoutException waiting) {
            // Waiting on a lock the first transaction holds: the case of a fix in place.
        }
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
