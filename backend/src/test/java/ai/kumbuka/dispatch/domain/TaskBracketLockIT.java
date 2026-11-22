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
 * The locks of a bracket under concurrency.
 *
 * <p>Two transactions meet in each case. The first is held open in the test
 * thread while the second runs on a thread of its own. The first commits only
 * once the database reports the second waiting on a lock the first holds
 * ({@link LockWaits}), so the overlap is established and not hoped for; no
 * fixed time decides it. Each case then asserts what the bracket holds once
 * both are done, which is the statement the locks exist for, and last that
 * the second did wait.
 *
 * <p>Red probes, observed: with {@code TaskService.requireOpenBracket} reading
 * the root without a lock, the first case ends with a closed root and a draft
 * child; with {@code TaskRepository.lockChildren} reading without a lock, the
 * second ends with the accepted child overwritten as withdrawn; with {@code
 * TaskRepository.lockSelector} answering the selector the creation read before
 * its lock, the second creation of the third case takes the first's number and
 * fails on the unique address of the task table.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@Disabled(TaskBracketLockIT.REBUILT)
class TaskBracketLockIT {

    static final String REBUILT = "rebuilt in T3: the locks of a bracket keep a closing root, "
        + "its children and the numbering of new roots consistent under concurrency";

    @Test
    void a_child_created_while_its_root_closes_never_stays_unfinished_under_a_closed_root() {
        fail(REBUILT);
    }

    @Test
    void a_child_accepted_by_a_second_transaction_before_the_root_closes_keeps_accepted() {
        fail(REBUILT);
    }

    @Test
    void two_roots_created_at_once_take_consecutive_numbers() {
        fail(REBUILT);
    }
}
