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
 * Where in a call an argument stands: {@code title} at the top, {@code
 * fields.title} under {@code fields}, and {@code apparatus[2]} for the third
 * value of a list, counted from zero (DEC-0043 R19).
 *
 * <p>A placeholder type of DEC-0043 R18, and what {@code data.offenders} and
 * {@code data.accepts} carry. Built only from a declared {@link Argument}, the
 * declared container {@code fields}, or a declared {@link Transport} part of a
 * REST request. A position is a number, so naming which of several values was
 * wrong never needs the value.
 */
public final class ArgumentPath {

    /** The {@code fields} object itself. */
    public static final ArgumentPath FIELDS = new ArgumentPath(Argument.FIELDS);

    /** The parts of a REST request that carry no argument of the declaration. */
    public enum Transport {
        /** The body, which carries a JSON object of the call's arguments. */
        BODY("body");

        private final String wireName;

        Transport(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }
    }

    private final String path;

    private ArgumentPath(String path) {
        this.path = path;
    }

    /** A declared argument at its place: under {@code fields} with its prefix. */
    public static ArgumentPath of(Argument argument) {
        return new ArgumentPath(argument.isField()
            ? Argument.FIELDS + "." + argument.name()
            : argument.name());
    }

    /** One value of a declared list argument, by its position counted from zero. */
    public static ArgumentPath of(Argument argument, int position) {
        if (position < 0) {
            throw new IllegalArgumentException("a position counts from zero");
        }
        return new ArgumentPath(of(argument).path + "[" + position + "]");
    }

    /** A declared part of a REST request. */
    public static ArgumentPath of(Transport part) {
        return new ArgumentPath(part.wireName());
    }

    /** The paths of a list of declared arguments, in their order. */
    public static List<ArgumentPath> of(List<Argument> arguments) {
        return arguments.stream().map(ArgumentPath::of).toList();
    }

    @Override
    public String toString() {
        return path;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ArgumentPath that && path.equals(that.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path);
    }
}
