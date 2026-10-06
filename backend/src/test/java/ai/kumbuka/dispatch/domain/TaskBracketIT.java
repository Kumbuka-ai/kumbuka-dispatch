package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import ai.kumbuka.dispatch.tenancy.TenantContext;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static ai.kumbuka.dispatch.domain.TaskStage.C;
import static ai.kumbuka.dispatch.domain.TaskStage.H;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Closing a bracket root with unfinished children (TAR-0004 section 3):
 * refused first with every unfinished child named and a confirmation handed
 * out; the same call with it withdraws the children and closes the root in one
 * transaction; a confirmation is void once the set of unfinished children
 * changed.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class TaskBracketIT {

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
    void closing_a_root_with_unfinished_children_needs_a_confirmation_and_then_withdraws_them() {
        TaskStage.Staged root = stage.open(SELECTOR);
        TaskStage.Staged running = stage.claimedBy(child(root), H);
        TaskStage.Staged finished = child(root);
        tasks.act(SCOPE, finished.address(), TaskVerb.WITHDRAW,
            TaskCall.by(C).withConflictToken(stage.token(finished)));

        DispatchException first = catchThrowableOfType(DispatchException.class, () ->
            tasks.act(SCOPE, root.address(), TaskVerb.WITHDRAW,
                TaskCall.by(C).withConflictToken(stage.token(root))));
        assertThat(first.reason()).isEqualTo(DispatchException.Reason.CONFIRMATION_REQUIRED);
        assertThat(first.offenders()).as("each unfinished child, and only those")
            .containsExactly(running.address() + " (active)");
        assertThat(first.confirmation()).as("and a confirmation to repeat the call with")
            .isPresent();
        assertThat(TaskStage.row(root.identity()).state()).as("nothing was written").isEqualTo("open");
        assertThat(TaskStage.row(running.identity()).state()).isEqualTo("active");

        TaskView closed = tasks.act(SCOPE, root.address(), TaskVerb.WITHDRAW,
            TaskCall.by(C).withConflictToken(stage.token(root))
                .withConfirmation(first.confirmation().orElseThrow()));
        assertThat(closed.state()).isEqualTo(TaskState.CLOSED);
        TaskStage.Row child = TaskStage.row(running.identity());
        assertThat(child.state()).isEqualTo("closed");
        assertThat(child.outcome()).as("the children close as withdrawn").isEqualTo("withdrawn");
        assertThat(child.holder()).isNull();
        assertThat(TaskStage.row(finished.identity()).outcome())
            .as("a finished child keeps its outcome").isEqualTo("withdrawn");
    }

    @Test
    void a_confirmation_is_void_once_the_set_of_unfinished_children_changed() {
        TaskStage.Staged root = stage.open(SELECTOR);
        TaskStage.Staged one = child(root);
        String confirmation = catchThrowableOfType(DispatchException.class, () ->
                tasks.act(SCOPE, root.address(), TaskVerb.WITHDRAW,
                    TaskCall.by(C).withConflictToken(stage.token(root))))
            .confirmation().orElseThrow();

        TaskStage.Staged added = child(root);

        DispatchException stale = catchThrowableOfType(DispatchException.class, () ->
            tasks.act(SCOPE, root.address(), TaskVerb.WITHDRAW, TaskCall.by(C)
                .withConflictToken(stage.token(root)).withConfirmation(confirmation)));
        assertThat(stale.reason()).isEqualTo(DispatchException.Reason.CONFIRMATION_STALE);
        assertThat(stale.offenders()).hasSize(2);
        assertThat(TaskStage.row(root.identity()).state()).isEqualTo("open");
        assertThat(TaskStage.row(one.identity()).state()).isEqualTo("open");
        assertThat(TaskStage.row(added.identity()).state()).isEqualTo("open");
    }

    @Test
    void a_confirmation_is_bound_to_the_verb_it_was_handed_out_for() {
        TaskStage.Staged root = stage.open(SELECTOR);
        child(root);
        String forWithdraw = catchThrowableOfType(DispatchException.class, () ->
                tasks.act(SCOPE, root.address(), TaskVerb.WITHDRAW,
                    TaskCall.by(C).withConflictToken(stage.token(root))))
            .confirmation().orElseThrow();

        DispatchException other = catchThrowableOfType(DispatchException.class, () ->
            tasks.act(SCOPE, root.address(), TaskVerb.REJECT, TaskCall.by(H)
                .withConfirmation(forWithdraw).with(new TaskPayload.RequiredRemark("no"))));
        assertThat(other.reason()).isEqualTo(DispatchException.Reason.CONFIRMATION_STALE);
    }

    @Test
    void a_root_whose_children_are_all_closed_closes_without_a_confirmation() {
        TaskStage.Staged root = stage.open(SELECTOR);
        TaskStage.Staged c = child(root);
        tasks.act(SCOPE, c.address(), TaskVerb.WITHDRAW,
            TaskCall.by(C).withConflictToken(stage.token(c)));

        TaskView closed = tasks.act(SCOPE, root.address(), TaskVerb.WITHDRAW,
            TaskCall.by(C).withConflictToken(stage.token(root)));
        assertThat(closed.state()).isEqualTo(TaskState.CLOSED);
    }

    @Test
    void a_closed_root_takes_no_new_child() {
        TaskStage.Staged root = stage.closed(SELECTOR);
        DispatchException refused = catchThrowableOfType(DispatchException.class, () ->
            tasks.create(SCOPE, SELECTOR, root.address().number(),
                new TaskService.Draft("late", "code", null, null), C, IdempotencyKey.NONE));
        assertThat(refused.reason()).isEqualTo(DispatchException.Reason.TRANSITION_NOT_PERMITTED);
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
