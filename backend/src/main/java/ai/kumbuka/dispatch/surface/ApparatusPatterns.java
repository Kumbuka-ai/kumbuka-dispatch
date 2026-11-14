/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The apparatus patterns of a listing's filter: which ones are well formed.
 *
 * <p>A pattern is one or more of {@code A-Z a-z 0-9 + - *}, and {@code *} is
 * the only wildcard. The underscore and the percent sign are excluded because
 * they carry a meaning of their own in the comparison the listing runs, so a
 * pattern that passes here needs no escaping there. A pattern of nothing but
 * {@code *} is admitted: a listing takes nothing.
 */
public final class ApparatusPatterns {

    /** The character rule of one pattern, as the published schema carries it. */
    public static final String CHARACTER_RULE = "^[A-Za-z0-9+*-]+$";

    private static final Pattern WELL_FORMED = Pattern.compile(CHARACTER_RULE);

    private ApparatusPatterns() {
    }

    /** The first pattern that is not well formed; empty where every one is. */
    public static Optional<String> firstMalformed(List<String> patterns) {
        for (String pattern : patterns) {
            if (pattern == null || !WELL_FORMED.matcher(pattern).matches()) {
                return Optional.of(String.valueOf(pattern));
            }
        }
        return Optional.empty();
    }

    /**
     * The position of the first pattern that breaks the character rule,
     * counted from zero, or -1 where every one keeps it. A refusal names the
     * position and never the pattern (DEC-0043 R19).
     */
    public static int firstMalformedAt(List<String> patterns) {
        for (int i = 0; i < patterns.size(); i++) {
            String pattern = patterns.get(i);
            if (pattern == null || !WELL_FORMED.matcher(pattern).matches()) {
                return i;
            }
        }
        return -1;
    }
}
