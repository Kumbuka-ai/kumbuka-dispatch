/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The effective state, concept section 2.1, row by row of its table.
 *
 * <p>The table is restated here as literals: stored {@code active} with a
 * running lease is {@code active}; with a lapsed lease and zero or one lapse
 * recorded it is {@code open} without a holder; with two or more recorded it
 * is {@code on_hold}, {@code external}, without a holder; every other stored
 * state is itself.
 */
@Disabled(EffectiveStateTest.REBUILT)
class EffectiveStateTest {

    static final String REBUILT = "rebuilt in T5: a lapsed lease opens the task without a holder "
        + "and the third lapse parks it on hold";

    @Test
    void a_running_lease_is_active_with_its_holder() {
        fail(REBUILT);
    }

    @Test
    void the_first_and_second_lapse_open_the_task_without_a_holder() {
        fail(REBUILT);
    }

    @Test
    void the_third_lapse_parks_the_task_without_a_holder() {
        fail(REBUILT);
    }

    @Test
    void a_lease_ending_exactly_now_has_lapsed() {
        fail(REBUILT);
    }

    @Test
    void every_other_stored_state_is_itself() {
        fail(REBUILT);
    }

    @Test
    void a_root_counts_its_unfinished_children_and_not_its_closed_ones() {
        fail(REBUILT);
    }

    /**
     * There is no write that takes a state: the entity's public methods read,
     * and the one that enters a state is the kernel's own, after a decision.
     */
    @Test
    void the_task_offers_no_public_write_of_its_state() {
        fail(REBUILT);
    }
}
