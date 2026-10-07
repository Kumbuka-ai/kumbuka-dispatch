package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import ai.kumbuka.dispatch.tenancy.TenantContext;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;

import static ai.kumbuka.dispatch.domain.TaskStage.C;
import static ai.kumbuka.dispatch.domain.TaskStage.H;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two calls of one caller under one idempotency key, at once.
 *
 * <p>The first is held open in the test thread while the second runs on a
 * thread of its own, with the same key and the same arguments. The first
 * commits only once the database reports the second waiting on a lock the
 * first holds ({@link LockWaits}). Each case then asserts that the second
 * answered what the first produced, as a repeat after the commit does, and
 * last that the second did wait.
 *
 * <p>Red probes, observed before the key lock existed: the overlapping
 * creation waited at the selector, then created a second root and pointed the
 * key at it; the overlapping draw stepped over the first's task, drew the
 * other one and failed on the unique key of {@code task_idempotency_key}.
 * The claim and the addendum were not run before the key lock and carry no
 * observed red state of their own.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class TaskKeyOverlapIT {

    static final UUID SCOPE = UUID.fromString("00000000-0000-0000-0000-000000000010");
    static final String SELECTOR = "sprint";
    static final List<String> CODE = List.of("code");

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
    void an_overlapping_creation_under_one_key_answers_the_first_root() throws Exception {
        int before = TaskStage.nextNumber(tenant, SCOPE, SELECTOR);
        IdempotencyKey key = IdempotencyKey.of("create-once");
        Callable<TaskView> create = () -> tasks.create(SCOPE, SELECTOR, null,
            new TaskService.Draft("once", "code", null, null), C, key);

        Overlap overlap = overlap(create, create);

        assertThat(overlap.second()).as("the second call answered, and was not refused")
            .isInstanceOf(TaskView.class);
        TaskView first = (TaskView) overlap.first();
        assertThat(((TaskView) overlap.second()).address()).isEqualTo(first.address());
        assertThat(TaskStage.nextNumber(tenant, SCOPE, SELECTOR)).as("one root was numbered")
            .isEqualTo(before + 1);
        assertThat(tasksUnder()).isEqualTo(1);
        assertThat(keyPointsAt("create-once")).as("the key names the one root")
            .isEqualTo(TaskStage.row(first.identity()).id());
        assertThat(overlap.waited()).as("the second waited on the first").isTrue();
    }

    @Test
    void an_overlapping_draw_under_one_key_answers_the_first_task_with_a_new_receipt()
            throws Exception {
        TaskStage.Staged drawn = stage.open(SELECTOR);
        TaskStage.Staged other = stage.open(SELECTOR);
        IdempotencyKey key = IdempotencyKey.of("draw-once");
        Callable<TaskClaim> draw = () -> tasks.claimNext(SCOPE, SELECTOR, CODE, TaskCall.by(H), key);

        Overlap overlap = overlap(draw, draw);

        assertThat(overlap.second()).as("the second call answered, and was not refused")
            .isInstanceOf(TaskClaim.class);
        TaskClaim first = (TaskClaim) overlap.first();
        TaskClaim second = (TaskClaim) overlap.second();
        assertThat(first.task().address()).isEqualTo(drawn.address());
        assertThat(second.task().address()).isEqualTo(drawn.address());
        assertThat(second.receipt()).as("a new receipt").isNotEqualTo(first.receipt());
        assertThat(TaskStage.row(other.identity()).state()).as("the other task was not drawn")
            .isEqualTo("open");
        assertThat(TaskStage.row(other.identity()).holder()).isNull();
        assertThat(overlap.waited()).as("the second waited on the first").isTrue();
    }

    @Test
    void an_overlapping_claim_under_one_key_answers_the_task_with_a_new_receipt()
            throws Exception {
        TaskStage.Staged open = stage.open(SELECTOR);
        IdempotencyKey key = IdempotencyKey.of("claim-once");
        Callable<TaskClaim> claim = () -> tasks.claim(SCOPE, open.address(), TaskCall.by(H), key);

        Overlap overlap = overlap(claim, claim);

        assertThat(overlap.second()).as("the second call answered, and was not refused")
            .isInstanceOf(TaskClaim.class);
        TaskClaim first = (TaskClaim) overlap.first();
        TaskClaim second = (TaskClaim) overlap.second();
        assertThat(second.task().address()).isEqualTo(open.address());
        assertThat(second.receipt()).as("a new receipt").isNotEqualTo(first.receipt());
        assertThat(TaskStage.row(open.identity()).state()).isEqualTo("active");
        assertThat(overlap.waited()).as("the second waited on the first").isTrue();
    }

    @Test
    void an_overlapping_addendum_under_one_key_is_attached_once() throws Exception {
        TaskStage.Staged open = stage.open(SELECTOR);
        IdempotencyKey key = IdempotencyKey.of("annotate-once");
        Callable<TaskView> annotate = () -> tasks.annotate(SCOPE, open.address(),
            TextType.DISPATCH, "one more thing", C, key);

        Overlap overlap = overlap(annotate, annotate);

        assertThat(overlap.second()).as("the second call answered, and was not refused")
            .isInstanceOf(TaskView.class);
        assertThat(((TaskView) overlap.second()).address()).isEqualTo(open.address());
        assertThat(TaskStage.texts(open.identity()))
            .containsExactly("dispatch=the commission", "dispatch[a]=one more thing");
        assertThat(overlap.waited()).as("the second waited on the first").isTrue();
    }

    // -----------------------------------------------------------------------

    private Overlap overlap(Callable<?> first, Callable<?> second) throws Exception {
        return new Overlap.Stage(em, tenantContext, tenant).run(first, second);
    }

    private int tasksUnder() {
        return Integer.parseInt(TaskStage.value("SELECT count(*) FROM dispatch.task "
            + "WHERE tenant_id = '" + tenant + "'"));
    }

    private long keyPointsAt(String key) {
        return Long.parseLong(TaskStage.value("SELECT task_id FROM dispatch.task_idempotency_key "
            + "WHERE tenant_id = '" + tenant + "' AND idempotency_key = '" + key + "'"));
    }
}
