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
 * A JSON-RPC error: its code, its message, and for an unknown tool the tool
 * names the service declares, under {@code data.accepts} (C4.3).
 */
public record RpcErrorBodyPayload(
    @JsonProperty("code") int code,
    @JsonProperty("message") String message,
    @JsonProperty("data") RpcErrorDataPayload data) {
}
