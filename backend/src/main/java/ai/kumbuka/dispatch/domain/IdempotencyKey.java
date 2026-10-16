/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import org.jspecify.annotations.Nullable;

/**
 * The key a caller chose so that a retry does not act twice, or the statement
 * that it chose none.
 *
 * <p>Sealed and two-cased, because the absence is the ordinary case and it is
 * load-bearing. A call without a key is executed
 * every time it arrives, and a call with one is executed at most once per key
 * per caller per scope in twenty-four hours. A {@code null} travelling into the
 * domain would leave that difference undeclared at every method it passed.
 */
public sealed interface IdempotencyKey {

    /** The caller chose a key. */
    record Given(String value) implements IdempotencyKey {
    }

    /** The caller chose none, and every arrival of the call is a call. */
    record NotGiven() implements IdempotencyKey {
    }

    /** The one instance of the absent case; it carries nothing to distinguish. */
    IdempotencyKey NONE = new NotGiven();

    /** The key an argument carries, absent or present. */
    static IdempotencyKey of(@Nullable String value) {
        return value == null || value.isBlank() ? NONE : new Given(value);
    }

    /** Whether a key arrived at all. */
    default boolean isGiven() {
        return this instanceof Given;
    }
}
