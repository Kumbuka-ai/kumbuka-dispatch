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
 * An instance as an act on it answers it: its address, its head, its
 * conflict token and the calls open to the caller (C6.1).
 *
 * <p>The head is what a listing would answer of it, its system fields; the
 * input text, the field values and the references are read by the calls that
 * read them, in later orders. A closed instance has no call open and is
 * finished, so it waits for nothing.
 *
 * @param id            the instance's surrogate, for the log line of a write
 *                      and never on the wire
 * @param address       its complete address
 * @param version       the number of the version it is bound to
 * @param title         its title; absent on an instance whose stock recorded none
 * @param state         its state as the store names it
 * @param resolution    how it was closed; absent while it runs
 * @param conflictToken the time of its last act, rendered, opaque to a caller
 * @param open          the calls open to the caller
 */
public record InstanceView(long id, InstanceAddress address, int version,
                           @Nullable String title, String state, @Nullable String resolution,
                           String conflictToken, List<InstanceCall> open) {

    public InstanceView {
        open = List.copyOf(open);
    }

    /**
     * The fields of the answer, in their order: the one mapping of an
     * instance to the {@code fields} a caller reads (C7.1). A member without
     * a value is left out.
     */
    public Map<String, Object> fields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("selector", address.selector());
        fields.put("number", address.number());
        fields.put("version", version);
        if (title != null) {
            fields.put("title", title);
        }
        fields.put("state", state);
        if (resolution != null) {
            fields.put("resolution", resolution);
        }
        return Collections.unmodifiableMap(fields);
    }
}
