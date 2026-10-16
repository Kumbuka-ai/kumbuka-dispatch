/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.domain.TaskVerb;
import org.jspecify.annotations.Nullable;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The twenty-five calls of the verb surface: the sixteen transitions and the
 * nine calls that are not transitions (TAR-0004 section 3), with the arguments
 * of concept section 3.2 and the descriptions of section 3.5.
 *
 * <p><strong>This is the single source of the surface.</strong> The MCP tool
 * list, the input schemas, the closure of every call's arguments at both
 * levels, the {@code next} names and the {@code ARGUMENT_UNKNOWN} refusal all
 * read from here; the REST routes are written by hand and a test holds them to
 * this list. The shape is a plain value type with no framework and no adapter
 * import, because the router takes the same declaration.
 *
 * <p>A transition names its row of {@link TaskVerb}, which decides it. The
 * nine others name none; the kernel's call of the same name decides them.
 *
 * <h2>Descriptions</h2>
 *
 * Each is at most {@link SurfaceDeclaration#DESCRIPTION_BUDGET} characters,
 * and what explains one argument stands at that argument. The descriptions of
 * {@code dispatch_claim} and {@code dispatch_claim_next} follow concept section
 * 3.5, worded positively (DEC-0043 R21): the sentence about the text is the
 * same in both. Every text is in {@code messages/tool.properties}, {@code
 * argument.properties} and {@code value.properties}; this enum holds the
 * references to them and no text.
 */
public enum ProcessVerb {

    // ======================================================================
    // The sixteen transitions, in the order of TAR-0004 section 3
    // ======================================================================

    SEND("send", TaskVerb.SEND, Participation.COMMISSIONER, () -> Texts.tools().send(),
        List.of(address(), conflictToken())),

    CLAIM("claim", TaskVerb.CLAIM, Participation.CANDIDATE, () -> Texts.tools().claim(),
        List.of(address(), duration(),
            idempotencyKey(() -> Texts.arguments().claimIdempotencyKey()))),

    CLAIM_NEXT("claim_next", TaskVerb.CLAIM_NEXT, Participation.CANDIDATE,
        () -> Texts.tools().claimNext(),
        List.of(scope(), selector(),
            Argument.topList(Shared.APPARATUS, ApparatusPatterns.CHARACTER_RULE,
                () -> Texts.arguments().claimNextApparatus()),
            duration(),
            idempotencyKey(() -> Texts.arguments().claimNextIdempotencyKey()))),

    RELEASE("release", TaskVerb.RELEASE, Participation.HOLDER, () -> Texts.tools().release(),
        List.of(address(), receipt(), remark(false))),

    DEFER("defer", TaskVerb.DEFER, Participation.HOLDER, () -> Texts.tools().defer(),
        List.of(address(), receipt(),
            Argument.field("not_before", Argument.STRING,
                () -> Texts.arguments().deferNotBefore()),
            remark(false))),

    RENEW("renew", TaskVerb.RENEW, Participation.HOLDER, () -> Texts.tools().renew(),
        List.of(address(), receipt(), duration())),

    ASK("ask", TaskVerb.ASK, Participation.HOLDER, () -> Texts.tools().ask(),
        List.of(address(), receipt(),
            Argument.field("question", Argument.STRING,
                () -> Texts.arguments().askQuestion()),
            Argument.field("options", Argument.ARRAY, Argument.UNSET,
                () -> Texts.arguments().askOptions()),
            Argument.field("free_text", Argument.BOOLEAN, "false",
                () -> Texts.arguments().askFreeText()))),

    ANSWER("answer", TaskVerb.ANSWER, Participation.COMMISSIONER, () -> Texts.tools().answer(),
        List.of(address(), conflictToken(),
            Argument.field("option", Argument.STRING, Argument.UNSET,
                () -> Texts.arguments().answerOption()),
            Argument.field("text", Argument.STRING, Argument.UNSET,
                () -> Texts.arguments().answerText()))),

    HOLD("hold", TaskVerb.HOLD, Participation.HOLDER, () -> Texts.tools().hold(),
        List.of(address(), receipt(),
            Argument.oneOf("reason", Argument.Placement.FIELDS, null, List.of(
                    Argument.value("dependency", () -> Texts.values().holdReasonDependency()),
                    Argument.value("external", () -> Texts.values().holdReasonExternal())),
                () -> Texts.arguments().holdReason()),
            remark(false))),

    RESUME("resume", TaskVerb.RESUME, Participation.HOLDER, () -> Texts.tools().resume(),
        List.of(address(), receipt(), duration())),

    DELIVER("deliver", TaskVerb.DELIVER, Participation.HOLDER, () -> Texts.tools().deliver(),
        List.of(address(), receipt(),
            Argument.field("text", Argument.STRING, () -> Texts.arguments().deliverText()),
            metadata(() -> Texts.arguments().deliverMetadata()))),

    REWORK("rework", TaskVerb.REWORK, Participation.COMMISSIONER, () -> Texts.tools().rework(),
        List.of(address(), conflictToken(), remark(true))),

    ACCEPT("accept", TaskVerb.ACCEPT, Participation.COMMISSIONER, () -> Texts.tools().accept(),
        List.of(address(), conflictToken(), confirmation())),

    REJECT("reject", TaskVerb.REJECT, Participation.CANDIDATE, () -> Texts.tools().reject(),
        List.of(address(), remark(true))),

    FAIL("fail", TaskVerb.FAIL, Participation.HOLDER, () -> Texts.tools().fail(),
        List.of(address(), receipt(), confirmation(), remark(true))),

    WITHDRAW("withdraw", TaskVerb.WITHDRAW, Participation.COMMISSIONER,
        () -> Texts.tools().withdraw(),
        List.of(address(), conflictToken(), confirmation(), remark(false))),

    // ======================================================================
    // The nine calls that are not transitions
    // ======================================================================

    CREATE("create", null, Participation.COMMISSIONER, () -> Texts.tools().create(),
        List.of(scope(), selector(),
            Argument.top("parent", Argument.STRING, Argument.UNSET,
                () -> Texts.arguments().createParent()),
            idempotencyKey(() -> Texts.arguments().createIdempotencyKey()),
            Argument.field("title", Argument.STRING, () -> Texts.arguments().createTitle()),
            Argument.field(Shared.APPARATUS, Argument.STRING,
                () -> Texts.arguments().createApparatus()),
            Argument.field("text", Argument.STRING, Argument.UNSET,
                () -> Texts.arguments().createText()),
            metadata(() -> Texts.arguments().createMetadata()))),

    UPDATE("update", null, Participation.COMMISSIONER, () -> Texts.tools().update(),
        List.of(address(), conflictToken(),
            Argument.field("title", Argument.STRING, Argument.UNSET,
                () -> Texts.arguments().updateTitle()),
            Argument.field(Shared.APPARATUS, Argument.STRING, Argument.UNSET,
                () -> Texts.arguments().updateApparatus()),
            Argument.field("text", Argument.STRING, Argument.UNSET,
                () -> Texts.arguments().updateText()),
            metadata(() -> Texts.arguments().updateMetadata()))),

    DELETE("delete", null, Participation.COMMISSIONER, () -> Texts.tools().delete(),
        List.of(address(), conflictToken())),

    READ("read", null, null, () -> Texts.tools().read(),
        List.of(address())),

    READ_TEXT("read_text", null, null, () -> Texts.tools().readText(),
        List.of(address(),
            Argument.oneOf("part", Argument.Placement.TOP, null, List.of(
                    Argument.value("dispatch", () -> Texts.values().readTextPartDispatch()),
                    Argument.value("return", () -> Texts.values().readTextPartReturn()),
                    Argument.value("thread", () -> Texts.values().readTextPartThread()),
                    Argument.value("addenda", () -> Texts.values().readTextPartAddenda())),
                () -> Texts.arguments().readTextPart()))),

    QUERY("query", null, null, () -> Texts.tools().query(),
        List.of(scope(), selector(),
            Argument.top("state", Argument.STRING, Argument.UNSET,
                () -> Texts.arguments().queryState()),
            Argument.top(Shared.APPARATUS, Argument.STRING, Argument.UNSET,
                () -> Texts.arguments().queryApparatus()),
            Argument.top("bracket", Argument.STRING, Argument.UNSET,
                () -> Texts.arguments().queryBracket()),
            Argument.top("address", Argument.STRING, Argument.UNSET,
                () -> Texts.arguments().queryAddress()),
            Argument.top("page_size", Argument.INTEGER,
                String.valueOf(Bound.PAGE_SIZE_DEFAULT.value()),
                () -> Texts.arguments().queryPageSize()),
            Argument.top("after", Argument.STRING, Argument.UNSET,
                () -> Texts.arguments().queryAfter()),
            Argument.oneOf("sort", Argument.Placement.TOP, "id", List.of(
                    Argument.value("id", () -> Texts.values().sortId()),
                    Argument.value("created_at", () -> Texts.values().sortCreatedAt())),
                () -> Texts.arguments().querySort()),
            Argument.oneOf("direction", Argument.Placement.TOP, "asc", List.of(
                    Argument.value("asc", () -> Texts.values().directionAsc()),
                    Argument.value("desc", () -> Texts.values().directionDesc())),
                () -> Texts.arguments().queryDirection()))),

    ANNOTATE("annotate", null, null, () -> Texts.tools().annotate(),
        List.of(address(),
            idempotencyKey(() -> Texts.arguments().annotateIdempotencyKey()),
            Argument.field("text", Argument.STRING, () -> Texts.arguments().annotateText()),
            Argument.oneOf("part", Argument.Placement.FIELDS, null, List.of(
                    Argument.value("dispatch", () -> Texts.values().annotatePartDispatch()),
                    Argument.value("return", () -> Texts.values().annotatePartReturn()),
                    Argument.value("question", () -> Texts.values().annotatePartQuestion()),
                    Argument.value("answer", () -> Texts.values().annotatePartAnswer()),
                    Argument.value(Shared.REMARK, () -> Texts.values().annotatePartRemark())),
                () -> Texts.arguments().annotatePart()))),

    RELATE("relate", null, Participation.COMMISSIONER, () -> Texts.tools().relate(),
        List.of(address(), conflictToken(),
            Argument.field("curated_in", Argument.STRING,
                () -> Texts.arguments().relateCuratedIn()))),

    UNRELATE("unrelate", null, Participation.COMMISSIONER, () -> Texts.tools().unrelate(),
        List.of(address(), conflictToken()));

    /** The prefix every call carries on the assistant surface. */
    public static final String PREFIX = "dispatch_";

    /**
     * Names the constants share. A holder of its own, because an enum constant
     * cannot name a static field of its own type that is declared after it.
     */
    private static final class Shared {

        static final String APPARATUS = "apparatus";

        static final String REMARK = "remark";

        static final String RECEIPT = "receipt";

        private Shared() {
        }
    }

    // ======================================================================
    // The arguments several calls share: one definition each, because they
    // ARE one argument and mean the same wherever they appear.
    // ======================================================================

    private static Argument scope() {
        return Argument.top("scope", Argument.STRING, () -> Texts.arguments().scope());
    }

    private static Argument selector() {
        return Argument.top("selector", Argument.STRING, () -> Texts.arguments().selector());
    }

    private static Argument address() {
        return Argument.top("address", Argument.STRING, () -> Texts.arguments().address());
    }

    private static Argument conflictToken() {
        return Argument.top("conflict_token", Argument.STRING,
            () -> Texts.arguments().conflictToken());
    }

    private static Argument receipt() {
        return Argument.top(Shared.RECEIPT, Argument.STRING, () -> Texts.arguments().receipt());
    }

    private static Argument duration() {
        return Argument.top("duration", Argument.STRING, "PT30M",
            () -> Texts.arguments().duration());
    }

    private static Argument confirmation() {
        return Argument.top("confirmation", Argument.STRING, Argument.UNSET,
            () -> Texts.arguments().confirmation());
    }

    private static Argument idempotencyKey(Supplier<String> description) {
        return Argument.top("idempotency_key", Argument.STRING, Argument.UNSET, description);
    }

    private static Argument remark(boolean required) {
        return required
            ? Argument.field(Shared.REMARK, Argument.STRING,
                () -> Texts.arguments().remarkRequired())
            : Argument.field(Shared.REMARK, Argument.STRING, Argument.UNSET,
                () -> Texts.arguments().remarkOptional());
    }

    private static Argument metadata(Supplier<String> description) {
        return Argument.field("metadata", Argument.OBJECT, Argument.UNSET, description);
    }

    private final String verb;
    /** The row of the transition table; absent for the nine calls that are no transition. */
    private final @Nullable TaskVerb transition;
    /** The part that makes the call; absent where it depends on the task. */
    private final @Nullable Participation role;
    private final Supplier<String> description;
    private final List<Argument> arguments;

    ProcessVerb(String verb, @Nullable TaskVerb transition, @Nullable Participation role,
                Supplier<String> description, List<Argument> arguments) {
        this.verb = verb;
        this.transition = transition;
        this.role = role;
        this.description = description;
        this.arguments = List.copyOf(arguments);
    }

    /** The verb as TAR-0004 section 3 names it, without a prefix: the name on REST. */
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

    /** The row of the transition table that decides this call; empty for the nine others. */
    public Optional<TaskVerb> transition() {
        return Optional.ofNullable(transition);
    }

    public boolean isTransition() {
        return transition != null;
    }

    /**
     * The part that makes this call; empty where it depends on the task:
     * reading is open to whoever may see a task, an addendum to whoever wrote
     * the text it supplements.
     */
    public Optional<Participation> role() {
        return Optional.ofNullable(role);
    }

    /** What the call does, from its bundle (concept section 3.5, DEC-0043 R3 and R21). */
    public String description() {
        return description.get();
    }

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

    /** The declared argument of this name at one level; empty where there is none. */
    public Optional<Argument> argument(String name, Argument.Placement placement) {
        return arguments.stream()
            .filter(a -> a.name().equals(name) && a.placement() == placement)
            .findFirst();
    }

    /** The declared argument of this name at one level; a defect where there is none. */
    public Argument declared(String name, Argument.Placement placement) {
        return argument(name, placement).orElseThrow(() ->
            new IllegalStateException(call() + " declares no " + name));
    }

    /** Every declared top-level name, {@code fields} included where the call writes. */
    public List<String> topNames() {
        List<String> names = new java.util.ArrayList<>(
            topArguments().stream().map(Argument::name).toList());
        if (hasFields()) {
            names.add(Argument.FIELDS);
        }
        return List.copyOf(names);
    }

    /** The call this transition row is declared as. */
    public static ProcessVerb of(TaskVerb transition) {
        return Arrays.stream(values())
            .filter(v -> v.transition == transition)
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "no call is declared for the transition " + transition));
    }

    /** The call of this name on one surface; empty when no call goes by it. */
    public static Optional<ProcessVerb> byName(Surface surface, String name) {
        for (ProcessVerb call : values()) {
            if (call.on(surface).equals(name)) {
                return Optional.of(call);
            }
        }
        return Optional.empty();
    }

    /** Every call name on one surface, in declaration order. */
    public static List<String> names(Surface surface) {
        return Arrays.stream(values()).map(v -> v.on(surface)).toList();
    }

    /**
     * The effect class of this call, by the router's name for it (ADR-0039):
     * the form its HTTP request takes. {@code RestRouteFormTest} holds the
     * REST routes of this service to it.
     */
    public EffectClass effectClass() {
        return switch (this) {
            case CREATE -> EffectClass.CREATE;
            case UPDATE -> EffectClass.FIELD_WRITE;
            case DELETE -> EffectClass.ITEM_DELETE;
            case READ -> EffectClass.ITEM_READ;
            case READ_TEXT -> EffectClass.ITEM_READ_CALL;
            case QUERY -> EffectClass.COLLECTION_READ;
            case CLAIM_NEXT -> EffectClass.COLLECTION_COMPOUND;
            default -> EffectClass.ITEM_TRANSITION;
        };
    }

    /** The shape of this call's answer. */
    public AnswerShape answerShape() {
        return switch (this) {
            case CREATE -> AnswerShape.TASK_LEAN_CREATED;
            case CLAIM, CLAIM_NEXT -> AnswerShape.CLAIM_RECEIPT;
            case READ -> AnswerShape.TASK_HEAD;
            case QUERY -> AnswerShape.TASK_LISTING;
            case READ_TEXT -> AnswerShape.TASK_TEXT;
            case DELETE -> AnswerShape.TASK_DELETED;
            default -> AnswerShape.TASK_LEAN;
        };
    }

    /** Whether this call only reads: it leaves the scope as it is. */
    public boolean reads() {
        return this == READ || this == READ_TEXT || this == QUERY;
    }

    /**
     * The reasons a refusal of this call may name, in the order of the codes.
     *
     * <p>Read off the declaration: every call can be refused for its address,
     * its arguments, its caller and its scope; a call that writes for the
     * scope's right and lock; a call on a task for the task's state; and each
     * argument for the refusals that belong to it -- a receipt, a conflict
     * token, a confirmation, a duration, a key, the page of a listing.
     */
    public java.util.Set<RefusalCode> refusals() {
        java.util.Set<RefusalCode> codes = java.util.EnumSet.of(
            RefusalCode.NOT_FOUND, RefusalCode.PAYLOAD_MALFORMED, RefusalCode.ADDRESS_MALFORMED,
            RefusalCode.ARGUMENT_UNKNOWN, RefusalCode.ARGUMENT_MISSING,
            RefusalCode.ARGUMENT_INVALID, RefusalCode.HEADER_INVALID, RefusalCode.ROLE_INSUFFICIENT,
            RefusalCode.SELECTOR_UNDECLARED, RefusalCode.SELECTOR_WITHDRAWN,
            RefusalCode.SCOPE_KIND_UNSUPPORTED, RefusalCode.VERB_NOT_CARRIED,
            RefusalCode.UNEXPECTED_FAILURE);
        if (!reads()) {
            codes.add(RefusalCode.SCOPE_READ_ONLY);
            codes.add(RefusalCode.SCOPE_LOCKED);
        }
        if (this != READ && this != READ_TEXT && this != QUERY && this != CLAIM_NEXT) {
            codes.add(RefusalCode.TRANSITION_REFUSED);
        }
        if (argument(Shared.RECEIPT, Argument.Placement.TOP).isPresent() || this == READ_TEXT) {
            codes.add(RefusalCode.CLAIM_HELD);
            codes.add(RefusalCode.CLAIM_ABSENT);
        }
        if (argument(Shared.RECEIPT, Argument.Placement.TOP).isPresent()) {
            codes.add(RefusalCode.RECEIPT_WRONG);
        }
        if (argument("conflict_token", Argument.Placement.TOP).isPresent()) {
            codes.add(RefusalCode.CONFLICT_TOKEN_MISSING);
            codes.add(RefusalCode.CONFLICT_TOKEN_STALE);
        }
        if (argument("confirmation", Argument.Placement.TOP).isPresent()) {
            codes.add(RefusalCode.CHILDREN_UNFINISHED);
        }
        if (argument("option", Argument.Placement.FIELDS).isPresent()) {
            codes.add(RefusalCode.OPTION_UNOFFERED);
        }
        if (argument("idempotency_key", Argument.Placement.TOP).isPresent()) {
            codes.add(RefusalCode.IDEMPOTENCY_KEY_REUSED);
        }
        if (this == CLAIM) {
            codes.add(RefusalCode.DEFERRAL_PENDING);
        }
        if (this == CREATE) {
            codes.add(RefusalCode.ROOT_UNSENT);
            codes.add(RefusalCode.ROOT_CLOSED);
        }
        if (this == DELETE) {
            codes.add(RefusalCode.CHILDREN_PRESENT);
        }
        if (this == CLAIM_NEXT) {
            codes.add(RefusalCode.DRAW_EMPTY);
        }
        if (this == QUERY) {
            codes.add(RefusalCode.PAGE_SIZE_REJECTED);
            codes.add(RefusalCode.CURSOR_MALFORMED);
            codes.add(RefusalCode.CURSOR_FOREIGN);
            codes.add(RefusalCode.SORT_UNDECLARED);
        }
        return java.util.Collections.unmodifiableSet(codes);
    }

    /** The role as the declaration publishes it. */
    public String roleName() {
        return role == null ? "any" : role.name().toLowerCase(Locale.ROOT);
    }
}
