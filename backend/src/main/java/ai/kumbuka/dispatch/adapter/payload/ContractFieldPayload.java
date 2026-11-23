/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.payload;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One field a contract names, by the name of its control field, and whether a
 * delivery or a start must carry it.
 */
public record ContractFieldPayload(
    @JsonProperty("field") String field,
    @JsonProperty("required") boolean required) {
}
