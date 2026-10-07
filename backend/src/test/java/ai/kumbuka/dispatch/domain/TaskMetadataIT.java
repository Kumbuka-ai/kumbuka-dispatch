package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import ai.kumbuka.dispatch.tenancy.TenantContext;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static ai.kumbuka.dispatch.domain.TaskStage.C;
import static ai.kumbuka.dispatch.domain.TaskStage.H;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * The cardinality of metadata on a task, through the kernel and back out of a
 * real database: a list stays a list and a string stays a string, on the
 * commissioner's metadata and on the executor's, and a value the rules refuse
 * is refused before anything is written.
 *
 * <p>The successor of the exchange's {@code MetadataCardinalityIT}: the rules
 * are {@link Metadata#validate}'s and did not change; what changed is the
 * store they are proven against.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class TaskMetadataIT {

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
    void a_list_returns_as_the_list_and_a_string_as_the_string() {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("tracks", List.of("backend", "frontend"));
        metadata.put("pull-request", "17");
        TaskView created = tasks.create(SCOPE, SELECTOR, null,
            new TaskService.Draft("with metadata", "code", null, metadata), C,
            IdempotencyKey.NONE);

        TaskView read = tasks.read(SCOPE, created.address(), C);

        assertThat(read.dispatchMetadata().get("tracks"))
            .isEqualTo(List.of("backend", "frontend"));
        assertThat(read.dispatchMetadata().get("pull-request"))
            .as("a single value is carried as a string, not wrapped into a list")
            .isEqualTo("17");
    }

    @Test
    void the_executor_s_metadata_keeps_its_shape_too() {
        TaskStage.Staged active = stage.active(SELECTOR);
        tasks.act(SCOPE, active.address(), TaskVerb.DELIVER, TaskCall.by(H)
            .withReceipt(active.receipt())
            .with(new TaskInput.Delivery("done", Map.of("commits", List.of("a1", "b2")))));

        assertThat(tasks.read(SCOPE, active.address(), C).returnMetadata().get("commits"))
            .isEqualTo(List.of("a1", "b2"));
    }

    @Test
    void a_value_the_rules_refuse_is_refused_and_nothing_is_written() {
        List<Map<String, Object>> refused = List.of(
            Map.of("tracks", List.of("short", "x".repeat(Metadata.MAX_VALUE_LENGTH + 1))),
            Map.of("link", List.of("https://user:secret@example.org/x")),
            Map.of("tracks", Arrays.asList("a", null)),
            Map.of("nested", Map.of("a", "b")));
        for (Map<String, Object> metadata : refused) {
            int before = TaskStage.rowCount();
            DispatchException e = catchThrowableOfType(DispatchException.class, () ->
                tasks.create(SCOPE, SELECTOR, null,
                    new TaskService.Draft("refused", "code", null, metadata), C,
                    IdempotencyKey.NONE));
            assertThat(e).as("%s", metadata).isNotNull();
            assertThat(e.reason()).isEqualTo(DispatchException.Reason.METADATA_REFUSED);
            assertThat(TaskStage.rowCount()).as("nothing was written").isEqualTo(before);
        }
    }
}
