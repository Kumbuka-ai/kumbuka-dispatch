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
 * One enumeration, by its name, with its values in their order: a customer's
 * of the schema, or the platform's of a system field, in the one form.
 */
public record EnumerationPayload(
    @JsonProperty("name") String name,
    @JsonProperty("values") List<String> values) {
}
