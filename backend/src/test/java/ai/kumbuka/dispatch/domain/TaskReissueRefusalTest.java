package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.repository.TaskRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A repeated claim whose key names a task the scope no longer holds.
 *
 * <p>No path of the database reaches this: the key's entry refers to its task
 * and is deleted with a draft. The kernel still refuses it, and the refusal
 * says what is missing rather than naming an address it does not have.
 */
class TaskReissueRefusalTest {

    private static final UUID SCOPE = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final Instant NOW = Instant.parse("2026-10-07T09:00:00Z");
    private static final ExchangeAddress ADDRESS = new ExchangeAddress("sprint", 7, 0);

    @Test
    void a_key_whose_task_is_gone_is_refused_as_not_found_without_a_null_address() {
        TaskService kernel = new TaskService(Clock.fixed(NOW, ZoneOffset.UTC));
        kernel.tasks = mock(TaskRepository.class);
        SpentTaskKey spent = new SpentTaskKey();
        spent.taskId = 42L;
        spent.callName = TaskService.CLAIM;
        spent.argumentDigest = IdempotencyService.digestOf(
            Arrays.asList(ADDRESS.toString(), "default"));
        spent.firstSeenAt = NOW.minusSeconds(60);
        when(kernel.tasks.lockKey(any(), anyString(), anyString())).thenReturn(Optional.of(spent));
        when(kernel.tasks.lockById(any(), anyLong())).thenReturn(Optional.empty());

        DispatchException refused = catchThrowableOfType(DispatchException.class, () ->
            kernel.claim(SCOPE, ADDRESS, TaskCall.by(TaskStage.H), IdempotencyKey.of("gone")));

        assertThat(refused.reason()).isEqualTo(DispatchException.Reason.NOT_FOUND);
        assertThat(refused.getMessage()).doesNotContain("null");
    }
}
