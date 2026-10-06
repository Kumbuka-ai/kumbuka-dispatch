package ai.kumbuka.dispatch.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The effective state, concept section 2.1, row by row of its table.
 *
 * <p>The table is restated here as literals: stored {@code active} with a
 * running lease is {@code active}; with a lapsed lease and zero or one lapse
 * recorded it is {@code open} without a holder; with two or more recorded it
 * is {@code on_hold}, {@code external}, without a holder; every other stored
 * state is itself.
 */
class EffectiveStateTest {

    static final Instant NOW = Instant.parse("2026-10-06T08:00:00Z");

    @Test
    void a_running_lease_is_active_with_its_holder() {
        TaskSituation s = TaskSituation.of(active(NOW.plusSeconds(60), 5), NOW, List.of());
        assertThat(s.state()).isEqualTo(TaskState.ACTIVE);
        assertThat(s.holder()).isEqualTo("holder");
        assertThat(s.lapsed()).isFalse();
    }

    @Test
    void the_first_and_second_lapse_open_the_task_without_a_holder() {
        for (int recorded : new int[] {0, 1}) {
            TaskSituation s = TaskSituation.of(active(NOW.minusSeconds(1), recorded), NOW, List.of());
            assertThat(s.state()).as("%d lapse(s) recorded", recorded).isEqualTo(TaskState.OPEN);
            assertThat(s.holdReason()).isNull();
            assertThat(s.holder()).isNull();
            assertThat(s.storedHolder()).as("the row still names it").isEqualTo("holder");
        }
    }

    @Test
    void the_third_lapse_parks_the_task_without_a_holder() {
        for (int recorded : new int[] {2, 3, 7}) {
            TaskSituation s = TaskSituation.of(active(NOW.minusSeconds(1), recorded), NOW, List.of());
            assertThat(s.state()).as("%d lapse(s) recorded", recorded)
                .isEqualTo(TaskState.ON_HOLD);
            assertThat(s.holdReason()).isEqualTo(HoldReason.EXTERNAL);
            assertThat(s.holder()).isNull();
            assertThat(s.parked()).isTrue();
        }
    }

    @Test
    void a_lease_ending_exactly_now_has_lapsed() {
        assertThat(TaskSituation.of(active(NOW, 0), NOW, List.of()).state())
            .isEqualTo(TaskState.OPEN);
    }

    @Test
    void every_other_stored_state_is_itself() {
        Task task = task();
        task.enter(TaskState.ON_HOLD, HoldReason.DEPENDENCY, null, NOW, "holder");
        task.award("holder", "r", null);
        TaskSituation s = TaskSituation.of(task, NOW, List.of());
        assertThat(s.state()).isEqualTo(TaskState.ON_HOLD);
        assertThat(s.holdReason()).isEqualTo(HoldReason.DEPENDENCY);
        assertThat(s.holder()).as("a paused holder holds without a lease").isEqualTo("holder");
    }

    @Test
    void a_root_counts_its_unfinished_children_and_not_its_closed_ones() {
        Task root = task();
        Task running = task();
        running.sub = 1;
        running.enter(TaskState.ACTIVE, null, null, NOW, "x");
        running.award("x", "r", NOW.plusSeconds(60));
        Task done = task();
        done.sub = 2;
        done.enter(TaskState.CLOSED, null, Outcome.ACCEPTED, NOW, "x");
        TaskSituation s = TaskSituation.of(root, NOW, List.of(running, done));
        assertThat(s.unfinishedChildren()).extracting(TaskSituation.Child::address)
            .containsExactly(ExchangeAddress.child("sprint", 1, 1));
    }

    // -----------------------------------------------------------------------

    static Task active(Instant leaseEnd, int lapsesRecorded) {
        Task task = task();
        task.enter(TaskState.ACTIVE, null, null, NOW, "holder");
        task.award("holder", "receipt", leaseEnd);
        for (int i = 0; i < lapsesRecorded; i++) {
            task.recordLapse();
        }
        return task;
    }

    static Task task() {
        Selector selector = new Selector();
        selector.name = "sprint";
        Task task = new Task();
        task.uuid = UUID.randomUUID();
        task.selector = selector;
        task.number = 1;
        task.sub = 0;
        return task;
    }
}
