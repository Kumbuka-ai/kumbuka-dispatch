/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The kernel's refusals of a listing carry no value the caller sent, neither
 * in the message nor in what they name; they name the declared argument
 * (DEC-0042, DEC-0043 R18).
 *
 * <p>The surface used to rebuild these refusals from its own texts, so a value
 * the kernel named never reached a caller. The surface now maps the kernel's
 * refusal as the kernel gives it, so the kernel names what a caller may read:
 * {@code page_size}, {@code after}, {@code sort}, {@code direction}, a filter
 * by its name, and a value's position where it is one of several.
 *
 * <p>RED STATE, observed 2026-10-16 against the kernel that named the values:
 * every case here was red, each refusal carrying the value it was sent.
 */
class ListingRefusalsTest {

    private static final String VALUE = "zzvalue7319";

    private static final String REBUILT =
        "rebuilt in T11: a refused filter of a listing names the filter and carries no value sent";

    @Test
    void a_page_size_outside_the_page_names_page_size() {
        refusal(() -> PageRequest.of(73190731L, null, SortKey.Direction.ASC, null))
            .names(DispatchException.Reason.PAGE_SIZE_REJECTED, "page_size", "73190731");
        refusal(() -> PageRequest.of(0L, null, SortKey.Direction.ASC, null))
            .names(DispatchException.Reason.PAGE_SIZE_REJECTED, "page_size", null);
    }

    @Test
    void a_cursor_this_service_did_not_hand_out_names_after() {
        refusal(() -> PageCursor.decode(VALUE))
            .names(DispatchException.Reason.CURSOR_MALFORMED, "after", VALUE);
    }

    @Test
    void an_undeclared_sort_or_direction_names_the_argument() {
        refusal(() -> SortKey.of(VALUE))
            .names(DispatchException.Reason.SORT_UNDECLARED, "sort", VALUE);
        refusal(() -> SortKey.Direction.of(VALUE))
            .names(DispatchException.Reason.SORT_UNDECLARED, "direction", VALUE);
    }

    @Test
    @Disabled(REBUILT)
    void a_filter_value_the_field_cannot_take_names_the_filter_and_its_position() {
        fail(REBUILT);
    }

    @Test
    @Disabled(REBUILT)
    void an_undeclared_filter_names_the_declared_ones_and_not_the_one_sent() {
        fail(REBUILT);
    }

    // -----------------------------------------------------------------------

    private record Refused(DispatchException e) {

        void names(DispatchException.Reason reason, String path, String sent) {
            assertThat(e).as("refused").isNotNull();
            assertThat(e.reason()).isEqualTo(reason);
            assertThat(e.offenders()).as("the declared argument, by its path")
                .isEqualTo(List.of(path));
            if (sent != null) {
                assertThat(e.getMessage()).as("the message carries no value sent")
                    .doesNotContain(sent);
            }
        }
    }

    private static Refused refusal(ThrowableAssert.ThrowingCallable call) {
        return new Refused(catchThrowableOfType(DispatchException.class, call));
    }
}
