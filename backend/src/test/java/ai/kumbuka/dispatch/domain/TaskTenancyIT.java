package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import ai.kumbuka.dispatch.tenancy.TenantContext;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static ai.kumbuka.dispatch.domain.TaskHoldingIT.assertRefused;
import static ai.kumbuka.dispatch.domain.TaskStage.C;
import static ai.kumbuka.dispatch.domain.TaskStage.K;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance criterion 7: under the service role and a bound tenant, the task
 * kernel neither sees nor writes a task of another tenant.
 *
 * <p>Runs against the substrate database, whose runtime connection is the
 * service role with its enumerated grants and no BYPASSRLS. The two tenants
 * share the scope id and the selector name, so every address of one tenant
 * also exists in the other: nothing but the tenant keeps them apart. The draw
 * is the sharpest case: it is native SQL, which the ORM's tenant filter does
 * not reach, so only the row policy of {@code task} stands between it and the
 * other tenant's open task.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class TaskTenancyIT {

    static final UUID SCOPE = UUID.fromString("00000000-0000-0000-0000-000000000010");
    static final String SELECTOR = "sprint";

    @Inject TaskService tasks;
    @Inject TenantContext tenantContext;

    @Test
    void a_bound_tenant_neither_sees_nor_writes_another_tenants_task() throws Exception {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        DomainFixture.declareSelector(tenantA, SCOPE, SELECTOR);
        DomainFixture.declareSelector(tenantB, SCOPE, SELECTOR);
        TaskStage stage = new TaskStage(tasks, SCOPE);

        TaskStage.Staged ofA;
        try (AutoCloseable ignored = tenantContext.bind(tenantA)) {
            ofA = stage.open(SELECTOR);
        }

        try (AutoCloseable ignored = tenantContext.bind(tenantB)) {
            ExchangeAddress sameAddress = ofA.address();
            assertRefused(() -> tasks.read(SCOPE, sameAddress, C),
                DispatchException.Reason.NOT_FOUND);
            assertRefused(() -> tasks.readText(SCOPE, sameAddress, TextPart.DISPATCH, C),
                DispatchException.Reason.NOT_FOUND);
            assertRefused(() -> tasks.claim(SCOPE, sameAddress, TaskCall.by(K)),
                DispatchException.Reason.NOT_FOUND);
            assertRefused(() -> tasks.act(SCOPE, sameAddress, TaskVerb.WITHDRAW,
                    TaskCall.by(C).withConflictToken("any")),
                DispatchException.Reason.NOT_FOUND);
            assertRefused(() -> tasks.claimNext(SCOPE, SELECTOR, List.of("*e"), TaskCall.by(K)),
                DispatchException.Reason.NOTHING_TO_CLAIM);
            assertThat(tasks.query(SCOPE, SELECTOR,
                    TaskFilter.of(Map.of("address", "sprint/1.0")), 10, C).tasks())
                .as("a listing of B shows nothing of A").isEmpty();

            TaskView own = tasks.create(SCOPE, SELECTOR, null,
                new TaskService.Draft("B's own", "code", "B's text", null), C,
                IdempotencyKey.NONE);
            assertThat(own.address())
                .as("B numbers in its own selector: the same address as A's task")
                .isEqualTo(sameAddress);
            tasks.delete(SCOPE, own.address(), own.conflictToken(), C);
        }

        TaskStage.Row a = TaskStage.row(ofA.identity());
        assertThat(a.state()).as("A's task was not touched").isEqualTo("open");
        assertThat(TaskStage.texts(ofA.identity())).containsExactly("dispatch=the commission");
        try (AutoCloseable ignored = tenantContext.bind(tenantA)) {
            assertThat(tasks.read(SCOPE, ofA.address(), C).title()).isEqualTo("a staged task");
        }
    }
}
