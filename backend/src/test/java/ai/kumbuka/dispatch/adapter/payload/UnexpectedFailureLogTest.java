/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.payload;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The one log line of an unexpected failure carries the platform's words only
 * (C8.3, C13.2, C13.3, DEC-0051, F-0383).
 *
 * <p>The line names the call, the reference and the class names of the
 * exception chain. It carries no exception object, no exception message and no
 * address the call carried: an address is written by the caller, its scope and
 * selector included, and C13.3 keeps even the canonical address of a completed
 * write out of the log.
 *
 * <p>The line is written under the category of {@code PlatformLog}, the one
 * logger of the packages that serve a caller (DEC-0051, C4);
 * {@code UnexpectedFailures} mints the reference and hands it there.
 */
@Disabled(UnexpectedFailureLogTest.REBUILT)
class UnexpectedFailureLogTest {

    static final String REBUILT = "rebuilt in T13: the log line of an unexpected failure "
        + "carries neither the address nor the message nor the exception";

    @Test
    void the_line_carries_neither_the_address_nor_the_message_nor_the_exception() {
        fail(REBUILT);
    }
}
