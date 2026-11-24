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
import java.util.Map;

/**
 * An instance, as every act on it answers it (C6.1): its complete address,
 * its head under {@code fields}, its conflict token, and the calls open to the
 * caller. The content of {@code fields} is the one mapping of an instance the
 * domain makes.
 *
 * @param next the calls open to the caller; empty on a closed instance, which
 *             is finished and waits for nothing
 */
public record InstancePayload(
    @JsonProperty("address") String address,
    @JsonProperty("fields") Map<String, Object> fields,
    @JsonProperty("conflict_token") String conflictToken,
    @JsonProperty("next") List<NextStepPayload> next) {
}
