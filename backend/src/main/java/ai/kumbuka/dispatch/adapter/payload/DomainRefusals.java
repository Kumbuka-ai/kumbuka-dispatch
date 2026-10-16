/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.payload;

import ai.kumbuka.dispatch.domain.Actor;
import ai.kumbuka.dispatch.domain.DispatchException;
import ai.kumbuka.dispatch.domain.HolderState;
import ai.kumbuka.dispatch.domain.TaskFilter;
import ai.kumbuka.dispatch.domain.TaskState;
import ai.kumbuka.dispatch.domain.TaskView;
import ai.kumbuka.dispatch.surface.AddressParser;
import ai.kumbuka.dispatch.surface.Argument;
import ai.kumbuka.dispatch.surface.ArgumentName;
import ai.kumbuka.dispatch.surface.ArgumentPath;
import ai.kumbuka.dispatch.surface.Form;
import ai.kumbuka.dispatch.surface.NextList;
import ai.kumbuka.dispatch.surface.ProcessVerb;
import ai.kumbuka.dispatch.surface.Refusal;
import ai.kumbuka.dispatch.surface.RefusalCode;
import ai.kumbuka.dispatch.surface.Surface;
import ai.kumbuka.dispatch.surface.UnexpectedFailures;
import ai.kumbuka.dispatch.surface.VerbName;
import ai.kumbuka.dispatch.surface.VerbSurface;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * A kernel refusal as the caller is told it, built at the edge that caught it
 * (C8.2).
 *
 * <p>The kernel's message is discarded: it names addresses in short form and
 * speaks the kernel's words. What the caller reads is a declared message, and
 * for a refusal about a task the caller may see, the task's situation — its
 * state, the calls open to the caller, what it waits for — read again after
 * the refused call rolled back. Where the task cannot be read the refusal is
 * {@code NOT_FOUND}, so a caller learns no state of a task it may not see.
 *
 * <p>That second read is the edge's, because it is a call that may itself be
 * refused, and only the edge catches a refusal. The edge hands it in as a
 * reader that answers the task at a complete address, or nothing.
 *
 * <p>A refusal of an argument names it in {@code data.offenders} by the path
 * the kernel gives, as it gives it (DEC-0042): the kernel holds the
 * declaration's names for its arguments, and a value it was sent is in none of
 * them.
 */
public final class DomainRefusals {

    private static final String ADDRESS = "address";
    private static final String SCOPE = "scope";
    private static final String SELECTOR = "selector";
    private static final String PARENT = "parent";
    private static final String RECEIPT = "receipt";
    private static final String TEXT = "text";
    private static final String OPTION = "option";
    private static final String AFTER = "after";

    /** Where an unexpected failure's log line says the call was made, when no address reads. */
    private static final String UNREAD = "<unread>";

    private DomainRefusals() {
    }

    /**
     * The task at a complete address, read in a transaction of its own, or
     * empty where it cannot be read.
     */
    @FunctionalInterface
    public interface Reader extends Function<String, Optional<VerbSurface.Result>> {
    }

    /**
     * A kernel refusal of one call, in the caller's vocabulary.
     *
     * @param actor the caller, or null where the token carried no usable
     *              capacity and so no caller was made
     * @param given the call's arguments as they arrived, read only for the
     *              address of the task the refusal is about
     */
    public static Refusal of(DispatchException e, Surface surface, Actor actor, ProcessVerb verb,
                             Map<String, Object> given, Reader read, Clock clock) {
        VerbName call = VerbName.of(verb, surface);
        RefusalCode code = RefusalCode.of(e.reason());
        return switch (code) {
            case NOT_FOUND -> Refusal.notFound();
            case UNEXPECTED_FAILURE -> UnexpectedFailures.refuse(call, whereIn(given), e);
            case SCOPE_KIND_UNSUPPORTED -> Refusal.scopeKindUnsupported(call);
            case SCOPE_READ_ONLY -> Refusal.scopeReadOnly(call);
            case SCOPE_LOCKED -> Refusal.scopeLocked(call);
            case SELECTOR_UNDECLARED -> Refusal.selectorUndeclared(call);
            case SELECTOR_WITHDRAWN -> Refusal.selectorWithdrawn(call);
            case IDEMPOTENCY_KEY_REUSED -> Refusal.idempotencyKeyReused(call);
            case DRAW_EMPTY -> Refusal.nothingToTake(call, VerbName.of(ProcessVerb.QUERY, surface));
            case PAGE_SIZE_REJECTED -> Refusal.pageSizeRejected(call, e.offenders());
            case CURSOR_MALFORMED -> Refusal.cursorMalformed(call,
                VerbName.of(ProcessVerb.QUERY, surface), e.offenders());
            case CURSOR_FOREIGN -> Refusal.cursorForeign(call, differing(verb, e.offenders()),
                pathIn(verb, AFTER, Argument.Placement.TOP));
            case SORT_UNDECLARED -> Refusal.sortUndeclared(call, e.offenders());
            case ARGUMENT_MISSING -> missing(verb, call, e);
            case ARGUMENT_UNKNOWN -> Refusal.argumentUnknown(call, false,
                ArgumentPath.of(verb.topArguments()));
            case ARGUMENT_INVALID -> invalid(verb, call, e);
            case CONFLICT_TOKEN_MISSING -> Refusal.conflictTokenMissing(call);
            case OPTION_UNOFFERED -> fieldIn(given, OPTION) != null
                ? Refusal.optionUnoffered(call, pathIn(verb, OPTION, Argument.Placement.FIELDS),
                    false)
                : Refusal.optionUnoffered(call, pathIn(verb, TEXT, Argument.Placement.FIELDS),
                    true);
            default -> about(new Context(surface, actor, verb, call, given, read, clock), e, code);
        };
    }

    /** The call a kernel refusal is worded against, and how its task is read again. */
    private record Context(Surface surface, Actor actor, ProcessVerb verb, VerbName call,
                           Map<String, Object> given, Reader read, Clock clock) {
    }

    /** The refusals about a task the caller may see: they carry its situation. */
    private static Refusal about(Context c, DispatchException e, RefusalCode code) {
        Surface surface = c.surface();
        Actor actor = c.actor();
        ProcessVerb verb = c.verb();
        VerbName call = c.call();
        Reader read = c.read();
        Clock clock = c.clock();
        Map<String, Object> given = c.given();
        String target = targetIn(verb, given);
        if (actor == null) {
            // A token with no usable capacity reads nothing, so there is no
            // state to name; the refusal is about who the caller is.
            return Refusal.ofRole(call, Refusal.Part.CAPACITY);
        }
        if (target == null && code == RefusalCode.ROLE_INSUFFICIENT) {
            // A call on a collection that is the other part's: a draw by a
            // commissioner. No task is named, so none has a state to name.
            return Refusal.ofRole(call, part(verb, e));
        }
        Optional<VerbSurface.Result> result = target == null ? Optional.empty()
            : read.apply(target);
        if (result.isEmpty()) {
            return Refusal.notFound();
        }
        Refusal.Situation at = situationOf(surface, actor, result.get(), clock);
        TaskView view = result.get().view();
        return switch (code) {
            case TRANSITION_REFUSED -> Refusal.ofState(call, at,
                view.state() == TaskState.CLOSED);
            case ROLE_INSUFFICIENT -> Refusal.ofRole(call, at, part(verb, e));
            case CLAIM_HELD, CLAIM_ABSENT -> Refusal.notTheHolder(call, at, unheld(view, e),
                verb == ProcessVerb.READ_TEXT);
            case RECEIPT_WRONG -> Refusal.receiptWrong(call, at);
            case CHILDREN_UNFINISHED -> Refusal.childrenUnfinished(call,
                children(result.get(), e), e.confirmation().orElse(null),
                e.reason() == DispatchException.Reason.CONFIRMATION_STALE);
            case DEFERRAL_PENDING -> Refusal.deferralPending(call,
                VerbName.of(ProcessVerb.READ, surface), at);
            case CONFLICT_TOKEN_STALE -> Refusal.conflictTokenStale(call, at,
                result.get().address(), view, AddressParser.SCHEME);
            default -> UnexpectedFailures.refuse(call, result.get().address(), e);
        };
    }

    /**
     * The declared path of an argument a kernel refusal concerns. The kernel
     * refuses only what the declaration carries; where it names one this call
     * does not declare, the two disagree, and that is a defect.
     */
    private static ArgumentPath pathIn(ProcessVerb verb, String name,
                                       Argument.Placement placement) {
        Argument argument = verb.argument(name, placement);
        if (argument == null) {
            throw new IllegalStateException(verb.call() + " declares no " + name
                + ", and the kernel refused it");
        }
        return ArgumentPath.of(argument);
    }

    /**
     * What differs between a cursor's listing and the call, as the declared
     * arguments that carry it, which is how the kernel names them.
     */
    private static List<ArgumentName> differing(ProcessVerb verb, List<String> differ) {
        List<ArgumentName> names = new ArrayList<>();
        for (String name : differ) {
            Argument argument = verb.argument(name, Argument.Placement.TOP);
            if (argument != null) {
                names.add(ArgumentName.of(argument));
            }
        }
        return names;
    }

    private static Refusal missing(ProcessVerb verb, VerbName call, DispatchException e) {
        return switch (e.reason()) {
            case RECEIPT_ABSENT -> Refusal.argumentMissing(call,
                pathIn(verb, RECEIPT, Argument.Placement.TOP));
            case ADDENDUM_TEXT_MISSING -> Refusal.argumentMissing(call,
                pathIn(verb, TEXT, Argument.Placement.FIELDS));
            default -> Refusal.argumentMissing(call, ArgumentPath.FIELDS);
        };
    }

    private static Refusal invalid(ProcessVerb verb, VerbName call, DispatchException e) {
        return switch (e.reason()) {
            case METADATA_REFUSED -> Refusal.argumentInvalid(call,
                pathIn(verb, "metadata", Argument.Placement.FIELDS), Form.METADATA);
            case CURATION_TARGET_SELF -> Refusal.argumentInvalid(call,
                pathIn(verb, "curated_in", Argument.Placement.FIELDS), Form.CURATION_TARGET);
            case ADDENDUM_SUFFIX_EXHAUSTED -> Refusal.argumentInvalid(call,
                pathIn(verb, "part", Argument.Placement.FIELDS), Form.ADDENDA_EXHAUSTED);
            case CLAIM_DURATION_NOT_POSITIVE -> Refusal.claimDurationInvalid(call,
                pathIn(verb, "duration", Argument.Placement.TOP));
            case FILTER_VALUE_REFUSED -> filterValue(verb, call, e);
            default -> Refusal.valueInvalid(call);
        };
    }

    /**
     * A filter value the kernel cannot take. The message names the declared
     * filter and the form it takes; {@code data.offenders} carries the path
     * the kernel gives, with the value's position where it is one of several.
     */
    private static Refusal filterValue(ProcessVerb verb, VerbName call, DispatchException e) {
        String first = e.offenders().isEmpty() ? "" : e.offenders().get(0);
        int open = first.indexOf('[');
        String name = open < 0 ? first : first.substring(0, open);
        Argument argument = verb.argument(name, Argument.Placement.TOP);
        if (argument == null) {
            return Refusal.valueInvalid(call, e.offenders());
        }
        ArgumentPath path = open < 0 ? ArgumentPath.of(argument)
            : ArgumentPath.of(argument,
                Integer.parseInt(first.substring(open + 1, first.length() - 1)));
        return Refusal.argumentInvalid(call, path, filterForm(name), e.offenders());
    }

    /** The form one filter takes, as a refusal of its value says it. */
    private static Form filterForm(String field) {
        if (field.equals(TaskFilter.Field.STATE.wireName())) {
            return Form.STATE_FILTER;
        }
        if (field.equals(TaskFilter.Field.BRACKET.wireName())) {
            return Form.BRACKET_FILTER;
        }
        if (field.equals(TaskFilter.Field.ADDRESS.wireName())) {
            return Form.ADDRESS_FILTER;
        }
        return Form.VALUE_FILTER;
    }

    /** Whom the call belongs to, as a refusal of the caller's part names it. */
    private static Refusal.Part part(ProcessVerb verb, DispatchException e) {
        if (e.reason() == DispatchException.Reason.RATIFICATION_NOT_PERMITTED) {
            return Refusal.Part.COMMISSIONER_OTHER_THAN_THE_DELIVERER;
        }
        if (verb.role() == null) {
            return Refusal.Part.AUTHOR;
        }
        return switch (verb.role()) {
            case COMMISSIONER, BYSTANDER -> Refusal.Part.COMMISSIONER;
            case CANDIDATE -> Refusal.Part.EXECUTOR;
            case HOLDER -> Refusal.Part.HOLDER;
        };
    }

    private static Refusal.Unheld unheld(TaskView view, DispatchException e) {
        if (e.reason() == DispatchException.Reason.LEASE_LAPSED) {
            return Refusal.Unheld.LAPSED;
        }
        return view.holder() == HolderState.OTHER ? Refusal.Unheld.OTHER : Refusal.Unheld.NOBODY;
    }

    /**
     * The complete address of each unfinished child. The kernel names each by
     * its short form, with its state after it; the state is not carried.
     */
    private static List<String> children(VerbSurface.Result root, DispatchException e) {
        List<String> children = new ArrayList<>();
        for (String named : e.offenders()) {
            int space = named.indexOf(' ');
            String shortForm = space < 0 ? named : named.substring(0, space);
            int slash = shortForm.indexOf('/');
            children.add(new VerbSurface.Item(root.scope(),
                AddressParser.item(shortForm.substring(0, slash), shortForm.substring(slash + 1)))
                .complete());
        }
        return children;
    }

    private static Refusal.Situation situationOf(Surface surface, Actor actor,
                                                 VerbSurface.Result result, Clock clock) {
        List<NextList.Step> next = NextList.of(surface, result.view(), actor, false);
        return new Refusal.Situation(stateOf(result.view()), next,
            NextList.waitingFor(result.view(), next, clock.instant()));
    }

    /** The state as a refusal names it, with its hold reason or its outcome. */
    static String stateOf(TaskView view) {
        String state = view.state().wireName();
        if (view.holdReason() != null) {
            return state + " (" + view.holdReason().wireName() + ")";
        }
        if (view.outcome() != null) {
            return state + " (" + view.outcome().wireName() + ")";
        }
        return state;
    }

    /**
     * The task a refused call was about: its address, or a create's parent;
     * null where the call named none, or none that reads as an address. Read
     * only to find the state the refusal carries, never to name it.
     */
    private static String targetIn(ProcessVerb verb, Map<String, Object> given) {
        Object target = given.get(verb == ProcessVerb.CREATE ? PARENT : ADDRESS);
        return target == null ? null : wellFormed(String.valueOf(target));
    }

    /** The address as the service renders it, or null where it reads as none. */
    private static String wellFormed(String raw) {
        return AddressParser.partsOf(raw)
            .map(parts -> new VerbSurface.Item(parts.scope(),
                AddressParser.item(parts.selector(), parts.id())).complete())
            .orElse(null);
    }

    /**
     * The address or the collection a call named, for the log line of an
     * unexpected failure (DEC-0051 decides what that line may carry).
     */
    public static String whereIn(Map<String, Object> given) {
        Object address = given.get(ADDRESS);
        if (address != null) {
            String complete = wellFormed(String.valueOf(address));
            return complete == null ? UNREAD : complete;
        }
        Object scope = given.get(SCOPE);
        Object selector = given.get(SELECTOR);
        if (scope == null || selector == null) {
            return UNREAD;
        }
        Optional<String> scopeSlug = AddressParser.scopeOf(String.valueOf(scope));
        Optional<String> kind = AddressParser.selectorOf(String.valueOf(selector));
        return scopeSlug.isPresent() && kind.isPresent()
            ? AddressParser.completeCollection(scopeSlug.get(), kind.get())
            : UNREAD;
    }

    @SuppressWarnings("unchecked")
    private static Object fieldIn(Map<String, Object> given, String name) {
        Object fields = given.get(CallPayload.FIELDS);
        return fields instanceof Map ? ((Map<String, Object>) fields).get(name) : null;
    }
}
