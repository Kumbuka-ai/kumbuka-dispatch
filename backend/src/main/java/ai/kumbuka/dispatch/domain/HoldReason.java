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

/**
 * Why a task in {@code on_hold} is paused.
 *
 * <p>{@code question} is set by {@code ask} and lifted by the commissioner's
 * {@code answer}; {@code dependency} and {@code external} are set by
 * {@code hold} and lifted by the holder's {@code resume}. A task parked after
 * its third lapse is {@code external} without a holder.
 */
public enum HoldReason {

    QUESTION("question"),
    DEPENDENCY("dependency"),
    EXTERNAL("external");

    private final String wireName;

    HoldReason(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    /** The constant a stored value names; empty for a stored null. */
    static Optional<HoldReason> fromWireName(@Nullable String value) {
        if (value == null) {
            return Optional.empty();
        }
        for (HoldReason r : values()) {
            if (r.wireName.equals(value)) {
                return Optional.of(r);
            }
        }
        throw new IllegalArgumentException("no hold reason '" + value + "'");
    }
}
