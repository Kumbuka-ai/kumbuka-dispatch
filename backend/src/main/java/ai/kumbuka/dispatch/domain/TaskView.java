/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A task as a call on it answers it: its address, its head, its conflict
 * token and the calls open to the caller (C6.1).
 *
 * <p>The answer is lean: the head is the task's place and its state, with
 * the resolution of a closed task, and the attributes of its state -- why it
 * is on hold, the instant a deferral named, and for its holder the end of the
 * lease. Its title, its texts, its criteria and its free fields are read by
 * the calls that read them, in later orders, and no writing call answers with
 * a text. A {@code claim} adds the receipt it handed out.
 *
 * @param id            the task's surrogate, for the log line of a write and
 *                      never on the wire
 * @param address       its complete address
 * @param state         its state as a reader is told it: the stored one, or the
 *                      one its lapses make of it (EffectiveState)
 * @param resolution    how it was closed; absent while it is not closed
 * @param conflictToken the time of its draft's last change while it is a
 *                      draft, and from the first send the time of its last act,
 *                      rendered and opaque to a caller (D29)
 * @param open          the calls open to the caller
 * @param standing      the attributes of its state, and what a holder's call hands out
 */
public record TaskView(long id, TaskAddress address, String state, @Nullable String resolution,
                       String conflictToken, List<TaskCall> open, Standing standing) {

    public TaskView {
        open = List.copyOf(open);
    }

    /** A task whose state carries no attribute, and to whose caller nothing is handed out. */
    public TaskView(long id, TaskAddress address, String state, @Nullable String resolution,
                    String conflictToken, List<TaskCall> open) {
        this(id, address, state, resolution, conflictToken, open, Standing.NONE);
    }

    /**
     * The attributes of a task's state, and what a holder's call hands out.
     *
     * @param holdReason      why the task waits; absent where it is not on hold
     * @param parked          whether it is parked after its lapses
     * @param notBefore       the instant a deferral named; absent where the task
     *                        is not deferred
     * @param deferralPending whether that instant has not come yet
     * @param leaseExpiresAt  the end of the caller's lease; absent but for the holder
     * @param receipt         the receipt a claim handed out now; absent on every
     *                        other answer, and never stored
     */
    public record Standing(@Nullable String holdReason, boolean parked,
                           @Nullable Instant notBefore, boolean deferralPending,
                           @Nullable Instant leaseExpiresAt, @Nullable String receipt) {

        /** No attribute, and nothing handed out. */
        public static final Standing NONE = new Standing(null, false, null, false, null, null);
    }

    /** Whether the task is closed and finished: it waits for nothing then. */
    public boolean closed() {
        return Task.CLOSED.equals(state);
    }

    /**
     * The fields of the answer, in their order: the one mapping of a task to
     * the {@code fields} a caller reads (C7.1). A member without a value is
     * left out.
     */
    public Map<String, Object> fields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("selector", address.instance().selector());
        fields.put("number", address.instance().number());
        fields.put("sub", address.sub());
        fields.put("state", state);
        putPresent(fields, "hold_reason", standing.holdReason());
        putPresent(fields, "not_before", standing.notBefore());
        putPresent(fields, "lease_expires_at", standing.leaseExpiresAt());
        putPresent(fields, "resolution", resolution);
        return Collections.unmodifiableMap(fields);
    }

    private static void putPresent(Map<String, Object> fields, String name,
                                   @Nullable Object value) {
        if (value != null) {
            fields.put(name, value);
        }
    }
}
