/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 *
 * @param suffix absent on a text that is no addendum
 */
package ai.kumbuka.dispatch.adapter.payload;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.jspecify.annotations.Nullable;

import java.time.Instant;

/**
 * One text of a part: its type, its suffix where it is an addendum, the text, when it was written.
 *
 * @param suffix absent on a text that is no addendum
 */
public record TextEntryPayload(
    @JsonProperty("type") String type,
    @JsonProperty("suffix") @Nullable String suffix,
    @JsonProperty("text") String text,
    @JsonProperty("created_at") Instant createdAt) {
}
