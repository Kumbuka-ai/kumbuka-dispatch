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

    @Test
    void an_overlapping_creation_under_one_key_answers_the_first_root() {
        fail(REBUILT);
    }

    @Test
    void an_overlapping_claim_under_one_key_answers_the_task_with_a_new_receipt() {
        fail(REBUILT);
    }

    @Test
    void an_overlapping_addendum_under_one_key_is_attached_once() {
        fail(REBUILT);
    }

    /**
     * Two keys whose parts differ only in where a unit separator falls do not
     * wait on each other: the key lock is taken over the parts, not over the
     * parts joined by a character a key may itself carry.
     *
     * <p>RED STATE, observed against the lock over the parts joined by {@code
     * chr(31)}: caller {@code x} under key {@code y␟z} and caller {@code x␟y}
     * under key {@code z} took one lock, and the second waited on the first.
     */
    @Test
    void keys_that_join_alike_across_a_unit_separator_take_two_locks() {
        fail(REBUILT);
    }
}
