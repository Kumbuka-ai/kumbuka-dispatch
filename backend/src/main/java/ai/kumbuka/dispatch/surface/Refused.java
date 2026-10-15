/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.surface.ReasonCatalogue.Message;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A refusal in the shape TAR-0004 section 7 fixes: a stable reason, a message
 * that explains itself, and the data a caller acts on — the call as it was
 * made, the state, and the way out.
 *
 * <p>Raised at the surface, never in the kernel. The kernel keeps its own
 * typed refusals and {@link ReasonMapping} turns each into one of these at the
 * boundary; the kernel's own message is discarded, because it names addresses
 * in short form and speaks the kernel's words.
 *
 * <p>The message is one declared {@link Message} of {@link ReasonCatalogue},
 * rendered by its method of {@link RefusalMessages}, so one refusal is worded
 * one way wherever it is raised and its text is in the bundle file alone.
 *
 * <h2>Only the platform's vocabulary (DEC-0043 R18, R19)</h2>
 *
 * No factory here takes a string. A call is a {@link VerbName}, an argument a
 * {@link ArgumentPath}, a form a {@link Form}; each is built from the
 * declaration and from nothing a caller sends. So no refusal can carry the
 * caller's input or the tenant's vocabulary, in its message or in its {@code
 * data}: what cannot be passed in cannot come back. A name the call does not
 * declare is answered with the names it does declare, under {@code
 * data.accepts}; a wrong value of several is named by its position, as
 * {@code apparatus[2]}.
 *
 * <p><strong>A refusal of a call's arguments names them in {@code
 * data.offenders}</strong> (DEC-0042), each with its path. A refusal that is
 * about no argument carries no {@code offenders}: the unfinished children of a
 * bracket root stand under {@code data.children}.
 */
public class Refused extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Keys of the refusal's {@code data}. */
    public static final String ATTEMPTED = "attempted";
    public static final String STATE = "state";
    public static final String NEXT = "next";
    public static final String WAITING_FOR = "waiting_for";
    public static final String OFFENDERS = "offenders";
    public static final String ACCEPTS = "accepts";
    public static final String CHILDREN = "children";
    public static final String CONFIRMATION = "confirmation";
    public static final String CONFLICT_TOKEN = "conflict_token";
    public static final String REFERENCE = "reference";

    private final transient RefusalCode code;
    private final transient Map<String, Object> data;

    private Refused(Message message, String text, Map<String, Object> data) {
        super(text);
        this.code = message.code();
        this.data = data == null ? null : java.util.Collections.unmodifiableMap(
            new LinkedHashMap<>(data));
    }

    public RefusalCode code() {
        return code;
    }

    /**
     * The refusal's data, or null where it carries none.
     *
     * <p>Null and not an empty map for {@link RefusalCode#NOT_FOUND}, which
     * must be byte-identical across its causes; absent is the only shape that
     * cannot drift.
     */
    public Map<String, Object> data() {
        return data;
    }

    /**
     * Where a refused call stood: the task's state as a caller reads it, the
     * calls open to the caller with one sentence each, and what the task waits
     * for where none is.
     */
    public record Situation(String state, List<NextList.Step> next, String waitingFor) {

        public Situation {
            next = next == null ? List.of() : List.copyOf(next);
        }
    }

    /** Whom a call belongs to, as a refusal of the caller's part names it. */
    public enum Part {
        COMMISSIONER(Message.ROLE_COMMISSIONER),
        EXECUTOR(Message.ROLE_EXECUTOR),
        HOLDER(Message.ROLE_HOLDER),
        AUTHOR(Message.ROLE_AUTHOR),
        COMMISSIONER_OTHER_THAN_THE_DELIVERER(Message.ROLE_OTHER_THAN_THE_DELIVERER),
        CAPACITY(Message.ROLE_CAPACITY);

        private final Message message;

        Part(Message message) {
            this.message = message;
        }

        private String text(VerbName call) {
            RefusalMessages refusals = Texts.refusals();
            return switch (this) {
                case COMMISSIONER -> refusals.roleCommissioner(call);
                case EXECUTOR -> refusals.roleExecutor(call);
                case HOLDER -> refusals.roleHolder(call);
                case AUTHOR -> refusals.roleAuthor(call);
                case COMMISSIONER_OTHER_THAN_THE_DELIVERER ->
                    refusals.roleOtherThanTheDeliverer(call);
                case CAPACITY -> refusals.roleCapacity(call);
            };
        }
    }

    /** Why a holder's call finds the caller without the task. */
    public enum Unheld {
        OTHER(Message.HOLDER_OTHER, Message.TEXT_HOLDER_OTHER),
        NOBODY(Message.HOLDER_NOBODY, Message.TEXT_HOLDER_NOBODY),
        LAPSED(Message.HOLDER_LAPSED, Message.TEXT_HOLDER_LAPSED);

        private final Message call;
        private final Message text;

        Unheld(Message call, Message text) {
            this.call = call;
            this.text = text;
        }

        private String text(VerbName refused, boolean reading) {
            RefusalMessages refusals = Texts.refusals();
            return switch (this) {
                case OTHER -> reading ? refusals.textHolderOther(refused)
                    : refusals.holderOther(refused);
                case NOBODY -> reading ? refusals.textHolderNobody(refused)
                    : refusals.holderNobody(refused);
                case LAPSED -> reading ? refusals.textHolderLapsed(refused)
                    : refusals.holderLapsed(refused);
            };
        }
    }

    /** A call that, made where it does not apply, says where it does: one per form of route. */
    public enum Route {
        CREATE(Message.ROUTE_CREATE),
        QUERY(Message.ROUTE_QUERY),
        ON_A_BRACKET_KIND(Message.ROUTE_ON_A_BRACKET_KIND),
        READ(Message.ROUTE_READ),
        READ_CALL(Message.ROUTE_READ_CALL),
        UPDATE(Message.ROUTE_UPDATE),
        DELETE(Message.ROUTE_DELETE),
        ON_A_TASK(Message.ROUTE_ON_A_TASK);

        private final Message message;

        Route(Message message) {
            this.message = message;
        }

        private String text(VerbName call) {
            RefusalMessages refusals = Texts.refusals();
            return switch (this) {
                case CREATE -> refusals.routeCreate(call);
                case QUERY -> refusals.routeQuery(call);
                case ON_A_BRACKET_KIND -> refusals.routeOnABracketKind(call);
                case READ -> refusals.routeRead(call);
                case READ_CALL -> refusals.routeReadCall(call);
                case UPDATE -> refusals.routeUpdate(call);
                case DELETE -> refusals.routeDelete(call);
                case ON_A_TASK -> refusals.routeOnATask(call);
            };
        }
    }

    /** The two arguments of a combination a call needs one of. */
    public enum Missing {
        OPTIONS(Message.ARGUMENT_MISSING_OPTIONS, "options"),
        ANSWER(Message.ARGUMENT_MISSING_ANSWER, "option"),
        CHANGE(Message.ARGUMENT_MISSING_CHANGE, null);

        private final Message message;
        private final String offender;

        Missing(Message message, String offender) {
            this.message = message;
            this.offender = offender;
        }

        private String text(VerbName call) {
            RefusalMessages refusals = Texts.refusals();
            return switch (this) {
                case OPTIONS -> refusals.argumentMissingOptions(call);
                case ANSWER -> refusals.argumentMissingAnswer(call);
                case CHANGE -> refusals.argumentMissingChange(call);
            };
        }
    }

    // ======================================================================
    // The one that says nothing
    // ======================================================================

    /**
     * The deliberately indistinguishable refusal: nothing there, a scope the
     * caller may not see, and an address that cannot be routed answer alike.
     * Takes no arguments, which is the enforcement.
     */
    public static Refused notFound() {
        return new Refused(Message.NOT_FOUND, Texts.refusals().notFound(), null);
    }

    // ======================================================================
    // The ones about a task the caller may see: they carry its situation
    // ======================================================================

    /** The state, or the condition on the hold, does not permit the call. */
    public static Refused ofState(VerbName call, Situation at, boolean closed) {
        RefusalMessages refusals = Texts.refusals();
        if (closed) {
            return new Refused(Message.STATE_CLOSED, refusals.stateClosed(call),
                situation(call, at));
        }
        if (at.next().isEmpty()) {
            return new Refused(Message.STATE_NOTHING_OPEN, refusals.stateNothingOpen(call),
                situation(call, at));
        }
        return new Refused(Message.STATE_DOES_NOT_ALLOW, refusals.stateDoesNotAllow(call,
            at.next().stream().map(NextList.Step::name).toList()), situation(call, at));
    }

    /** The call belongs to another part. */
    public static Refused ofRole(VerbName call, Situation at, Part part) {
        return new Refused(part.message, part.text(call), situation(call, at));
    }

    /** The call belongs to another part, and no task is there to name a state of. */
    public static Refused ofRole(VerbName call, Part part) {
        return new Refused(part.message, part.text(call), attempted(call));
    }

    /**
     * The call is the holder's and the caller does not hold the task.
     *
     * @param reading whether the call reads the text, which an executor may
     *                only of a task it holds
     */
    public static Refused notTheHolder(VerbName call, Situation at, Unheld why,
                                       boolean reading) {
        return new Refused(reading ? why.text : why.call, why.text(call, reading),
            situation(call, at));
    }

    /** A receipt arrived and is not the one the task holds. */
    public static Refused receiptWrong(VerbName call, Situation at) {
        return new Refused(Message.RECEIPT_WRONG, Texts.refusals().receiptWrong(call),
            situation(call, at));
    }

    /**
     * The call would close a bracket root with unfinished children.
     *
     * @param children     each unfinished child: complete address, state, and
     *                     the calls open to the caller on it
     * @param confirmation what the call is repeated with to close them all
     * @param stale        whether the call carried a confirmation that no
     *                     longer holds
     */
    public static Refused childrenNotFinished(VerbName call, Situation at,
                                              List<Map<String, Object>> children,
                                              String confirmation, boolean stale) {
        Map<String, Object> data = situation(call, at);
        data.put(CHILDREN, List.copyOf(children));
        data.put(CONFIRMATION, confirmation);
        return stale
            ? new Refused(Message.CHILDREN_CONFIRMATION_STALE,
                Texts.refusals().childrenConfirmationStale(call), data)
            : new Refused(Message.CHILDREN_NOT_FINISHED,
                Texts.refusals().childrenNotFinished(call), data);
    }

    /**
     * A claim on a task deferred until an instant that has not come. The
     * instant is named by the read that shows it, never in the refusal.
     */
    public static Refused deferralPending(VerbName call, VerbName read, Situation at) {
        return new Refused(Message.DEFERRAL_PENDING, Texts.refusals().deferralPending(call, read),
            situation(call, at));
    }

    /**
     * The token presented is not the one the task holds; the current one
     * travels in {@code data.conflict_token}.
     */
    public static Refused conflictTokenStale(VerbName call, Situation at, String current) {
        Map<String, Object> data = situation(call, at);
        data.put(CONFLICT_TOKEN, current);
        return new Refused(Message.CONFLICT_TOKEN_STALE,
            Texts.refusals().conflictTokenStale(call), data);
    }

    // ======================================================================
    // The ones decided before a task is resolved: no state travels, because
    // naming one would tell a task the caller may see from one it may not
    // ======================================================================

    /** The draw found nothing in a collection that exists. */
    public static Refused nothingToTake(VerbName call, VerbName query) {
        Map<String, Object> data = attempted(call);
        data.put(NEXT, NextList.wire(List.of(new NextList.Step(query,
            Texts.next().listAfterAnEmptyDraw()))));
        return new Refused(Message.NOTHING_TO_TAKE, Texts.refusals().nothingToTake(), data);
    }

    /** A duration that is not a positive ISO-8601 duration. */
    public static Refused claimDurationInvalid(VerbName call, ArgumentPath duration) {
        return new Refused(Message.CLAIM_DURATION_INVALID,
            Texts.refusals().claimDurationInvalid(call), offending(call, List.of(duration)));
    }

    /** A page size a listing cannot take: out of its bounds, or no whole number. */
    public static Refused pageSizeRejected(VerbName call, ArgumentPath pageSize) {
        return new Refused(Message.PAGE_SIZE_REJECTED,
            Texts.refusals().pageSizeRejected(call, Bound.PAGE_SIZE_MAX),
            offending(call, List.of(pageSize)));
    }

    /** A cursor this service did not hand out. */
    public static Refused cursorMalformed(VerbName call, VerbName query, ArgumentPath after) {
        return new Refused(Message.CURSOR_MALFORMED, Texts.refusals().cursorMalformed(query),
            offending(call, List.of(after)));
    }

    /**
     * A cursor of another listing.
     *
     * @param differs the arguments whose values differ from the listing that
     *                handed the cursor out
     */
    public static Refused cursorForeign(VerbName call, List<ArgumentName> differs,
                                        ArgumentPath after) {
        return new Refused(Message.CURSOR_FOREIGN, Texts.refusals().cursorForeign(call, differs),
            offending(call, List.of(after)));
    }

    /** A sort a listing does not declare. */
    public static Refused sortUndeclared(VerbName call, ArgumentPath sort) {
        return new Refused(Message.SORT_UNDECLARED, Texts.refusals().sortUndeclared(call),
            offending(call, List.of(sort)));
    }

    /**
     * Names at one level of a call that the declaration does not have. The
     * refusal carries none of them (DEC-0043 R19): it lists what the level
     * accepts, under {@code data.accepts}.
     *
     * @param underFields whether the names arrived under {@code fields}
     * @param accepts     what the level declares, each with its path
     */
    public static Refused argumentUnknown(VerbName call, boolean underFields,
                                          List<ArgumentPath> accepts) {
        Map<String, Object> data = attempted(call);
        data.put(ACCEPTS, wire(accepts));
        return underFields
            ? new Refused(Message.ARGUMENT_UNKNOWN_UNDER_FIELDS,
                Texts.refusals().argumentUnknownUnderFields(call, accepts), data)
            : new Refused(Message.ARGUMENT_UNKNOWN,
                Texts.refusals().argumentUnknown(call, accepts), data);
    }

    /**
     * A call this service does not have, or no route for a request. The name
     * that arrived is in neither the message nor {@code data}, and so there is
     * no {@code attempted}: it lists the calls there are, under {@code
     * data.accepts}.
     */
    public static Refused callUnknown(List<VerbName> calls) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(ACCEPTS, wire(calls));
        return new Refused(Message.CALL_UNKNOWN, Texts.refusals().callUnknown(calls), data);
    }

    /** A required argument did not arrive. */
    public static Refused argumentMissing(VerbName call, ArgumentPath path) {
        return new Refused(Message.ARGUMENT_MISSING, Texts.refusals().argumentMissing(call, path),
            offending(call, List.of(path)));
    }

    /** One of two arguments, or a change, did not arrive. */
    public static Refused argumentMissing(VerbName call, Missing which,
                                          List<ArgumentPath> offenders) {
        return new Refused(which.message, which.text(call), offending(call, offenders));
    }

    /**
     * An argument arrived with a value it cannot take.
     *
     * @param path the argument, with its path and its position where the
     *             value is one of several; also the one entry of {@code
     *             data.offenders}
     * @param form the form the argument takes
     */
    public static Refused argumentInvalid(VerbName call, ArgumentPath path, Form form) {
        return new Refused(form.message(), invalid(call, path, form),
            offending(call, List.of(path)));
    }

    /** A part of a request outside any call arrived in a form it cannot take. */
    public static Refused requestInvalid(ArgumentPath path) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(OFFENDERS, wire(List.of(path)));
        return new Refused(Message.INVALID_IN_REQUEST, Texts.refusals().invalidInRequest(path),
            data);
    }

    /**
     * An argument arrived with a value it cannot take, where the refusal can
     * name no argument: {@code offenders} is then empty rather than a guess.
     */
    public static Refused valueInvalid(VerbName call) {
        return new Refused(Message.VALUE_INVALID, Texts.refusals().valueInvalid(call),
            offending(call, List.of()));
    }

    /**
     * A value arrived twice in one request, under a name the call does not
     * declare: {@code offenders} is empty, since the name is the caller's.
     */
    public static Refused valueGivenTwice(VerbName call) {
        return new Refused(Message.VALUE_GIVEN_TWICE, Texts.refusals().valueGivenTwice(call),
            offending(call, List.of()));
    }

    /** The bracket kind is not declared in this scope. */
    public static Refused selectorUnknown(VerbName call) {
        return new Refused(Message.SELECTOR_UNKNOWN, Texts.refusals().selectorUnknown(),
            attempted(call));
    }

    /** The scope is of a kind this service does not carry tasks in. */
    public static Refused scopeKindUnsupported(VerbName call) {
        return new Refused(Message.SCOPE_KIND_UNSUPPORTED,
            Texts.refusals().scopeKindUnsupported(), attempted(call));
    }

    /** The caller may read this scope and not write to it. */
    public static Refused scopeReadOnly(VerbName call) {
        return new Refused(Message.SCOPE_READ_ONLY, Texts.refusals().scopeReadOnly(call),
            attempted(call));
    }

    /** The scope is locked and refuses every write. */
    public static Refused scopeLocked(VerbName call) {
        return new Refused(Message.SCOPE_LOCKED, Texts.refusals().scopeLocked(),
            attempted(call));
    }

    /** The same key, a different call or different arguments. */
    public static Refused idempotencyKeyReused(VerbName call) {
        return new Refused(Message.IDEMPOTENCY_KEY_REUSED,
            Texts.refusals().idempotencyKeyReused(), attempted(call));
    }

    /** The call is real and does not apply where it was addressed. */
    public static Refused callNotAtThisAddress(VerbName call, Route route) {
        return new Refused(route.message, route.text(call), attempted(call));
    }

    /**
     * Ours, not the caller's. Says that nothing was changed, and carries the
     * reference {@link UnexpectedFailures} logged it under; call that, not this.
     *
     * @param call the call, or null for a request that reached no call
     */
    public static Refused unexpected(VerbName call, String reference) {
        Map<String, Object> data = call == null ? new LinkedHashMap<>() : attempted(call);
        data.put(REFERENCE, reference);
        return call == null
            ? new Refused(Message.UNEXPECTED_FAILURE_OF_REQUEST,
                Texts.refusals().unexpectedFailureOfRequest(), data)
            : new Refused(Message.UNEXPECTED_FAILURE, Texts.refusals().unexpectedFailure(call),
                data);
    }

    // ======================================================================

    /** The message that states one form, for the argument at {@code path}. */
    private static String invalid(VerbName call, ArgumentPath path, Form form) {
        RefusalMessages refusals = Texts.refusals();
        return switch (form) {
            case FIELDS_OBJECT -> refusals.invalidFieldsObject(call, path);
            case STRING -> refusals.invalidString(call, path);
            case ONE_OF -> refusals.invalidOneOf(call, path);
            case LIST_OF_STRINGS -> refusals.invalidListOfStrings(call, path);
            case OBJECT -> refusals.invalidObject(call, path);
            case BOOLEAN -> refusals.invalidBoolean(call, path);
            case WHOLE_NUMBER -> refusals.invalidWholeNumber(call, path);
            case PARENT_ELSEWHERE -> refusals.invalidParentElsewhere(call, path);
            case PARENT_NOT_A_ROOT -> refusals.invalidParentNotARoot(call, path);
            case APPARATUS_PATTERN -> refusals.invalidApparatusPattern(call, path);
            case APPARATUS_UNBOUNDED -> refusals.invalidApparatusUnbounded(call, path);
            case STATE_FILTER -> refusals.invalidStateFilter(call, path);
            case BRACKET_FILTER -> refusals.invalidBracketFilter(call, path);
            case ADDRESS_FILTER -> refusals.invalidAddressFilter(call, path);
            case ADDRESS_FILTER_ELSEWHERE -> refusals.invalidAddressFilterElsewhere(call, path);
            case VALUE_FILTER -> refusals.invalidValueFilter(call, path);
            case LEASE_END -> refusals.invalidLeaseEnd(call, path);
            case INSTANT -> refusals.invalidInstant(call, path);
            case INSTANT_RANGE -> refusals.invalidInstantRange(call, path);
            case ANSWER_ONE_OF_TWO -> refusals.invalidAnswerOneOfTwo(call, path);
            case ADDRESS -> refusals.invalidAddress(call, path);
            case SCOPE -> refusals.invalidScope(call, path);
            case SELECTOR -> refusals.invalidSelector(call, path);
            case METADATA -> refusals.invalidMetadata(call, path);
            case CURATION_TARGET -> refusals.invalidCurationTarget(call, path);
            case ADDENDUM_TARGET -> refusals.invalidAddendumTarget(call, path);
            case ADDENDA_EXHAUSTED -> refusals.invalidAddendaExhausted(call, path);
            case ANSWER_OPTION -> refusals.invalidAnswerOption(call, path);
            case ANSWER_FREE_TEXT -> refusals.invalidAnswerFreeText(call, path);
            case ONCE -> refusals.invalidOnce(call, path);
            case BODY -> refusals.invalidBody(call, path);
            case SURFACE_HEADER -> refusals.invalidSurfaceHeader(call, path);
        };
    }

    private static Map<String, Object> attempted(VerbName call) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(ATTEMPTED, call.toString());
        return data;
    }

    /** The data of a refusal of a call's arguments: the call, and the arguments by path. */
    private static Map<String, Object> offending(VerbName call, List<ArgumentPath> paths) {
        Map<String, Object> data = attempted(call);
        data.put(OFFENDERS, wire(paths));
        return data;
    }

    /** Names as the wire carries them: their text, which only the declaration can make. */
    private static List<String> wire(List<?> names) {
        return names.stream().map(Object::toString).toList();
    }

    /** The members every refusal about a task the caller may see carries. */
    private static Map<String, Object> situation(VerbName call, Situation at) {
        Map<String, Object> data = attempted(call);
        data.put(STATE, at.state());
        data.put(NEXT, NextList.wire(at.next()));
        if (at.waitingFor() != null) {
            data.put(WAITING_FOR, at.waitingFor());
        }
        return data;
    }
}
