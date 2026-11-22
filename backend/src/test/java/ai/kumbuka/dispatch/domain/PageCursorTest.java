/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A cursor continues the listing it was handed out for and no other, and a
 * page is what a listing answers (ADR-0049, C9.1, C9.2).
 *
 * <p>The listing is named here by its parts; which filter arguments a listing
 * declares is the listing's, so the test names its own.
 */
class PageCursorTest {

    private static final UUID SCOPE = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final List<String> FILTERS = List.of("state", "tag");
    private static final PageRequest FIRST = PageRequest.first();

    @Test
    void a_cursor_handed_out_continues_its_own_listing_from_its_position() {
        PageCursor.Binding listing = PageCursor.Binding.of(SCOPE, "sprint", "state=open", FIRST);
        PageCursor.Position last = new PageCursor.Position(4, 2,
            Instant.parse("2026-11-22T10:15:30.123456Z"));

        PageCursor read = PageCursor.decode(new PageCursor(listing, last).encode());

        assertThat(read.continuing(PageCursor.Binding.of(SCOPE, "sprint", "state=open", FIRST),
                FILTERS))
            .as("the cursor travels and comes back to the position it was handed out at")
            .isEqualTo(last);
    }

    @Test
    void a_cursor_of_another_listing_is_refused_naming_what_differs() {
        PageCursor handedOut = new PageCursor(
            PageCursor.Binding.of(SCOPE, "sprint", "state=open", FIRST),
            new PageCursor.Position(1, 0, Instant.parse("2026-11-22T10:15:30Z")));
        PageCursor.Binding otherFilter = PageCursor.Binding.of(SCOPE, "sprint", "state=closed",
            FIRST);
        PageCursor.Binding otherSelector = PageCursor.Binding.of(SCOPE, "satellite",
            "state=open", FIRST);

        assertThatThrownBy(() -> handedOut.continuing(otherFilter, FILTERS))
            .as("a filter that differs names every filter argument the listing declares, since "
                + "its fingerprint does not say which")
            .isInstanceOfSatisfying(DispatchException.class, refused -> {
                assertThat(refused.reason()).isEqualTo(DispatchException.Reason.CURSOR_FOREIGN);
                assertThat(refused.offenders()).containsExactly("state", "tag");
            });
        assertThatThrownBy(() -> handedOut.continuing(otherSelector, FILTERS))
            .as("a selector that differs names the selector")
            .isInstanceOfSatisfying(DispatchException.class, refused ->
                assertThat(refused.offenders()).containsExactly("selector"));
    }

    @Test
    void a_page_holds_its_objects_as_handed_and_says_where_the_listing_goes_on() {
        Page<String> page = new Page<>(new java.util.ArrayList<>(List.of("a", "b")),
            Optional.of("next"));

        assertThat(page.objects()).as("the objects, in their order").containsExactly("a", "b");
        assertThat(page.nextCursor()).as("and the cursor of the next page").contains("next");
        List<String> objects = page.objects();
        assertThatThrownBy(() -> objects.add("c"))
            .as("a page is not changed after it is answered")
            .isInstanceOf(UnsupportedOperationException.class);
    }
}
