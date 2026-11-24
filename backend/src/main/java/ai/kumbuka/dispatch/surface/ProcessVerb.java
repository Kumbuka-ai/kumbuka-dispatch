/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.domain.DispatchPermission;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The calls of the verb surface: what each is named, the permission it checks
 * about its caller, the form its HTTP request takes, what it answers and the
 * arguments it takes.
 *
 * <p><strong>One call is declared.</strong> The service is rebuilt on the
 * store line, and a call is declared in the same change that binds it to the
 * new kernel, never before: a call declared without a binding would be offered
 * and could not be made. {@code read_definition} is the first.
 *
 * <p><strong>This is the single source of the surface.</strong> The MCP tool
 * list, the input schemas, the closure of every call's arguments and the
 * declaration read from here; the REST routes are written by hand and {@code
 * RestRouteFormTest} holds them to this list. The shape is a plain value type
 * with no framework and no adapter import, because the router takes the same
 * declaration.
 *
 * <h2>Permissions</h2>
 *
 * Every call names the one permission it checks about its caller, and checks
 * nothing else about the caller (ADR-0053/C10, {@link #permissions}).
 *
 * <h2>Descriptions</h2>
 *
 * Each is at most {@link SurfaceDeclaration#DESCRIPTION_BUDGET} characters,
 * and what explains one argument stands at that argument. Every text is in
 * {@code messages/tool.properties}, {@code argument.properties} and {@code
 * value.properties}; a call holds the references to them and no text.
 */
public enum ProcessVerb {

    /**
     * Reads a seeded version of a selector's definition: its schema with its
     * enumerations, its activities with their result contracts, its start
     * contract, and the enumerations of the platform's system fields
     * (DEC-0056/C5, C6). It writes nothing.
     */
    READ_DEFINITION("read_definition", DispatchPermission.INSTANCE_READ,
        EffectClass.COLLECTION_READ, AnswerShape.DEFINITION,
        () -> Texts.tools().readDefinition(),
        List.of(
            Argument.top("scope", Argument.STRING, () -> Texts.arguments().scope()),
            Argument.top("selector", Argument.STRING, () -> Texts.arguments().selector()),
            Argument.top("version", Argument.INTEGER, Argument.UNSET,
                () -> Texts.arguments().readDefinitionVersion())));

    /** The prefix every call carries on the assistant surface. */
    public static final String PREFIX = "dispatch_";

    private final String verb;
    private final DispatchPermission permission;
    private final EffectClass effectClass;
    private final AnswerShape answerShape;
    private final Supplier<String> description;
    private final List<Argument> arguments;

    ProcessVerb(String verb, DispatchPermission permission, EffectClass effectClass,
                AnswerShape answerShape, Supplier<String> description, List<Argument> arguments) {
        this.verb = verb;
        this.permission = permission;
        this.effectClass = effectClass;
        this.answerShape = answerShape;
        this.description = description;
        this.arguments = List.copyOf(arguments);
    }

    /** The verb without a prefix: the name on REST. */
    public String verb() {
        return verb;
    }

    /** The name on the assistant surface: the verb, prefixed {@code dispatch_}. */
    public String call() {
        return PREFIX + verb;
    }

    /** The name this call goes by on one surface. */
    public String on(Surface surface) {
        return surface == Surface.MCP ? call() : verb;
    }

    /**
     * The permission this call checks about its caller, and nothing else
     * about the caller: exactly one (ADR-0053/C10).
     */
    public List<DispatchPermission> permissions() {
        return List.of(permission);
    }

    /**
     * The effect class of this call, by the router's name for it (ADR-0039):
     * the form its HTTP request takes. {@code RestRouteFormTest} holds the
     * REST routes of this service to it.
     */
    public EffectClass effectClass() {
        return effectClass;
    }

    /** The shape of this call's answer. */
    public AnswerShape answerShape() {
        return answerShape;
    }

    /** What the call does, from its bundle (concept section 3.5, DEC-0043 R3 and R21). */
    public String description() {
        return description.get();
    }

    /** The arguments the call takes, in their declared order. */
    public List<Argument> arguments() {
        return arguments;
    }

    /** The arguments that travel at the top level. */
    public List<Argument> topArguments() {
        return arguments.stream().filter(a -> !a.isField()).toList();
    }

    /** The arguments that travel under {@code fields}. */
    public List<Argument> fieldArguments() {
        return arguments.stream().filter(Argument::isField).toList();
    }

    /** Whether this call carries a {@code fields} object at all. */
    public boolean hasFields() {
        return !fieldArguments().isEmpty();
    }

    /** The declared top-level argument of this name; empty where there is none. */
    public Optional<Argument> argument(String name) {
        return topArguments().stream().filter(a -> a.name().equals(name)).findFirst();
    }

    /** The declared top-level argument of this name; a defect where there is none. */
    public Argument declared(String name) {
        return argument(name).orElseThrow(() ->
            new IllegalStateException(call() + " declares no " + name));
    }

    /**
     * The reasons a refusal of this call may name, in the order of the codes.
     *
     * <p>Read off what the call is: a read of a collection by its scope and
     * selector, which is refused for its form and its arguments, for the
     * header that names the surface, for a scope or a definition the caller
     * cannot see, for a scope of a kind this service does not serve, for a
     * caller without the call's permission, at an address that carries
     * another verb, and for a fault of ours.
     */
    public Set<RefusalCode> refusals() {
        return Collections.unmodifiableSet(EnumSet.of(
            RefusalCode.ADDRESS_MALFORMED, RefusalCode.ARGUMENT_UNKNOWN,
            RefusalCode.ARGUMENT_MISSING, RefusalCode.ARGUMENT_INVALID,
            RefusalCode.HEADER_INVALID, RefusalCode.ROLE_INSUFFICIENT, RefusalCode.NOT_FOUND,
            RefusalCode.VERB_NOT_CARRIED, RefusalCode.SCOPE_KIND_UNSUPPORTED,
            RefusalCode.UNEXPECTED_FAILURE));
    }

    /** The call of this name on one surface; empty when no call goes by it. */
    public static Optional<ProcessVerb> byName(Surface surface, String name) {
        return Arrays.stream(values()).filter(call -> call.on(surface).equals(name)).findFirst();
    }

    /** Every call name on one surface, in declaration order. */
    public static List<String> names(Surface surface) {
        return Arrays.stream(values()).map(v -> v.on(surface)).toList();
    }
}
