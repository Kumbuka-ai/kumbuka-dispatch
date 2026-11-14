/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

/**
 * A claimed task and the receipt that proves the claim.
 *
 * <p>The receipt is the only copy; the row stores its hash. No text of the
 * task travels with the claim: the holder reads the commission next, with
 * {@code read_text}.
 */
public record TaskClaim(TaskView task, String receipt) {
}
