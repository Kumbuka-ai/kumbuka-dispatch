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
 * One control field of a schema: its name and its type.
 *
 * @param enumeration the name of the enumeration a choice field takes its
 *                    values from; absent for every other type
 */
public record SchemaFieldPayload(
    @JsonProperty("name") String name,
    @JsonProperty("type") String type,
    @JsonProperty("enumeration") @Nullable String enumeration) {
}
