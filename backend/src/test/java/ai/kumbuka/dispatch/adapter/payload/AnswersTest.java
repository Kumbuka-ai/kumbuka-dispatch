package ai.kumbuka.dispatch.adapter.payload;

import ai.kumbuka.dispatch.domain.ExchangeAddress;
import ai.kumbuka.dispatch.domain.HolderState;
import ai.kumbuka.dispatch.domain.TaskState;
import ai.kumbuka.dispatch.domain.TaskView;
import ai.kumbuka.dispatch.surface.CallRouter;
import ai.kumbuka.dispatch.surface.ProcessVerb;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An instant in an answer is rendered as the database holds it.
 *
 * <p>Measured on the CI runner, 2026-10-06: the answer to a claim carried the
 * lease end from the clock, {@code …42.538822988Z}, while every later read
 * carried the stored {@code …42.538823Z}, so a repeated claim looked as if it
 * had moved the lease. A clock with microsecond resolution, as on macOS, hides
 * the difference; this case does not depend on the clock.
 */
class AnswersTest {

    @Test
    @SuppressWarnings("unchecked")
    void an_instant_is_rendered_to_the_microsecond_as_it_is_stored() {
        Instant fromTheClock = Instant.parse("2026-10-06T11:05:42.538822988Z");
        TaskView held = new TaskView(UUID.randomUUID(), new ExchangeAddress("sprint", 7, 0),
            TaskState.ACTIVE, null, null, HolderState.SELF, fromTheClock, fromTheClock, null, 0,
            "t", "code", null, null, "token", null, List.of(), List.of());

        Map<String, Object> answer = Answers.answer(new CallRouter.Answered(ProcessVerb.CLAIM,
            "dispatch://probe/sprint/7.0", held, false, List.of(), null, "receipt"));

        Map<String, Object> fields = (Map<String, Object>) answer.get("fields");
        assertThat(fields.get("lease_expires_at")).isEqualTo("2026-10-06T11:05:42.538822Z");
        assertThat(fields.get("not_before")).isEqualTo("2026-10-06T11:05:42.538822Z");
    }
}
