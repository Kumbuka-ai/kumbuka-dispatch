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
 * A task, as {@code create}, {@code update}, {@code send} and the calls that
 * hold it answer it (C6.1): its complete address, its head under {@code
 * fields}, its conflict token, the calls open to the caller, what it waits for
 * where none is open, and the receipt a {@code claim} handed out (C6.3). The
 * content of {@code fields} is the one mapping of a task the domain makes.
 *
 * @param next       the calls open to the caller; empty where none is
 * @param waitingFor what the task waits for; absent where a call is open to
 *                   the caller, or the task is closed and finished
 * @param receipt    the receipt a claim handed out now; absent on every other answer
 */
public record TaskPayload(
    @JsonProperty("address") String address,
    @JsonProperty("fields") Map<String, Object> fields,
    @JsonProperty("conflict_token") String conflictToken,
    @JsonProperty("next") List<NextStepPayload> next,
    @JsonProperty("waiting_for") @Nullable String waitingFor,
    @JsonProperty("receipt") @Nullable String receipt) {
}
