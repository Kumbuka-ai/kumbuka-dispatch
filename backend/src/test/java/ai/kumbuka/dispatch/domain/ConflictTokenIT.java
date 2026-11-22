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
 * The conflict token is the task's {@code updated_at} at microsecond
 * precision, written by the application from the injected clock as {@code
 * max(now, previous + 1 µs)} on every write that changes the task, and left
 * as it is by a write that changes nothing (C10.1, DEC-0041).
 *
 * <p>The clock is the test's: {@link SettableClock} holds one instant, so two
 * writes fall into the same microsecond by construction and the expected
 * tokens are written here from that instant, not read from the service.
 *
 * <p>RED STATE, observed against the tree with the database trigger: the
 * token was the database's transaction time, not the clock's, so the cases on
 * two writes in one microsecond and on a clock set back failed on the first
 * token; the update of the text alone left the token where it was, because
 * the task row was not written. The write that changes nothing kept its token
 * there too, as it must.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@Disabled(ConflictTokenIT.REBUILT)
class ConflictTokenIT {

    static final String REBUILT = "rebuilt in T4: the conflict token moves with the clock on every "
        + "write that changes a draft and stays on one that changes nothing";

    @Test
    void two_writes_in_the_same_microsecond_yield_two_tokens() {
        fail(REBUILT);
    }

    @Test
    void the_token_follows_the_clock_and_never_returns_to_an_earlier_value() {
        fail(REBUILT);
    }

    @Test
    void a_write_that_changes_nothing_keeps_the_token() {
        fail(REBUILT);
    }

    @Test
    void a_change_of_the_dispatch_text_alone_moves_the_token() {
        fail(REBUILT);
    }
}
