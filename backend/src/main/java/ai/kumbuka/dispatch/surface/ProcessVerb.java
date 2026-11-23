/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.domain.DispatchPermission;

import java.util.List;

/**
 * A call of the verb surface: what it is named, the permission it checks
 * about its caller, what it does and the arguments it takes.
 *
 * <p><strong>No call is declared yet.</strong> The service is rebuilt on the
 * store line, and a call is declared in the same change that binds it to the
 * new kernel, never before: a call declared without a binding would be offered
 * and could not be made. Until the first one is, {@link #values()} answers
 * none, and the tool list and the declaration's {@code calls} are empty.
 *
 * <p><strong>This is the single source of the surface.</strong> The MCP tool
 * list, the input schemas and the declaration read from here. The shape is a
 * plain value type with no framework and no adapter import, because the router
 * takes the same declaration.
 *
 * <h2>Permissions</h2>
 *
 * Every call names the permission it checks about its caller, and checks
 * nothing else about the caller ({@link #permissions}).
 *
 * <h2>Descriptions</h2>
 *
 * Each is at most {@link SurfaceDeclaration#DESCRIPTION_BUDGET} characters,
 * and what explains one argument stands at that argument. Every text is in
 * {@code messages/tool.properties}, {@code argument.properties} and {@code
 * value.properties}; a call holds the references to them and no text.
 */
public interface ProcessVerb {

    /** The prefix every call carries on the assistant surface. */
    String PREFIX = "dispatch_";

    /** The declared calls, in their order: none until the first call is bound. */
    static ProcessVerb[] values() {
        return new ProcessVerb[0];
    }

    /** The verb without a prefix: the name on REST. */
    String verb();

    /**
     * The permission this call checks about its caller, and nothing else
     * about the caller: one, or two of which the caller holds either.
     */
    List<DispatchPermission> permissions();

    /** What the call does, from its bundle (concept section 3.5, DEC-0043 R3 and R21). */
    String description();

    /** The arguments the call takes, in their declared order. */
    List<Argument> arguments();

    /** The name on the assistant surface: the verb, prefixed {@code dispatch_}. */
    default String call() {
        return PREFIX + verb();
    }

    /** The name this call goes by on one surface. */
    default String on(Surface surface) {
        return surface == Surface.MCP ? call() : verb();
    }

    /** The arguments that travel at the top level. */
    default List<Argument> topArguments() {
        return arguments().stream().filter(a -> !a.isField()).toList();
    }

    /** The arguments that travel under {@code fields}. */
    default List<Argument> fieldArguments() {
        return arguments().stream().filter(Argument::isField).toList();
    }

    /** Whether this call carries a {@code fields} object at all. */
    default boolean hasFields() {
        return !fieldArguments().isEmpty();
    }
}
