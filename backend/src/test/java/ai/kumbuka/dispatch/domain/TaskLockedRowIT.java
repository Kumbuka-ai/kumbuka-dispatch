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
 * The kernel decides on the row as it stands once locked, even where the same
 * transaction read the task before it took the lock.
 *
 * <p>The first transaction reads a task without a lock, which puts the task
 * into its persistence context. A second transaction changes the row and
 * commits. Only then does the first call a kernel operation that locks and
 * decides. Nothing here waits on a lock: the second transaction has ended
 * before the first takes one, so the order is fixed by the test and not by a
 * timing.
 *
 * <p>Red probe, observed: with the refresh under the lock taken out of {@code
 * TaskRepository}, the first case lets K take over a task H holds, and the
 * second overwrites an accepted child as withdrawn.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@Disabled(TaskLockedRowIT.REBUILT)
class TaskLockedRowIT {

    static final String REBUILT = "rebuilt in T5: a claim decides on the row as it stands once "
        + "locked and not on an earlier unlocked read";

    @Test
    void a_claim_after_an_unlocked_read_decides_on_the_claim_another_transaction_committed() {
        fail(REBUILT);
    }

    @Test
    void a_root_closing_after_an_unlocked_read_of_its_child_keeps_the_child_accepted() {
        fail(REBUILT);
    }
}
