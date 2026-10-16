/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import org.jspecify.annotations.Nullable;

import java.util.Objects;

/**
 * One call of a transition: who makes it, and what it presents.
 *
 * <p>The receipt, the conflict token and the confirmation are transport
 * artefacts (concept section 3.1); absent ones are null here and their
 * absence is what check 5 and check 6 of {@link Decision} refuse.
 *
 * @param caller        who calls; authorship is derived from it
 * @param receipt       the receipt a claim handed out; absent where the call carried none
 * @param conflictToken the token of the caller's last read; absent where the call
 *                      carried none
 * @param confirmation  the confirmation a refusal handed out; absent where the call
 *                      carried none
 * @param payload       what the verb carries; {@link TaskInput#NONE} for nothing
 */
public record TaskCall(Actor caller, @Nullable String receipt, @Nullable String conflictToken,
                       @Nullable String confirmation, TaskInput payload) {

    public TaskCall {
        Objects.requireNonNull(caller, "caller");
        payload = payload == null ? TaskInput.NONE : payload;
    }

    /** A call by {@code caller} presenting nothing yet. */
    public static TaskCall by(Actor caller) {
        return new TaskCall(caller, null, null, null, TaskInput.NONE);
    }

    public TaskCall withReceipt(@Nullable String value) {
        return new TaskCall(caller, value, conflictToken, confirmation, payload);
    }

    public TaskCall withConflictToken(@Nullable String value) {
        return new TaskCall(caller, receipt, value, confirmation, payload);
    }

    public TaskCall withConfirmation(@Nullable String value) {
        return new TaskCall(caller, receipt, conflictToken, value, payload);
    }

    public TaskCall with(TaskInput value) {
        return new TaskCall(caller, receipt, conflictToken, confirmation, value);
    }
}
