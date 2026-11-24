/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The confirmation that closes an instance with unfinished tasks is computed
 * from the set of those tasks (TAR-0004 2.3, REA-0010 S5): the same set gives
 * the same one, whatever its order, and another set, another instance or
 * another closing gives another.
 */
class ConfirmationTest {

    @Test
    void the_same_set_gives_the_same_confirmation_in_any_order() {
        assertThat(Confirmation.of(Act.Kind.CONCLUDE, 5L, List.of(3L, 1L, 2L)))
            .as("computed from the set, stored nowhere: the order of the tasks is no part of it")
            .isEqualTo(Confirmation.of(Act.Kind.CONCLUDE, 5L, List.of(1L, 2L, 3L)))
            .matches("[A-Za-z0-9_-]{43}");
    }

    @Test
    void another_set_another_instance_or_another_closing_gives_another_confirmation() {
        String handedOut = Confirmation.of(Act.Kind.CONCLUDE, 5L, List.of(1L, 2L));
        assertThat(List.of(
                Confirmation.of(Act.Kind.CONCLUDE, 5L, List.of(1L, 2L, 3L)),
                Confirmation.of(Act.Kind.CONCLUDE, 5L, List.of(1L)),
                Confirmation.of(Act.Kind.CONCLUDE, 6L, List.of(1L, 2L)),
                Confirmation.of(Act.Kind.WITHDRAW, 5L, List.of(1L, 2L))))
            .as("a task more, a task less, another instance and the other closing each give a "
                + "confirmation the one handed out is not")
            .doesNotContain(handedOut)
            .doesNotHaveDuplicates();
    }
}
