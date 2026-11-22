/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * A claim repeated under its idempotency key.
 *
 * <p>The repeat answers the same task with a new receipt; the earlier receipt
 * stops matching, because the service keeps only the hash and replaces it.
 * Nothing else moves: state, holder, lease end and lapse count stay. Where the
 * caller no longer holds the task, the
 * repeat is refused with the reason the situation gives and takes nothing.
 *
 * <p>Red probe, observed: with {@code Task.reissue} not called, the earlier
 * receipt keeps matching and the first case turns red at its renewal.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@Disabled(TaskClaimRepeatIT.REBUILT)
class TaskClaimRepeatIT {

    static final String REBUILT = "rebuilt in T5: a claim repeated under its idempotency key hands "
        + "out a new receipt and moves nothing else";

    /**
     * Stands for the tests of this class until their order rebuilds them:
     * <ul>
     *   <li>{@code a_repeated_claim_answers_the_same_task_with_a_new_receipt_and_moves_nothing}</li>
     *   <li>{@code a_repeat_after_the_hold_ended_is_refused_and_takes_nothing}</li>
     *   <li>{@code a_repeat_after_the_lease_lapsed_names_the_lapse_and_takes_nothing}</li>
     *   <li>{@code the_same_key_on_another_call_or_other_arguments_is_refused}</li>
     * </ul>
     */
    @Test
    void the_tests_of_this_class_are_rebuilt_in_their_order() {
        fail(REBUILT);
    }
}
