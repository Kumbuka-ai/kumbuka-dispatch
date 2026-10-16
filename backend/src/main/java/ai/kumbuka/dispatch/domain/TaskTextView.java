/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;

/**
 * One part of the text of one task: what {@code read_text} answers, and the
 * only answer of the kernel that carries text.
 *
 * @param part     the part that was read
 * @param entries  the texts of that part, in the order written
 * @param addenda  how many addenda the part's text has, without their content;
 *                 counted for {@code dispatch} and {@code return}, zero otherwise
 */
public record TaskTextView(TextPart part, List<Entry> entries, int addenda) {

    /**
     * One text, with its type, suffix and creation instant.
     *
     * @param addendumSuffix the letter of an addendum; absent on the text it supplements
     */
    public record Entry(TextType type, @Nullable String addendumSuffix, String text,
                        Instant createdAt) {
    }

    public TaskTextView {
        entries = List.copyOf(entries);
    }
}
