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

import java.util.Optional;

/** How a {@code closed} task ended. Set exactly in {@code closed}. */
public enum Outcome {

    ACCEPTED("accepted"),
    REJECTED("rejected"),
    FAILED("failed"),
    WITHDRAWN("withdrawn");

    private final String wireName;

    Outcome(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    /** The constant a stored value names; empty for a stored null. */
    static Optional<Outcome> fromWireName(@Nullable String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (Outcome o : values()) {
            if (o.wireName.equals(value)) {
                return Optional.of(o);
            }
        }
        throw new IllegalArgumentException("no outcome '" + value + "'");
    }
}
