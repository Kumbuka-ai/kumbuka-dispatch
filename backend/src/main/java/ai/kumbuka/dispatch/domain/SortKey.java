/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

/**
 * What a listing of tasks may be sorted by, and nothing else.
 *
 * <p>Two keys, on both surfaces: the address and the instant of creation. The
 * address sorts as the two numbers it is made of, so {@code 194.2} comes
 * before {@code 194.18}; the instant of creation always takes the address as
 * its second key, so two tasks created in the same microsecond still stand in
 * one order. Never the technical identity: a UUID orders nothing a caller can
 * name.
 */
public enum SortKey {

    /** {@code (number, sub)}, numerically. The default. */
    ID("id"),

    /** {@code created_at}, then {@code (number, sub)}. */
    CREATED_AT("created_at");

    private final String wireName;

    SortKey(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /** Every declared key, by its name on the wire. */
    public static List<String> wireNames() {
        return Arrays.stream(values()).map(SortKey::wireName).toList();
    }

    /**
     * The key a caller named, or {@link #ID} where it named none.
     *
     * @throws DispatchException {@code SORT_UNDECLARED} for a key not declared,
     *                           naming the argument {@code sort} and not the key
     */
    public static SortKey of(@Nullable String wireName) {
        if (wireName == null) {
            return ID;
        }
        for (SortKey key : values()) {
            if (key.wireName.equals(wireName)) {
                return key;
            }
        }
        throw new DispatchException(DispatchException.Reason.SORT_UNDECLARED,
            "a listing of tasks sorts by " + String.join(", ", wireNames())
                + " and by nothing else.", List.of("sort"));
    }

    /** The direction of a sort. */
    public enum Direction {
        ASC("asc"),
        DESC("desc");

        private final String wireName;

        Direction(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        /** Every direction, by its name on the wire. */
        public static List<String> wireNames() {
            return Arrays.stream(values()).map(Direction::wireName).toList();
        }

        /** The direction a caller named, or {@link #ASC} where it named none. */
        public static Direction of(@Nullable String wireName) {
            if (wireName == null) {
                return ASC;
            }
            for (Direction d : values()) {
                if (d.wireName.equals(wireName)) {
                    return d;
                }
            }
            throw new DispatchException(DispatchException.Reason.SORT_UNDECLARED,
                "a listing of tasks sorts " + String.join(" or ", wireNames()) + ".",
                List.of("direction"));
        }
    }
}
