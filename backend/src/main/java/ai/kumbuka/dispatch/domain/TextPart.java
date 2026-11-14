/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The one part of a task's text that {@code read_text} answers.
 *
 * <p>One call, one part: no call answers several (concept section 3.4).
 */
public enum TextPart {

    /** The commission. */
    DISPATCH("dispatch"),
    /** The valid answer: the youngest delivery. */
    RETURN("return"),
    /** Questions, their answers, remarks and earlier deliveries, in order. */
    THREAD("thread"),
    /** Every addendum, on whichever text it supplements. */
    ADDENDA("addenda");

    private final String wireName;

    TextPart(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }
}
