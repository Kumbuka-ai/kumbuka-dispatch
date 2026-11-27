/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import org.jspecify.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A task as a call on it answers it: its address, its head, its conflict
 * token and the calls open to the caller (C6.1).
 *
 * <p>The answer is lean: the head is the task's place and its state, with
 * the resolution of a closed task. Its title, its texts, its criteria and its
 * free fields are read by the calls that read them, in later orders, and no
 * writing call answers with a text.
 *
 * @param id            the task's surrogate, for the log line of a write and
 *                      never on the wire
 * @param address       its complete address
 * @param state         its state as the store names it
 * @param resolution    how it was closed; absent while it is not closed
 * @param conflictToken the time of its draft's last change while it is a
 *                      draft, and from the first send the time of its last act,
 *                      rendered and opaque to a caller (D29)
 * @param open          the calls open to the caller
 */
public record TaskView(long id, TaskAddress address, String state, @Nullable String resolution,
                       String conflictToken, List<TaskCall> open) {

    public TaskView {
        open = List.copyOf(open);
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
        if (resolution != null) {
            fields.put("resolution", resolution);
        }
        return Collections.unmodifiableMap(fields);
    }
}
