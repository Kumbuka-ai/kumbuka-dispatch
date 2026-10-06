package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.platform.PlatformFixture;
import ai.kumbuka.dispatch.repository.ScopeAccessRepository;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import ai.kumbuka.dispatch.tenancy.TenantContext;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static ai.kumbuka.dispatch.domain.TaskStage.C;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The rendering of {@code curated_in} in a head: the target's complete
 * address, and only where the caller may see the target's scope.
 *
 * <p>The rule of the running service, carried unchanged: the slug comes from
 * the subject-filtered directory view. The verb surface binds the caller's
 * subject in the transaction that calls the kernel; this test does the same,
 * on the tenant and subject the substrate publishes a directory for.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class TaskCurationIT {

    static final UUID SCOPE = UUID.fromString(SubstrateDatabaseResource.SCOPE_ID);
    static final UUID TENANT = UUID.fromString(SubstrateDatabaseResource.TENANT_ID);

    /** Its own selector, so this test's numbers are its own in the shared tenant. */
    static final String SELECTOR = "curation-probe";

    @Inject TaskService tasks;
    @Inject ScopeAccessRepository scopes;
    @Inject TenantContext tenantContext;

    @Test
    void a_curation_target_is_named_to_a_caller_who_sees_its_scope_and_to_no_other()
            throws Exception {
        PlatformFixture.grantDirectoryAccess();
        DomainFixture.declareSelector(TENANT, SCOPE, SELECTOR);
        try (AutoCloseable ignored = tenantContext.bind(TENANT)) {
            TaskStage stage = new TaskStage(tasks, SCOPE);
            TaskStage.Staged target = stage.open(SELECTOR);
            TaskStage.Staged closed = stage.closed(SELECTOR);
            tasks.relate(SCOPE, closed.address(), SCOPE, target.address(), stage.token(closed), C);

            assertThat(readAs(SubstrateDatabaseResource.PROBE_SUBJECT, closed).curatedIn())
                .isEqualTo(target.address().complete(SubstrateDatabaseResource.PROBE_SCOPE_SLUG));
            assertThat(readAs("no-member-of-this-tenant", closed).curatedIn())
                .as("a caller the directory shows no scope to is not told the address")
                .isNull();
        }
    }

    private TaskView readAs(String subject, TaskStage.Staged s) {
        return QuarkusTransaction.requiringNew().call(() -> {
            scopes.bindSubject(subject);
            return tasks.read(SCOPE, s.address(), C);
        });
    }
}
