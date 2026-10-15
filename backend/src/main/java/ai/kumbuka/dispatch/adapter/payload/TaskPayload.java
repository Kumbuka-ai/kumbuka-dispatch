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
 * One task, as a call on it answers it (C6.1): its complete address, its
 * fields, its conflict token, the calls open to the caller with one sentence
 * each, what it waits for where none is open, and for a claim the receipt.
 *
 * @param fields the task's fields, from the one mapping of its type in the
 *               domain; lean for a writing call, the full head for a read
 */
public record TaskPayload(
    @JsonProperty("address") String address,
    @JsonProperty("fields") Map<String, Object> fields,
    @JsonProperty("conflict_token") String conflictToken,
    @JsonProperty("next") List<NextStepPayload> next,
    @JsonProperty("waiting_for") String waitingFor,
    @JsonProperty("receipt") String receipt) {
}
