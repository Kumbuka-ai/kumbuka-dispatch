/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.payload;

import ai.kumbuka.dispatch.domain.DispatchException;
import ai.kumbuka.dispatch.fixture.LogLines;
import ai.kumbuka.dispatch.surface.PlatformLog;
import ai.kumbuka.dispatch.surface.ProcessVerb;
import ai.kumbuka.dispatch.surface.Surface;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.LogRecord;

import static org.assertj.core.api.Assertions.assertThat;

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
class UnexpectedFailureLogTest {

    @Test
    void the_line_carries_neither_the_address_nor_the_message_nor_the_exception() {
        String scope = "zzscope7319";
        String marker = "zzmessage4417";
        List<LogRecord> lines = LogLines.of(PlatformLog.class, () ->
            DomainRefusals.of(
                new DispatchException(DispatchException.Reason.SESSION_NOT_BOUND, marker),
                Surface.MCP, null, ProcessVerb.READ,
                Map.of("address", "dispatch://" + scope + "/sprint/1.0"),
                complete -> Optional.empty(), Clock.systemUTC()));

        assertThat(lines).singleElement().satisfies(line -> {
            String text = LogLines.text(line);
            assertThat(text)
                .as("RED STATE, observed: the line named the address the call carried")
                .doesNotContain(scope);
            assertThat(text).as("the exception's message is the failing code's")
                .doesNotContain(marker);
            assertThat(text).as("the class names say what failed")
                .contains(DispatchException.class.getName())
                .contains("dispatch_read");
            assertThat(line.getThrown()).as("no exception object").isNull();
        });
    }
}
