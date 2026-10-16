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

/** One part of a task's text: the only answer that carries one. */
public record TextPayload(
    @JsonProperty("address") String address,
    @JsonProperty("part") String part,
    @JsonProperty("texts") List<TextEntryPayload> texts,
    @JsonProperty("addenda") int addenda) {
}
