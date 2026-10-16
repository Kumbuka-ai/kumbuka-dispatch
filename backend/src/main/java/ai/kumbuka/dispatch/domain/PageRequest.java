/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Which page of a listing a caller asks for: how many tasks, in which order,
 * and after which task.
 *
 * <p>The page size is bounded on both sides and refused outside them rather
 * than clamped: a caller that asked for a thousand and received two hundred
 * without a word would read the shorter page as the whole list.
 *
 * @param pageSize  how many tasks at most, from 1 to {@link #MAX_PAGE_SIZE}
 * @param sort      the key the listing is sorted by
 * @param direction the direction of that key
 * @param after     the cursor a previous page of the same listing handed out,
 *                  as the caller sent it; empty for the first page
 */
public record PageRequest(int pageSize, SortKey sort, SortKey.Direction direction,
                          Optional<String> after) {

    /** The page size of a listing that names none. */
    public static final int DEFAULT_PAGE_SIZE = 50;

    /** The largest page a listing hands out. */
    public static final int MAX_PAGE_SIZE = 200;

    /** The argument that names the page size, by its declared name. */
    static final String PAGE_SIZE = "page_size";

    public PageRequest {
        Objects.requireNonNull(sort, "sort");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(after, "after");
        if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw pageSizeRejected();
        }
    }

    /** The first page in the default order. */
    public static PageRequest first() {
        return new PageRequest(DEFAULT_PAGE_SIZE, SortKey.ID, SortKey.Direction.ASC,
            Optional.empty());
    }

    /**
     * A page as a caller wrote it, refusing what it cannot take.
     *
     * @param pageSize a whole number; absent for {@link #DEFAULT_PAGE_SIZE}. A
     *                 value past what a long holds arrives as the long's bound
     * @param sort     a declared key's wire name; absent for {@link SortKey#ID}
     * @param after    the cursor; absent for the first page
     * @throws DispatchException {@code PAGE_SIZE_REJECTED}, {@code SORT_UNDECLARED}
     */
    public static PageRequest of(@Nullable Long pageSize, @Nullable String sort,
                                 SortKey.@Nullable Direction direction, @Nullable String after) {
        int size = DEFAULT_PAGE_SIZE;
        if (pageSize != null) {
            if (pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
                throw pageSizeRejected();
            }
            size = pageSize.intValue();
        }
        return new PageRequest(size, SortKey.of(sort),
            direction == null ? SortKey.Direction.ASC : direction, Optional.ofNullable(after));
    }

    /**
     * The refusal names the argument and not the value asked for: a refusal
     * repeats nothing the caller sent (DEC-0042, DEC-0043 R18).
     */
    private static DispatchException pageSizeRejected() {
        return new DispatchException(DispatchException.Reason.PAGE_SIZE_REJECTED,
            "a page holds from 1 to " + MAX_PAGE_SIZE + " tasks.", List.of(PAGE_SIZE));
    }
}
