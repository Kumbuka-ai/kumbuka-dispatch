/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import java.util.List;
import java.util.Objects;

/**
 * The name of a call as one surface names it: {@code dispatch_claim} on the
 * assistant surface, {@code claim} on REST.
 *
 * <p>One of the five types a text addressed to a caller may take as a
 * placeholder (DEC-0043 R18). It is built only from the declaration, never
 * from a request: the constructor is private and every factory takes a
 * declared {@link ProcessVerb}. A name a call does not declare therefore has
 * no way into a message, a refusal or a step of {@code next}.
 */
public final class VerbName {

    private final String name;

    private VerbName(String name) {
        this.name = name;
    }

    /** The call as {@code surface} names it. */
    public static VerbName of(ProcessVerb verb, Surface surface) {
        return new VerbName(verb.on(surface));
    }

    /** Every call of the declaration as {@code surface} names it, in declaration order. */
    public static List<VerbName> all(Surface surface) {
        return java.util.Arrays.stream(ProcessVerb.values()).map(v -> of(v, surface)).toList();
    }

    @Override
    public String toString() {
        return name;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof VerbName that && name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }
}
