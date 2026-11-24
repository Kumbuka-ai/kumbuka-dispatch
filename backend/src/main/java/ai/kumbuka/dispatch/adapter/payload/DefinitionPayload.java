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
 * The answer of {@code read_definition}: a seeded definition version under
 * {@code fields} (DEC-0056/C5, C6).
 *
 * <p>No address and no conflict token: a version is no object of this
 * service's address form, and no call writes one.
 */
public record DefinitionPayload(
    @JsonProperty("fields") DefinitionFieldsPayload fields) {
}
