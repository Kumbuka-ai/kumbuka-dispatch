/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What a listing answers: one page of objects, and where the listing goes on
 * (ADR-0049, C9.2).
 *
 * @param objects    the objects of this page, in the order asked for
 * @param nextCursor the cursor of the next page; empty on the last page, which
 *                   is the only way a caller learns that it is the last
 * @param <T>        what the listing lists
 */
public record Page<T>(List<T> objects, Optional<String> nextCursor) {

    public Page {
        objects = List.copyOf(objects);
        Objects.requireNonNull(nextCursor, "nextCursor");
    }
}
