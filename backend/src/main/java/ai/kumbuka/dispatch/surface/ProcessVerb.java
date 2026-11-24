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
 * <p><strong>Six calls are declared.</strong> The service is rebuilt on the
 * store line, and a call is declared in the same change that binds it to the
 * new kernel, never before: a call declared without a binding would be offered
 * and could not be made. {@code read_definition} came first; the acts on an
 * instance follow it -- {@code start}, {@code conclude}, {@code withdraw},
 * {@code reference_attach} and {@code reference_detach} (REA-0010, catalogue
 * "Acts on an instance"). {@code withdraw} is the call of the catalogue for an
 * instance and for a task; it takes the address of an instance alone until
 * the order that binds it for a task.
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
                () -> Texts.arguments().readDefinitionVersion()))),

    /**
     * Starts an instance of a selector, bound to the version in force, with
     * its title, an input text, a trigger reference, references and the field
     * values of the start contract (D4, D12, D18, D19).
     */
    START("start", DispatchPermission.INSTANCE_START, EffectClass.COLLECTION_COMPOUND,
        AnswerShape.INSTANCE, () -> Texts.tools().start(),
        List.of(
            Argument.top("scope", Argument.STRING, () -> Texts.arguments().scope()),
            Argument.top("selector", Argument.STRING, () -> Texts.arguments().selector()),
            Argument.field("title", Argument.STRING, () -> Texts.arguments().startTitle()),
            Argument.field("input", Argument.STRING, Argument.UNSET,
                () -> Texts.arguments().startInput()),
            Argument.field("trigger", Argument.STRING, Argument.UNSET,
                () -> Texts.arguments().startTrigger()),
            Argument.fieldList("items", Argument.REFERENCE_PATTERN,
                () -> Texts.arguments().startItems()),
            Argument.fieldList("references", Argument.REFERENCE_PATTERN,
                () -> Texts.arguments().startReferences()),
            Argument.field("values", Argument.OBJECT, Argument.UNSET,
                () -> Texts.arguments().startValues()))),

    /** Closes a running instance as concluded, with its return. */
    CONCLUDE("conclude", DispatchPermission.INSTANCE_CONCLUDE, EffectClass.ITEM_TRANSITION,
        AnswerShape.INSTANCE, () -> Texts.tools().conclude(), closing()),

    /** Closes a running instance as withdrawn, with its return. */
    WITHDRAW("withdraw", DispatchPermission.INSTANCE_WITHDRAW, EffectClass.ITEM_TRANSITION,
        AnswerShape.INSTANCE, () -> Texts.tools().withdraw(), closing()),

    /** Sets a reference of a running instance, by an act of the kind refer (D12). */
    REFERENCE_ATTACH("reference_attach", DispatchPermission.INSTANCE_REFER,
        EffectClass.ITEM_TRANSITION, AnswerShape.INSTANCE,
        () -> Texts.tools().referenceAttach(),
        reference(() -> Texts.arguments().attachValue())),

    /** Ends a reference of a running instance, by an act of the kind unrefer (D12, D22). */
    REFERENCE_DETACH("reference_detach", DispatchPermission.INSTANCE_REFER,
        EffectClass.ITEM_TRANSITION, AnswerShape.INSTANCE,
        () -> Texts.tools().referenceDetach(),
        reference(() -> Texts.arguments().detachValue()));

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

    /** The arguments of the two closings: the address, the fences, and the return. */
    private static List<Argument> closing() {
        return List.of(
            Argument.top("address", Argument.STRING, () -> Texts.arguments().instanceAddress()),
            Argument.top("conflict_token", Argument.STRING, Argument.UNSET,
                () -> Texts.arguments().instanceConflictToken()),
            Argument.top("confirmation", Argument.STRING, Argument.UNSET,
                () -> Texts.arguments().instanceConfirmation()),
            Argument.field("text", Argument.STRING, () -> Texts.arguments().returnText()),
            Argument.oneOf("verdict", Argument.Placement.FIELDS, Argument.UNSET, List.of(
                    Argument.value("green", () -> Texts.values().verdictGreen()),
                    Argument.value("red", () -> Texts.values().verdictRed()),
                    Argument.value("question", () -> Texts.values().verdictQuestion())),
                () -> Texts.arguments().returnVerdict()),
            Argument.field("outcome", Argument.STRING, Argument.UNSET,
                () -> Texts.arguments().returnOutcome()),
            Argument.field("translation_basis", Argument.STRING, Argument.UNSET,
                () -> Texts.arguments().returnTranslationBasis()));
    }

    /** The arguments of the two calls on a reference: the address, its kind and its value. */
    private static List<Argument> reference(Supplier<String> value) {
        return List.of(
            Argument.top("address", Argument.STRING, () -> Texts.arguments().instanceAddress()),
            Argument.oneOf("kind", Argument.Placement.FIELDS, null, List.of(
                    Argument.value("item", () -> Texts.values().referenceKindItem()),
                    Argument.value("reference", () -> Texts.values().referenceKindReference())),
                () -> Texts.arguments().referenceKind()),
            Argument.field("value", Argument.STRING, value));
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

    /** The declared argument of this name under {@code fields}; empty where there is none. */
    public Optional<Argument> field(String name) {
        return fieldArguments().stream().filter(a -> a.name().equals(name)).findFirst();
    }

    /** The declared top-level argument of this name; a defect where there is none. */
    public Argument declared(String name) {
        return argument(name).orElseThrow(() ->
            new IllegalStateException(call() + " declares no " + name));
    }

    /** The declared argument of this name under {@code fields}; a defect where there is none. */
    public Argument declaredField(String name) {
        return field(name).orElseThrow(() ->
            new IllegalStateException(call() + " declares no fields." + name));
    }

    /**
     * The reasons a refusal of this call may name, in the order of the codes.
     *
     * <p>Read off what the call is. Every call is refused for its form and its
     * arguments, for the header that names the surface, for a scope or an
     * object the caller cannot see, for a scope of a kind this service does
     * not serve, for a caller without the call's permission, at an address
     * that carries another verb, and for a fault of ours. A call that writes
     * is refused besides in a locked scope and in one the caller may only
     * read. A call on an instance is refused for an instance that is closed;
     * a closing besides without a conflict token, with a stale one, and for
     * unfinished tasks until it carries their confirmation.
     */
    public Set<RefusalCode> refusals() {
        Set<RefusalCode> refusals = EnumSet.of(
            RefusalCode.ADDRESS_MALFORMED, RefusalCode.ARGUMENT_UNKNOWN,
            RefusalCode.ARGUMENT_MISSING, RefusalCode.ARGUMENT_INVALID,
            RefusalCode.HEADER_INVALID, RefusalCode.ROLE_INSUFFICIENT, RefusalCode.NOT_FOUND,
            RefusalCode.VERB_NOT_CARRIED, RefusalCode.SCOPE_KIND_UNSUPPORTED,
            RefusalCode.UNEXPECTED_FAILURE);
        if (this != READ_DEFINITION) {
            refusals.addAll(EnumSet.of(RefusalCode.PAYLOAD_MALFORMED, RefusalCode.SCOPE_LOCKED,
                RefusalCode.SCOPE_READ_ONLY));
        }
        if (effectClass == EffectClass.ITEM_TRANSITION) {
            refusals.add(RefusalCode.TRANSITION_REFUSED);
        }
        if (this == CONCLUDE || this == WITHDRAW) {
            refusals.addAll(EnumSet.of(RefusalCode.CONFLICT_TOKEN_MISSING,
                RefusalCode.CONFLICT_TOKEN_STALE, RefusalCode.CHILDREN_UNFINISHED));
        }
        return Collections.unmodifiableSet(refusals);
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
