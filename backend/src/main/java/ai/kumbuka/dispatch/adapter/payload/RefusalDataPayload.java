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
 * What a refusal's caller acts on. Each member means one thing on every
 * service (DEC-0042); a member with no value is absent.
 */
public record RefusalDataPayload(
    @JsonProperty("attempted") String attempted,
    @JsonProperty("address") String address,
    @JsonProperty("state") String state,
    @JsonProperty("fields") Map<String, Object> fields,
    @JsonProperty("next") List<NextStepPayload> next,
    @JsonProperty("waiting_for") String waitingFor,
    @JsonProperty("offenders") List<String> offenders,
    @JsonProperty("accepts") List<String> accepts,
    @JsonProperty("children") List<ChildPayload> children,
    @JsonProperty("confirmation") String confirmation,
    @JsonProperty("conflict_token") String conflictToken,
    @JsonProperty("service") String service,
    @JsonProperty("reference") String reference) {
}
