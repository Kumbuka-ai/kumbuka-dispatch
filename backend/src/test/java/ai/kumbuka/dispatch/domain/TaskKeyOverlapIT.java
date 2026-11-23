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
 * Two calls of one caller under one idempotency key, at once.
 *
 * <p>The first is held open in the test thread while the second runs on a
 * thread of its own, with the same key and the same arguments. The first
 * commits only once the database reports the second waiting on a lock the
 * first holds ({@link LockWaits}). Each case then asserts that the second
 * answered what the first produced, as a repeat after the commit does, and
 * last that the second did wait.
 *
 * <p>Red probe, observed before the key lock existed: the overlapping
 * creation waited at the selector, then created a second root and pointed the
 * key at it. The claim and the addendum were not run before the key lock and
 * carry no observed red state of their own.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@Disabled(TaskKeyOverlapIT.REBUILT)
class TaskKeyOverlapIT {

    static final String REBUILT = "rebuilt in T4: two overlapping calls under one idempotency key "
        + "wait on one lock and the second answers what the first produced";

    /**
     * Stands for the tests of this class until their order rebuilds them:
     * <ul>
     *   <li>{@code an_overlapping_creation_under_one_key_answers_the_first_root}</li>
     *   <li>{@code an_overlapping_claim_under_one_key_answers_the_task_with_a_new_receipt}</li>
     *   <li>{@code an_overlapping_addendum_under_one_key_is_attached_once}</li>
     *   <li>{@code keys_that_join_alike_across_a_unit_separator_take_two_locks}</li>
     * </ul>
     */
    @Test
    void the_tests_of_this_class_are_rebuilt_in_their_order() {
        fail(REBUILT);
    }
}
