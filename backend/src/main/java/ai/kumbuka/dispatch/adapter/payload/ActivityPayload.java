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
 * One activity of a definition version: what a delivery of its tasks needs,
 * the outcome labels it may carry, the fields of its result contract, the
 * input its tasks take and whether their executor may ask.
 */
public record ActivityPayload(
    @JsonProperty("name") String name,
    @JsonProperty("verdict_required") boolean verdictRequired,
    @JsonProperty("outcome_required") boolean outcomeRequired,
    @JsonProperty("criteria_required") boolean criteriaRequired,
    @JsonProperty("outcome_labels") List<String> outcomeLabels,
    @JsonProperty("result_contract") List<ContractFieldPayload> resultContract,
    @JsonProperty("input_kind") String inputKind,
    @JsonProperty("may_ask") boolean mayAsk) {
}
