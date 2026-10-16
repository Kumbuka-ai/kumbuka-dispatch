/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.payload;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One page of a listing, and exactly that (C9.2): the tasks, and the cursor
 * of the next page, absent on the last, which is the only sign that it is the
 * last.
 */
public record PagePayload(
    @JsonProperty("objects") List<TaskPayload> objects,
    @JsonProperty("next_cursor") String nextCursor) {
}
