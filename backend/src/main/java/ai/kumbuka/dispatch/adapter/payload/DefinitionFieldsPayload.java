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
 * What a seeded definition version carries: its head, its schema with its
 * enumerations, its activities, its start contract, and the enumerations of
 * the platform's system fields in the same form as the customer's. A list
 * with nothing in it travels as an empty list.
 */
public record DefinitionFieldsPayload(
    @JsonProperty("selector") String selector,
    @JsonProperty("version") int version,
    @JsonProperty("state") String state,
    @JsonProperty("verdict_required") boolean verdictRequired,
    @JsonProperty("outcome_required") boolean outcomeRequired,
    @JsonProperty("outcome_labels") List<String> outcomeLabels,
    @JsonProperty("schema") List<SchemaFieldPayload> schema,
    @JsonProperty("enumerations") List<EnumerationPayload> enumerations,
    @JsonProperty("activities") List<ActivityPayload> activities,
    @JsonProperty("start_contract") List<ContractFieldPayload> startContract,
    @JsonProperty("system_enumerations") List<EnumerationPayload> systemEnumerations) {
}
