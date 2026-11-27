/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The effective state of a task, the derivation of the order row by row, with
 * literals: the stored state, the holdings and the instant of the check go in,
 * the state a reader is told comes out (ADR-0002, C11.2, data model A.5).
 *
 * <p>The rows, as the order states them and written here from it:
 * <ul>
 *   <li>the youngest holding no act ended has lapsed where the task is stored
 *       {@code active} and the end of its youngest lease is not after the
 *       instant of the check -- an end exactly at that instant has lapsed;
 *       every older holding no act ended has lapsed;</li>
 *   <li>the number of lapses is the number of lapsed holdings;</li>
 *   <li>stored {@code active} with its youngest holding lapsed is {@code open}
 *       without a holder at one or two lapses, and from the third on {@code
 *       on_hold} for {@code external}, parked, without a holder; every other
 *       stored state is itself, a stored parked hold included.</li>
 * </ul>
 *
 * <p>The case of the old table on a bracket root, which counted its unfinished
 * children, has no subject in the new model: a task has no children. The rule
 * it held, that a closing does not finish what is unfinished under it unseen,
 * is held by the confirmation of {@code conclude} ({@code ConfirmationTest},
 * {@code InstanceServiceModuleTest}).
 */
class EffectiveStateTest {

    private static final Instant NOW = Instant.parse("2026-11-27T12:00:00.000001Z");
    private static final Instant LATER = Instant.parse("2026-11-27T12:30:00Z");
    private static final Instant EARLIER = Instant.parse("2026-11-27T11:30:00Z");

    private static final EffectiveState.Line RUNNING = new EffectiveState.Line(false, LATER);
    private static final EffectiveState.Line LAPSED = new EffectiveState.Line(false, EARLIER);
    private static final EffectiveState.Line RELEASED = new EffectiveState.Line(true, EARLIER);

    @Test
    void a_running_lease_is_active_with_its_holder() {
        EffectiveState state = EffectiveState.of("active", null, false, List.of(RUNNING), NOW);

        assertThat(state.state()).isEqualTo("active");
        assertThat(state.held()).as("its holder holds it").isTrue();
        assertThat(state.lapses()).isZero();
        assertThat(state.parked()).isFalse();
    }

    @Test
    void the_first_and_second_lapse_open_the_task_without_a_holder() {
        EffectiveState first = EffectiveState.of("active", null, false, List.of(LAPSED), NOW);
        EffectiveState second = EffectiveState.of("active", null, false,
            List.of(new EffectiveState.Line(false, null), LAPSED), NOW);

        assertThat(List.of(first.state(), first.lapses(), first.held()))
            .as("one lapse: open, without a holder").containsExactly("open", 1, false);
        assertThat(List.of(second.state(), second.lapses(), second.held()))
            .as("two lapses, the older holding lapsed since a later claim followed it")
            .containsExactly("open", 2, false);
        assertThat(second.youngest()).isEqualTo(EffectiveState.Youngest.LAPSED);
        assertThat(second.holdReason()).isNull();
    }

    @Test
    void the_third_lapse_parks_the_task_without_a_holder() {
        EffectiveState state = EffectiveState.of("active", null, false,
            List.of(LAPSED, LAPSED, LAPSED), NOW);

        assertThat(List.of(state.state(), String.valueOf(state.holdReason()), state.parked(),
                state.lapses(), state.held()))
            .containsExactly("on_hold", "external", true, 3, false);
    }

    @Test
    void the_third_holding_running_is_active_and_counts_two_lapses() {
        EffectiveState state = EffectiveState.of("active", null, false,
            List.of(LAPSED, LAPSED, RUNNING), NOW);

        assertThat(List.of(state.state(), state.lapses(), state.held()))
            .containsExactly("active", 2, true);
    }

    @Test
    void a_lease_ending_exactly_now_has_lapsed() {
        EffectiveState state = EffectiveState.of("active", null, false,
            List.of(new EffectiveState.Line(false, NOW)), NOW);
        EffectiveState aMicrosecondBefore = EffectiveState.of("active", null, false,
            List.of(new EffectiveState.Line(false, NOW)),
            Instant.parse("2026-11-27T12:00:00Z"));

        assertThat(List.of(state.state(), state.lapses())).containsExactly("open", 1);
        assertThat(aMicrosecondBefore.held())
            .as("one microsecond before its end the lease runs").isTrue();
    }

    @Test
    void a_holding_without_a_lease_on_an_active_task_has_lapsed() {
        EffectiveState state = EffectiveState.of("active", null, false,
            List.of(new EffectiveState.Line(false, null)), NOW);

        assertThat(List.of(state.state(), state.lapses())).containsExactly("open", 1);
    }

    @Test
    void a_holding_an_act_ended_is_no_lapse() {
        EffectiveState released = EffectiveState.of("open", null, false, List.of(RELEASED), NOW);
        EffectiveState afterTwoReleases = EffectiveState.of("active", null, false,
            List.of(RELEASED, RELEASED, RUNNING), NOW);

        assertThat(List.of(released.state(), released.lapses(), released.held()))
            .containsExactly("open", 0, false);
        assertThat(released.youngest()).isEqualTo(EffectiveState.Youngest.NONE);
        assertThat(List.of(afterTwoReleases.state(), afterTwoReleases.lapses()))
            .containsExactly("active", 0);
    }

    @Test
    void an_open_holding_of_a_task_not_stored_active_with_a_past_lease_end_is_no_lapse() {
        EffectiveState state = EffectiveState.of("on_hold", "question", false, List.of(LAPSED),
            NOW);

        assertThat(List.of(state.state(), String.valueOf(state.holdReason()), state.lapses(),
                state.held()))
            .as("a lease runs only while the task is active, so none lapses on hold")
            .containsExactly("on_hold", "question", 0, false);
        assertThat(state.youngest()).isEqualTo(EffectiveState.Youngest.NONE);
    }

    @Test
    void every_other_stored_state_is_itself() {
        for (String stored : List.of("draft", "open", "delivered", "closed")) {
            EffectiveState state = EffectiveState.of(stored, null, false, List.of(), NOW);
            assertThat(List.of(state.state(), state.lapses(), state.held()))
                .as("stored %s", stored).containsExactly(stored, 0, false);
        }
        EffectiveState parked = EffectiveState.of("on_hold", "external", true, List.of(), NOW);
        assertThat(List.of(parked.state(), String.valueOf(parked.holdReason()), parked.parked()))
            .as("a parked hold the copy stored is itself").containsExactly("on_hold", "external",
                true);
        EffectiveState activeWithoutHolding = EffectiveState.of("active", null, false, List.of(),
            NOW);
        assertThat(List.of(activeWithoutHolding.state(), activeWithoutHolding.held()))
            .as("stored active without a holding stays active, held by nobody")
            .containsExactly("active", false);
    }

    @Test
    void the_task_offers_no_public_write_of_its_state() {
        List<String> writes = Arrays.stream(Task.class.getDeclaredMethods())
            .filter(method -> Modifier.isPublic(method.getModifiers()))
            .filter(method -> method.getReturnType() == void.class)
            .map(Method::getName)
            .toList();

        assertThat(writes)
            .as("the state of a task changes by the acts of the kernel alone, never by a "
                + "public setter a lapse could be written back through")
            .isEmpty();
    }
}
