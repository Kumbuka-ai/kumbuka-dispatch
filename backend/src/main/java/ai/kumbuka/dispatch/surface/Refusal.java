/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.domain.TaskView;
import ai.kumbuka.dispatch.surface.RefusalCode.Message;

import java.util.List;

/**
 * A refusal as a caller is told it: a code, a message that explains itself,
 * and the data the caller acts on — the call as it was made, the state, and
 * the way out (DEC-0042, TAR-0004 section 7).
 *
 * <p>A value and not an exception. A refusal the surface decides travels in a
 * {@link SurfaceException}; a refusal the kernel decides travels in the
 * domain's exception and is turned into one of these at the edge, where the
 * situation it names is read. The edge maps either to its envelope.
 *
 * <p>The message is one declared {@link Message} of {@link RefusalCode},
 * rendered by its method of {@link RefusalMessages}, so one refusal is worded
 * one way wherever it is raised and its text is in the bundle file alone.
 *
 * <h2>Only the platform's vocabulary (DEC-0043 R18, R19)</h2>
 *
 * No factory here takes a string from a request. A call is a {@link VerbName},
 * an argument an {@link ArgumentPath}, a form a {@link Form}; each is built
 * from the declaration and from nothing a caller sends. So no refusal can
 * carry the caller's input or the tenant's vocabulary in its message or its
 * data. A name the call does not declare is answered with the names it does
 * declare, under {@code data.accepts}; a wrong value of several is named by
 * its position, as {@code apparatus[2]}. A kernel refusal of an argument names
 * it by the path the kernel gives, and the kernel gives declared names only.
 *
 * @param code    the code a caller matches on
 * @param message the declared message, rendered
 * @param data    what the caller acts on; null for {@link RefusalCode#NOT_FOUND},
 *                which must be byte-identical across its causes
 */
public record Refusal(RefusalCode code, String message, Data data) {

    /**
     * The members a refusal may carry. Each means one thing on every service
     * (DEC-0042); a member with no value is absent on the wire.
     *
     * @param current the task as it stands, for a stale conflict token: its
     *                fields travel so that the caller can merge (DEC-0041)
     */
    public record Data(String attempted, String state, List<NextList.Step> next,
                       String waitingFor, List<String> offenders, List<String> accepts,
                       List<Child> children, String confirmation, String conflictToken,
                       String reference, String address, TaskView current, String service) {

        static Data attempted(VerbName call) {
            return new Data(call == null ? null : call.toString(), null, null, null, null, null,
                null, null, null, null, null, null, null);
        }

        Data offenders(List<?> paths) {
            return new Data(attempted, state, next, waitingFor, wire(paths), accepts, children,
                confirmation, conflictToken, reference, address, current, service);
        }

        Data accepts(List<?> names) {
            return new Data(attempted, state, next, waitingFor, offenders, wire(names), children,
                confirmation, conflictToken, reference, address, current, service);
        }

        Data situation(Situation at) {
            return new Data(attempted, at.state(), at.next(), at.waitingFor(), offenders,
                accepts, children, confirmation, conflictToken, reference, address, current,
                service);
        }

        Data next(List<NextList.Step> steps) {
            return new Data(attempted, state, steps, waitingFor, offenders, accepts, children,
                confirmation, conflictToken, reference, address, current, service);
        }

        Data children(List<Child> unfinished, String handedOut) {
            return new Data(attempted, state, next, waitingFor, offenders, accepts,
                List.copyOf(unfinished), handedOut, conflictToken, reference, address, current,
                service);
        }

        Data current(String at, TaskView view, String token, String serviceName) {
            return new Data(attempted, state, next, waitingFor, offenders, accepts, children,
                confirmation, token, reference, at, view, serviceName);
        }

        Data reference(String id) {
            return new Data(attempted, state, next, waitingFor, offenders, accepts, children,
                confirmation, conflictToken, id, address, current, service);
        }

        /** Names as the wire carries them: their text, which only the declaration can make. */
        private static List<String> wire(List<?> names) {
            return names.stream().map(Object::toString).toList();
        }
    }

    /** An unfinished child of a bracket root: complete address, state, the calls open on it. */
    public record Child(String address, String state, List<NextList.Step> next) {
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
        COMMISSIONER, EXECUTOR, HOLDER, AUTHOR, COMMISSIONER_OTHER_THAN_THE_DELIVERER, CAPACITY;

        private Message message() {
            return switch (this) {
                case COMMISSIONER -> Message.ROLE_COMMISSIONER;
                case EXECUTOR -> Message.ROLE_EXECUTOR;
                case HOLDER -> Message.ROLE_HOLDER;
                case AUTHOR -> Message.ROLE_AUTHOR;
                case COMMISSIONER_OTHER_THAN_THE_DELIVERER -> Message.ROLE_OTHER_THAN_THE_DELIVERER;
                case CAPACITY -> Message.ROLE_CAPACITY;
            };
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

    /**
     * Why a holder's call finds the caller without the task. Each is its own
     * code: another holds it ({@code CLAIM_HELD}), nobody does (the state does
     * not allow the call), or the caller's own lease ended ({@code
     * LEASE_LAPSED}).
     */
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
        CREATE, QUERY, ON_A_BRACKET_KIND, READ, READ_CALL, UPDATE, DELETE, ON_A_TASK;

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
        OPTIONS, ANSWER, CHANGE;

        private String text(VerbName call) {
            RefusalMessages refusals = Texts.refusals();
            return switch (this) {
                case OPTIONS -> refusals.argumentMissingOptions(call);
                case ANSWER -> refusals.argumentMissingAnswer(call);
                case CHANGE -> refusals.argumentMissingChange(call);
            };
        }

        private Message message() {
            return switch (this) {
                case OPTIONS -> Message.ARGUMENT_MISSING_OPTIONS;
                case ANSWER -> Message.ARGUMENT_MISSING_ANSWER;
                case CHANGE -> Message.ARGUMENT_MISSING_CHANGE;
            };
        }
    }

    private static Refusal of(Message message, String text, Data data) {
        return new Refusal(message.code(), text, data);
    }

    // ======================================================================
    // The one that says nothing
    // ======================================================================

    /**
     * The deliberately indistinguishable refusal: nothing there, a scope the
     * caller may not see, and an address that cannot be routed answer alike.
     * Takes no arguments, which is the enforcement.
     */
    public static Refusal notFound() {
        return of(Message.NOT_FOUND, Texts.refusals().notFound(), null);
    }

    // ======================================================================
    // The ones about a task the caller may see: they carry its situation
    // ======================================================================

    /** The state, or the condition on the hold, does not permit the call. */
    public static Refusal ofState(VerbName call, Situation at, boolean closed) {
        RefusalMessages refusals = Texts.refusals();
        Data data = Data.attempted(call).situation(at);
        if (closed) {
            return of(Message.STATE_CLOSED, refusals.stateClosed(call), data);
        }
        if (at.next().isEmpty()) {
            return of(Message.STATE_NOTHING_OPEN, refusals.stateNothingOpen(call), data);
        }
        return of(Message.STATE_DOES_NOT_ALLOW, refusals.stateDoesNotAllow(call,
            at.next().stream().map(NextList.Step::name).toList()), data);
    }

    /** The call belongs to another part. */
    public static Refusal ofRole(VerbName call, Situation at, Part part) {
        return of(part.message(), part.text(call), Data.attempted(call).situation(at));
    }

    /** The call belongs to another part, and no task is there to name a state of. */
    public static Refusal ofRole(VerbName call, Part part) {
        return of(part.message(), part.text(call), Data.attempted(call));
    }

    /**
     * The call is the holder's and the caller does not hold the task.
     *
     * @param reading whether the call reads the text, which an executor may
     *                only of a task it holds
     */
    public static Refusal notTheHolder(VerbName call, Situation at, Unheld why, boolean reading) {
        return of(reading ? why.text : why.call, why.text(call, reading),
            Data.attempted(call).situation(at));
    }

    /** A receipt arrived and is not the one the task holds. */
    public static Refusal receiptWrong(VerbName call, Situation at) {
        return of(Message.RECEIPT_WRONG, Texts.refusals().receiptWrong(call),
            Data.attempted(call).situation(at));
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
    public static Refusal childrenNotFinished(VerbName call, Situation at, List<Child> children,
                                              String confirmation, boolean stale) {
        Data data = Data.attempted(call).situation(at).children(children, confirmation);
        return stale
            ? of(Message.CHILDREN_CONFIRMATION_STALE,
                Texts.refusals().childrenConfirmationStale(call), data)
            : of(Message.CHILDREN_NOT_FINISHED, Texts.refusals().childrenNotFinished(call), data);
    }

    /**
     * A claim on a task deferred until an instant that has not come. The
     * instant is named by the read that shows it, never in the refusal.
     */
    public static Refusal deferralPending(VerbName call, VerbName read, Situation at) {
        return of(Message.DEFERRAL_PENDING, Texts.refusals().deferralPending(call, read),
            Data.attempted(call).situation(at));
    }

    /**
     * The token presented is not the one the task holds. The task travels as
     * it stands, under its address, its fields, its current token and the
     * service that holds it (DEC-0041), so the caller can merge and repeat.
     */
    public static Refusal conflictTokenStale(VerbName call, Situation at, String address,
                                             TaskView current, String service) {
        return of(Message.CONFLICT_TOKEN_STALE, Texts.refusals().conflictTokenStale(call),
            Data.attempted(call).situation(at)
                .current(address, current, current.conflictToken(), service));
    }

    /** A call fenced by the conflict token arrived without one. */
    public static Refusal conflictTokenMissing(VerbName call) {
        return of(Message.CONFLICT_TOKEN_MISSING, Texts.refusals().conflictTokenMissing(call),
            Data.attempted(call));
    }

    // ======================================================================
    // The ones decided before a task is resolved: no state travels, because
    // naming one would tell a task the caller may see from one it may not
    // ======================================================================

    /** The draw found nothing in a collection that exists. */
    public static Refusal nothingToTake(VerbName call, VerbName query) {
        return of(Message.NOTHING_TO_TAKE, Texts.refusals().nothingToTake(),
            Data.attempted(call).next(List.of(new NextList.Step(query,
                Texts.next().listAfterAnEmptyDraw()))));
    }

    /** A duration that is not a positive ISO-8601 duration. */
    public static Refusal claimDurationInvalid(VerbName call, ArgumentPath duration) {
        return of(Message.CLAIM_DURATION_INVALID, Texts.refusals().claimDurationInvalid(call),
            Data.attempted(call).offenders(List.of(duration)));
    }

    /** A page size a listing cannot take: out of its bounds, or no whole number. */
    public static Refusal pageSizeRejected(VerbName call, List<?> pageSize) {
        return of(Message.PAGE_SIZE_REJECTED,
            Texts.refusals().pageSizeRejected(call, Bound.PAGE_SIZE_MAX),
            Data.attempted(call).offenders(pageSize));
    }

    /** A cursor this service did not hand out. */
    public static Refusal cursorMalformed(VerbName call, VerbName query, List<?> after) {
        return of(Message.CURSOR_MALFORMED, Texts.refusals().cursorMalformed(query),
            Data.attempted(call).offenders(after));
    }

    /**
     * A cursor of another listing.
     *
     * @param differs the arguments whose values differ from the listing that
     *                handed the cursor out
     */
    public static Refusal cursorForeign(VerbName call, List<ArgumentName> differs,
                                        ArgumentPath after) {
        return of(Message.CURSOR_FOREIGN, Texts.refusals().cursorForeign(call, differs),
            Data.attempted(call).offenders(List.of(after)));
    }

    /** A sort a listing does not declare. */
    public static Refusal sortUndeclared(VerbName call, List<?> sort) {
        return of(Message.SORT_UNDECLARED, Texts.refusals().sortUndeclared(call),
            Data.attempted(call).offenders(sort));
    }

    /**
     * Names at one level of a call that the declaration does not have. The
     * refusal carries none of them (DEC-0043 R19): it lists what the level
     * accepts, under {@code data.accepts}.
     *
     * @param underFields whether the names arrived under {@code fields}
     * @param accepts     what the level declares, each with its path
     */
    public static Refusal argumentUnknown(VerbName call, boolean underFields,
                                          List<ArgumentPath> accepts) {
        Data data = Data.attempted(call).accepts(accepts);
        return underFields
            ? of(Message.ARGUMENT_UNKNOWN_UNDER_FIELDS,
                Texts.refusals().argumentUnknownUnderFields(call, accepts), data)
            : of(Message.ARGUMENT_UNKNOWN, Texts.refusals().argumentUnknown(call, accepts), data);
    }

    /**
     * A verb this service does not carry at this address. The name that
     * arrived is in neither the message nor {@code data}, and so there is no
     * {@code attempted}: it lists the verbs this address carries, under {@code
     * data.accepts} (C3.3).
     */
    public static Refusal callUnknown(List<VerbName> accepts) {
        return of(Message.CALL_UNKNOWN, Texts.refusals().callUnknown(accepts),
            Data.attempted(null).accepts(accepts));
    }

    /**
     * A verb this service carries, sent to an address it does not apply at.
     * The message names the route it applies at; {@code data.accepts} lists
     * the verbs this address carries (C3.3).
     */
    public static Refusal callNotAtThisAddress(VerbName call, Route route,
                                               List<VerbName> accepts) {
        return new Refusal(RefusalCode.VERB_NOT_CARRIED, route.text(call),
            Data.attempted(call).accepts(accepts));
    }

    /** A required argument did not arrive. */
    public static Refusal argumentMissing(VerbName call, ArgumentPath path) {
        return of(Message.ARGUMENT_MISSING, Texts.refusals().argumentMissing(call, path),
            Data.attempted(call).offenders(List.of(path)));
    }

    /** One of two arguments, or a change, did not arrive. */
    public static Refusal argumentMissing(VerbName call, Missing which,
                                          List<ArgumentPath> offenders) {
        return of(which.message(), which.text(call), Data.attempted(call).offenders(offenders));
    }

    /**
     * An argument arrived with a value it cannot take.
     *
     * @param path the argument, with its path and its position where the
     *             value is one of several; also the one entry of {@code
     *             data.offenders}
     * @param form the form the argument takes
     */
    public static Refusal argumentInvalid(VerbName call, ArgumentPath path, Form form) {
        return of(form.message(), invalid(call, path, form),
            Data.attempted(call).offenders(List.of(path)));
    }

    /**
     * A kernel refusal of a value. The message names the declared argument;
     * {@code data.offenders} carries the paths the kernel gives, as it gives
     * them, and the kernel gives declared names only (DEC-0042).
     */
    public static Refusal argumentInvalid(VerbName call, ArgumentPath path, Form form,
                                          List<String> offenders) {
        return of(form.message(), invalid(call, path, form),
            Data.attempted(call).offenders(offenders));
    }

    /** A part of a request outside any call arrived in a form it cannot take. */
    public static Refusal requestInvalid(ArgumentPath path) {
        return of(Message.INVALID_IN_REQUEST, Texts.refusals().invalidInRequest(path),
            Data.attempted(null).offenders(List.of(path)));
    }

    /**
     * An argument arrived with a value it cannot take, where the refusal can
     * name no argument: {@code offenders} is then empty rather than a guess.
     */
    public static Refusal valueInvalid(VerbName call) {
        return of(Message.VALUE_INVALID, Texts.refusals().valueInvalid(call),
            Data.attempted(call).offenders(List.of()));
    }

    /**
     * A kernel refusal of a value whose argument the kernel's path does not
     * name: {@code data.offenders} carries the paths as the kernel gives them.
     */
    public static Refusal valueInvalid(VerbName call, List<String> offenders) {
        return of(Message.VALUE_INVALID, Texts.refusals().valueInvalid(call),
            Data.attempted(call).offenders(offenders));
    }

    /**
     * A value arrived twice in one request, under a name the call does not
     * declare: {@code offenders} is empty, since the name is the caller's.
     */
    public static Refusal valueGivenTwice(VerbName call) {
        return of(Message.VALUE_GIVEN_TWICE, Texts.refusals().valueGivenTwice(call),
            Data.attempted(call).offenders(List.of()));
    }

    /** The bracket kind is not declared in this scope. */
    public static Refusal selectorUndeclared(VerbName call) {
        return of(Message.SELECTOR_UNKNOWN, Texts.refusals().selectorUnknown(),
            Data.attempted(call));
    }

    /** The bracket kind was declared in this scope and is withdrawn. */
    public static Refusal selectorWithdrawn(VerbName call) {
        return of(Message.SELECTOR_WITHDRAWN, Texts.refusals().selectorWithdrawn(),
            Data.attempted(call));
    }

    /** The scope is of a kind this service does not carry tasks in. */
    public static Refusal scopeKindUnsupported(VerbName call) {
        return of(Message.SCOPE_KIND_UNSUPPORTED, Texts.refusals().scopeKindUnsupported(),
            Data.attempted(call));
    }

    /** The caller may read this scope and not write to it. */
    public static Refusal scopeReadOnly(VerbName call) {
        return of(Message.SCOPE_READ_ONLY, Texts.refusals().scopeReadOnly(call),
            Data.attempted(call));
    }

    /** The scope is locked and refuses every write. */
    public static Refusal scopeLocked(VerbName call) {
        return of(Message.SCOPE_LOCKED, Texts.refusals().scopeLocked(), Data.attempted(call));
    }

    /** The same key, a different call or different arguments. */
    public static Refusal idempotencyKeyReused(VerbName call) {
        return of(Message.IDEMPOTENCY_KEY_REUSED, Texts.refusals().idempotencyKeyReused(),
            Data.attempted(call));
    }

    /**
     * Ours, not the caller's. Says that nothing was changed, and carries the
     * reference {@link UnexpectedFailures} logged it under; call that, not this.
     *
     * @param call the call, or null for a request that reached no call
     */
    public static Refusal unexpected(VerbName call, String reference) {
        Data data = Data.attempted(call).reference(reference);
        return call == null
            ? of(Message.UNEXPECTED_FAILURE_OF_REQUEST,
                Texts.refusals().unexpectedFailureOfRequest(), data)
            : of(Message.UNEXPECTED_FAILURE, Texts.refusals().unexpectedFailure(call), data);
    }

    // ======================================================================

    /** The message that states one form, for the argument at {@code path}. */
    private static String invalid(VerbName call, ArgumentPath at, Form form) {
        RefusalMessages refusals = Texts.refusals();
        return switch (form) {
            case FIELDS_OBJECT -> refusals.invalidFieldsObject(call, at);
            case STRING -> refusals.invalidString(call, at);
            case ONE_OF -> refusals.invalidOneOf(call, at);
            case LIST_OF_STRINGS -> refusals.invalidListOfStrings(call, at);
            case OBJECT -> refusals.invalidObject(call, at);
            case BOOLEAN -> refusals.invalidBoolean(call, at);
            case WHOLE_NUMBER -> refusals.invalidWholeNumber(call, at);
            case PARENT_ELSEWHERE -> refusals.invalidParentElsewhere(call, at);
            case PARENT_NOT_A_ROOT -> refusals.invalidParentNotARoot(call, at);
            case APPARATUS_PATTERN -> refusals.invalidApparatusPattern(call, at);
            case APPARATUS_UNBOUNDED -> refusals.invalidApparatusUnbounded(call, at);
            case STATE_FILTER -> refusals.invalidStateFilter(call, at);
            case BRACKET_FILTER -> refusals.invalidBracketFilter(call, at);
            case ADDRESS_FILTER -> refusals.invalidAddressFilter(call, at);
            case ADDRESS_FILTER_ELSEWHERE -> refusals.invalidAddressFilterElsewhere(call, at);
            case VALUE_FILTER -> refusals.invalidValueFilter(call, at);
            case LEASE_END -> refusals.invalidLeaseEnd(call, at);
            case INSTANT -> refusals.invalidInstant(call, at);
            case INSTANT_RANGE -> refusals.invalidInstantRange(call, at);
            case ANSWER_ONE_OF_TWO -> refusals.invalidAnswerOneOfTwo(call, at);
            case ADDRESS -> refusals.invalidAddress(call, at);
            case SCOPE -> refusals.invalidScope(call, at);
            case SELECTOR -> refusals.invalidSelector(call, at);
            case METADATA -> refusals.invalidMetadata(call, at);
            case CURATION_TARGET -> refusals.invalidCurationTarget(call, at);
            case ADDENDUM_TARGET -> refusals.invalidAddendumTarget(call, at);
            case ADDENDA_EXHAUSTED -> refusals.invalidAddendaExhausted(call, at);
            case ANSWER_OPTION -> refusals.invalidAnswerOption(call, at);
            case ANSWER_FREE_TEXT -> refusals.invalidAnswerFreeText(call, at);
            case ONCE -> refusals.invalidOnce(call, at);
            case BODY -> refusals.invalidBody(call, at);
            case SURFACE_HEADER -> refusals.invalidSurfaceHeader(call, at);
        };
    }
}
