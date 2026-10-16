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

import java.util.List;
import java.util.Map;

/**
 * What a refusal's caller acts on. Each member means one thing on every
 * service (DEC-0042); a member with no value is absent.
 *
 * @param attempted absent where no call is known
 * @param address absent except for a stale token
 * @param state absent on a refusal about no task
 * @param fields absent except for a stale token
 * @param next absent where the refusal names no call
 * @param waitingFor absent where a call is open
 * @param offenders absent where the refusal concerns no argument
 * @param accepts absent except where a call or address lists what it accepts
 * @param children absent on a refusal about no bracket
 * @param confirmation absent where none is handed out
 * @param conflictToken absent except for a stale token
 * @param service absent except for a stale token
 * @param reference absent except for an unexpected failure
 */
public record RefusalDataPayload(
    @JsonProperty("attempted") @Nullable String attempted,
    @JsonProperty("address") @Nullable String address,
    @JsonProperty("state") @Nullable String state,
    @JsonProperty("fields") @Nullable Map<String, Object> fields,
    @JsonProperty("next") @Nullable List<NextStepPayload> next,
    @JsonProperty("waiting_for") @Nullable String waitingFor,
    @JsonProperty("offenders") @Nullable List<String> offenders,
    @JsonProperty("accepts") @Nullable List<String> accepts,
    @JsonProperty("children") @Nullable List<String> children,
    @JsonProperty("confirmation") @Nullable String confirmation,
    @JsonProperty("conflict_token") @Nullable String conflictToken,
    @JsonProperty("service") @Nullable String service,
    @JsonProperty("reference") @Nullable String reference) {
}
