/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.repository;

import ai.kumbuka.dispatch.domain.PageCursor;
import ai.kumbuka.dispatch.domain.PageRequest;
import ai.kumbuka.dispatch.domain.SortKey;
import jakarta.persistence.Query;

/**
 * The keyset of a listing: where a page continues, in which order, and how
 * many rows it reads (ADR-0049, C9.3). The one place of the service that
 * states it.
 *
 * <p>The keyset is a row-value comparison on the sort's columns with the
 * address always last, so the order is total within a selector and a page
 * continues exactly after the position its cursor names. A page reads one row
 * more than it holds; that row's presence is what says a next page exists,
 * without counting.
 *
 * <p>Written against the alias {@code t} of the listed task.
 */
final class KeysetClause {

    private static final String CREATED = "kc";
    private static final String NUMBER = "kn";
    private static final String SUB = "ks";

    private final boolean descending;
    private final boolean byCreation;

    private KeysetClause(boolean descending, boolean byCreation) {
        this.descending = descending;
        this.byCreation = byCreation;
    }

    /** The keyset of the page a listing asks for. */
    static KeysetClause of(PageRequest page) {
        return new KeysetClause(page.direction() == SortKey.Direction.DESC,
            page.sort() == SortKey.CREATED_AT);
    }

    /** The rows a page reads: one more than it holds. */
    static int rowsRead(PageRequest page) {
        return page.pageSize() + 1;
    }

    /** The conjunct that continues after a position, as a line of the statement. */
    String after() {
        String op = descending ? "<" : ">";
        return byCreation
            ? "  AND (t.createdAt, t.number, t.sub) " + op + " (:" + CREATED + ", :" + NUMBER
                + ", :" + SUB + ")\n"
            : "  AND (t.number, t.sub) " + op + " (:" + NUMBER + ", :" + SUB + ")\n";
    }

    /** The order of the listing, the address last. */
    String orderBy() {
        String direction = descending ? " DESC" : " ASC";
        return "ORDER BY " + (byCreation ? "t.createdAt" + direction + ", " : "")
            + "t.number" + direction + ", t.sub" + direction;
    }

    /** Binds the position {@link #after} continues from. */
    void bind(Query query, PageCursor.Position position) {
        query.setParameter(NUMBER, position.number());
        query.setParameter(SUB, position.sub());
        if (byCreation) {
            query.setParameter(CREATED, position.createdAt());
        }
    }
}
