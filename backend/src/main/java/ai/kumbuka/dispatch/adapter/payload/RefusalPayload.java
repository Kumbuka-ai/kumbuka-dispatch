/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.payload;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

/**
 * A refusal on either surface (C8.4, DEC-0042): the code a caller matches on,
 * the message that explains it, and the data it acts on. {@code data} is
 * absent, not empty, on the one refusal that must say nothing.
 *
 * @param data absent for NOT_FOUND and the refusals decided before the route
 */
public record RefusalPayload(
    @JsonProperty("reason") String reason,
    @JsonProperty("message") String message,
    @JsonProperty("data") @Nullable RefusalDataPayload data) {
}
