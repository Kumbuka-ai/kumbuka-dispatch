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
 * The declared name of an argument, without its place: {@code title}, not
 * {@code fields.title}.
 *
 * <p>A placeholder type of DEC-0043 R18. Built only from a declared {@link
 * Argument} or the one declared container {@code fields}; there is no factory
 * that takes a string, so a name that arrived undeclared cannot become one.
 */
public final class ArgumentName {

    /** The one nested object a call may carry. */
    public static final ArgumentName FIELDS = new ArgumentName(Argument.FIELDS);

    private final String name;

    private ArgumentName(String name) {
        this.name = name;
    }

    public static ArgumentName of(Argument argument) {
        return new ArgumentName(argument.name());
    }

    /** The names of a list of declared arguments, in their order. */
    public static List<ArgumentName> of(List<Argument> arguments) {
        return arguments.stream().map(ArgumentName::of).toList();
    }

    @Override
    public String toString() {
        return name;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ArgumentName that && name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return Objects.hash(name);
    }
}
