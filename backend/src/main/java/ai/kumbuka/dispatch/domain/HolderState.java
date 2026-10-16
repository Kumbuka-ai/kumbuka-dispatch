/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import com.fasterxml.jackson.annotation.JsonValue;
import org.jspecify.annotations.Nullable;

/**
 * What the caller needs to know about who holds a task.
 *
 * <p>Three states, deliberately, and none of them names a person. The rule is
 * a standing invariant of the platform: a holder in a single lease is
 * harmless, a holder in a hundred query results is the beginning of a list of
 * who works on what, and per-member evaluation is structurally not to be
 * offered. Reduced to the answer a caller can act on: does anybody hold it,
 * and is that this caller.
 *
 * <p>The wire names are lower case and stable across renames of the constant,
 * for the same reason {@link TaskState} carries a {@code wireName}: the value
 * on the wire is a contract, and the Java identifier is a name for
 * readers of the code.
 */
public enum HolderState {

    /** No claim currently effective — free for takeup. */
    NOBODY("nobody"),
    /** The caller holds the task. */
    SELF("self"),
    /** Somebody else holds it. Who exactly is deliberately withheld. */
    OTHER("other");

    private final String wireName;

    HolderState(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    /**
     * The state as it presents to the caller behind {@code actor}, given the
     * effective holder subject at {@code now} (null when no claim stands).
     */
    public static HolderState of(@Nullable String effectiveHolderSubject, Actor actor) {
        if (effectiveHolderSubject == null) {
            return NOBODY;
        }
        return effectiveHolderSubject.equals(actor.subject()) ? SELF : OTHER;
    }
}
