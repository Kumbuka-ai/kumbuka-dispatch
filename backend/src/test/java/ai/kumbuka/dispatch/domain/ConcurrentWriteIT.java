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
 * Two writers present the same conflict token: exactly one writes, and the
 * other is refused with {@code CONFLICT_TOKEN_STALE} and the task as it now
 * stands (C10.2, DEC-0041).
 *
 * <p>The comparison of the presented token and the write happen under the
 * row lock, refreshed under it (C11.1). The first case makes the two writers
 * overlap for real: the first holds the lock in an open transaction while the
 * second is started, and the database is asked whether the second waits on
 * the first ({@link LockWaits}) before the first commits. Without the lock the
 * second reads the committed row, finds its token current, and writes too.
 *
 * <p>The second case is the wire: the refusal carries {@code address}, {@code
 * conflict_token}, {@code fields} and {@code service} with status 412, the
 * token being the one the winner's answer handed out.
 *
 * <p>RED STATE, observed with the update reading the task without the lock
 * (a read, then a compare, then a write): the second writer wrote as well,
 * and the first case failed on the refusal it expected. Against the tree with
 * the database trigger both cases were green: the lock serialised the two
 * writers there too, and the token the trigger wrote differed from the one
 * presented.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@Disabled(ConcurrentWriteIT.REBUILT)
class ConcurrentWriteIT {

    static final String REBUILT = "rebuilt in T4: of two writers with one conflict token exactly one "
        + "writes and the other is told the object as it now stands";

    @Test
    void two_writers_with_one_token_exactly_one_writes() {
        fail(REBUILT);
    }

    @Test
    void the_loser_is_told_the_task_as_it_now_stands_412() {
        fail(REBUILT);
    }
}
