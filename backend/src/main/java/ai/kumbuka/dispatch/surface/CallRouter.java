/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.domain.Actor;
import ai.kumbuka.dispatch.domain.DispatchException;
import ai.kumbuka.dispatch.domain.ExchangeAddress;
import ai.kumbuka.dispatch.domain.HoldReason;
import ai.kumbuka.dispatch.domain.HolderState;
import ai.kumbuka.dispatch.domain.IdempotencyKey;
import ai.kumbuka.dispatch.domain.PageRequest;
import ai.kumbuka.dispatch.domain.SortKey;
import ai.kumbuka.dispatch.domain.TaskCall;
import ai.kumbuka.dispatch.domain.TaskFilter;
import ai.kumbuka.dispatch.domain.TaskInput;
import ai.kumbuka.dispatch.domain.TaskService;
import ai.kumbuka.dispatch.domain.TaskState;
import ai.kumbuka.dispatch.domain.TaskTextView;
import ai.kumbuka.dispatch.domain.TaskVerb;
import ai.kumbuka.dispatch.domain.TaskView;
import ai.kumbuka.dispatch.domain.TextPart;
import ai.kumbuka.dispatch.domain.TextType;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * One call of the verb surface, from its name and arguments to its answer or
 * its refusal: the part both surfaces share.
 *
 * <p>Four steps, in this order. The name is looked up in the declaration; a
 * name it does not carry — an earlier one included — is refused as unknown.
 * The arguments are closed against the declaration at every level and their
 * values read ({@link CallArguments}); nothing is resolved before that. The
 * call is made: one method of {@link VerbSurface}, in one transaction, which
 * resolves the scope and calls the kernel once. And a refusal from below is
 * put into the shape TAR-0004 section 7 fixes: the call as it was made, the
 * state, the reason and the way out.
 *
 * <p>The state of a refused call is read again, in a transaction of its own,
 * after the refused one rolled back. That is a second read and it is paid
 * deliberately: a refusal that cannot say what the caller can do instead is
 * the refusal this surface exists to replace. Where the task cannot be read,
 * the refusal is {@code NOT_FOUND}, so a caller learns no state of a task it
 * may not see from the refusal it gets for acting on it.
 *
 * <p>Not transactional itself, and that is load-bearing: a kernel refusal
 * marks the transaction it is raised in for rollback, and a read in that
 * transaction would answer nothing.
 */
@ApplicationScoped
public class CallRouter {

    private static final String ADDRESS = "address";
    private static final String SCOPE = "scope";
    private static final String SELECTOR = "selector";
    private static final String CONFLICT_TOKEN = "conflict_token";
    private static final String RECEIPT = "receipt";
    private static final String DURATION = "duration";
    private static final String CONFIRMATION = "confirmation";
    private static final String KEY = "idempotency_key";
    private static final String REMARK = "remark";
    private static final String TEXT = "text";
    private static final String PART = "part";
    private static final String PARENT = "parent";
    private static final String CURATED_IN = "curated_in";
    private static final String APPARATUS = "apparatus";
    private static final String PAGE_SIZE = CallArguments.PAGE_SIZE;
    private static final String AFTER = "after";
    private static final String SORT = CallArguments.SORT;
    private static final String OPTION = "option";

    /** Where an unexpected failure's log line says the call was made, when no address reads. */
    private static final String UNREAD = "<unread>";

    /**
     * The first instant and the bound past the last a task stores, as the
     * start of a deferral and as the end of a lease: inside what the timestamp
     * columns hold, which is 4713 BC to 294276 AD. Measured: an instant past
     * that range parsed, reached the store and failed there as a defect, and
     * so did a lease whose end lies past it. The lower bound is the start of
     * the common era, a chosen margin and not the column's edge: nothing a
     * task stores lies earlier.
     */
    private static final Instant EARLIEST = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant LATEST = Instant.parse("+294276-01-01T00:00:00Z");

    /** The filters of a listing, by the names the kernel and the declaration share. */
    private static final List<String> FILTERS = TaskFilter.Field.wireNames();

    @Inject VerbSurface verbs;

    /** What a call answered: one of five shapes, or a refusal. */
    public sealed interface Outcome permits Answered, Listed, TextRead, Deleted, Refusal {
    }

    /**
     * One task, as a call on it answers it.
     *
     * @param head       whether the answer is the full head ({@code read},
     *                   {@code query}) or the lean one (every writing call)
     * @param receipt    the receipt of a claim, or null
     */
    public record Answered(ProcessVerb call, String address, TaskView view, boolean head,
                           List<NextList.Step> next, String waitingFor, String receipt)
        implements Outcome {
    }

    /**
     * A listing: one page of full heads, and the cursor of the next page,
     * which is null on the last.
     */
    public record Listed(List<Answered> heads, String nextCursor) implements Outcome {
    }

    /** One part of a task's text. */
    public record TextRead(String address, TaskTextView text) implements Outcome {
    }

    /** A deleted draft: the address it had, and nothing else. */
    public record Deleted(String address) implements Outcome {
    }

    /** A refusal, in its wire shape. */
    public record Refusal(Refused refused) implements Outcome {
    }

    /**
     * Makes one call.
     *
     * @param surface   the surface the call arrived on, which names the calls
     *                  in every answer and refusal
     * @param caller    who calls; asked inside, because a token that carries no
     *                  usable capacity is itself a refusal
     * @param name      the call's name as the caller wrote it
     * @param arguments the call's arguments, at both levels
     */
    public Outcome call(Surface surface, Supplier<Actor> caller, String name,
                        Map<String, Object> arguments) {
        ProcessVerb verb = name == null ? null : ProcessVerb.byName(surface, name);
        if (verb == null) {
            // The name that arrived is the caller's and stays with it (DEC-0043 R19).
            return new Refusal(Refused.callUnknown(VerbName.all(surface)));
        }
        VerbName call = VerbName.of(verb, surface);
        Map<String, Object> given = arguments == null ? Map.of() : arguments;
        Actor actor = null;
        try {
            CallArguments in = new CallArguments(verb, surface, given);
            actor = caller.get();
            return invoke(surface, actor, in);
        } catch (Refused refused) {
            return new Refusal(refused);
        } catch (DispatchException refused) {
            return new Refusal(dress(surface, actor, verb, call, given, refused));
        } catch (RuntimeException failure) {
            return new Refusal(UnexpectedFailures.refuse(call, whereIn(given), failure));
        }
    }

    // ======================================================================
    // The twenty-five
    // ======================================================================

    private Outcome invoke(Surface surface, Actor actor, CallArguments in) {
        ProcessVerb verb = in.verb();
        return switch (verb) {
            case CREATE -> create(surface, actor, in);
            case UPDATE -> answered(surface, actor, verb, verbs.update(actor, item(in, ADDRESS),
                changes(in), in.top(CONFLICT_TOKEN)), false, null);
            case DELETE -> new Deleted(verbs.delete(actor, item(in, ADDRESS),
                in.top(CONFLICT_TOKEN)));
            case READ -> answered(surface, actor, verb, verbs.read(actor, item(in, ADDRESS)),
                true, null);
            case READ_TEXT -> {
                VerbSurface.Item at = item(in, ADDRESS);
                TaskTextView text = verbs.readText(actor, at,
                    TextPart.valueOf(in.top(PART).toUpperCase(Locale.ROOT)));
                yield new TextRead(at.complete(), text);
            }
            case QUERY -> query(surface, actor, in);
            case ANNOTATE -> answered(surface, actor, verb, verbs.annotate(actor,
                item(in, ADDRESS), TextType.fromWireName(in.field(PART)), in.field(TEXT),
                in.call(), in.fieldPath(PART), IdempotencyKey.of(in.top(KEY))), false, null);
            case RELATE -> answered(surface, actor, verb, verbs.relate(actor, item(in, ADDRESS),
                item(in.fieldPath(CURATED_IN), in.field(CURATED_IN), in.call()),
                in.top(CONFLICT_TOKEN)),
                false, null);
            case UNRELATE -> answered(surface, actor, verb, verbs.unrelate(actor,
                item(in, ADDRESS), in.top(CONFLICT_TOKEN)), false, null);
            case CLAIM -> {
                VerbSurface.Claimed claimed = verbs.claim(item(in, ADDRESS),
                    callOf(actor, in, lease(in)), IdempotencyKey.of(in.top(KEY)));
                yield answered(surface, actor, verb, claimed.result(), false, claimed.receipt());
            }
            case CLAIM_NEXT -> claimNext(surface, actor, in);
            default -> answered(surface, actor, verb, verbs.act(item(in, ADDRESS),
                verb.transition(), callOf(actor, in, payloadOf(verb.transition(), in))),
                false, null);
        };
    }

    private Outcome create(Surface surface, Actor actor, CallArguments in) {
        String scope = scope(in, in.top(SCOPE));
        String selector = selector(in, in.top(SELECTOR));
        Integer parentNumber = null;
        String parent = in.top(PARENT);
        if (parent != null) {
            ArgumentPath path = in.topPath(PARENT);
            VerbSurface.Item root = item(path, parent, in.call());
            if (!root.scope().equals(scope) || !root.address().selector().equals(selector)) {
                throw Refused.argumentInvalid(in.call(), path, Form.PARENT_ELSEWHERE);
            }
            if (root.address().sub() != 0) {
                throw Refused.argumentInvalid(in.call(), path, Form.PARENT_NOT_A_ROOT);
            }
            parentNumber = root.address().number();
        }
        TaskService.Draft draft = new TaskService.Draft(in.field("title"),
            in.field(APPARATUS), in.field(TEXT), in.metadata().orElse(null));
        return answered(surface, actor, ProcessVerb.CREATE, verbs.create(actor, scope, selector,
            parentNumber, draft, IdempotencyKey.of(in.top(KEY))), false, null);
    }

    private Outcome claimNext(Surface surface, Actor actor, CallArguments in) {
        List<String> patterns = in.list(APPARATUS, Argument.Placement.TOP);
        Argument apparatus = in.declared(APPARATUS, Argument.Placement.TOP);
        int malformed = ApparatusPatterns.firstMalformedAt(patterns);
        if (malformed >= 0) {
            throw Refused.argumentInvalid(in.call(), position(apparatus, patterns, malformed),
                Form.APPARATUS_PATTERN);
        }
        int unbounded = ApparatusPatterns.firstUnboundedAt(patterns);
        if (unbounded >= 0) {
            throw Refused.argumentInvalid(in.call(), position(apparatus, patterns, unbounded),
                Form.APPARATUS_UNBOUNDED);
        }
        String scope = scope(in, in.top(SCOPE));
        String selector = selector(in, in.top(SELECTOR));
        VerbSurface.Claimed claimed = verbs.claimNext(scope, selector, patterns,
            callOf(actor, in, lease(in)), IdempotencyKey.of(in.top(KEY)));
        return answered(surface, actor, ProcessVerb.CLAIM_NEXT, claimed.result(), false,
            claimed.receipt());
    }

    private Outcome query(Surface surface, Actor actor, CallArguments in) {
        String scope = scope(in, in.top(SCOPE));
        String selector = selector(in, in.top(SELECTOR));
        Map<String, String> raw = in.filters(FILTERS);
        Map<String, String> filters = new LinkedHashMap<>();
        for (Map.Entry<String, String> filter : raw.entrySet()) {
            Argument argument = in.declared(filter.getKey(), Argument.Placement.TOP);
            List<String> values = List.of(filter.getValue().split(",", -1)).stream()
                .map(String::trim).toList();
            String value = filter.getKey().equals(TaskFilter.Field.ADDRESS.wireName())
                ? shortAddresses(in, argument, scope, selector, values)
                : filter.getValue();
            if (filter.getKey().equals(TaskFilter.Field.APPARATUS.wireName())) {
                int malformed = ApparatusPatterns.firstMalformedAt(values);
                if (malformed >= 0) {
                    throw Refused.argumentInvalid(in.call(),
                        position(argument, values, malformed), Form.APPARATUS_PATTERN);
                }
            }
            refuseFilterValues(in, argument, value, values);
            filters.put(filter.getKey(), value);
        }
        PageRequest page = PageRequest.of(in.wholeNumber(PAGE_SIZE), in.top(SORT),
            SortKey.Direction.of(in.top("direction")), in.top(AFTER));
        VerbSurface.Listing listing = verbs.query(actor, scope, selector,
            TaskFilter.of(filters), page);
        List<Answered> heads = listing.page().tasks().stream()
            .map(view -> answered(surface, actor, ProcessVerb.QUERY,
                new VerbSurface.Result(listing.scope(), view), true, null))
            .toList();
        return new Listed(heads, listing.page().nextCursor().orElse(null));
    }

    /**
     * Refuses a filter whose values the kernel cannot take, naming the first
     * value it refuses by its position where there are several, and the form
     * the filter takes — none of its values (DEC-0043 R18, R19).
     */
    private static void refuseFilterValues(CallArguments in, Argument argument, String value,
                                           List<String> values) {
        try {
            TaskFilter.of(Map.of(argument.name(), value));
            return;
        } catch (DispatchException refused) {
            // The kernel names the values it refused; they stay with it.
        }
        int at = 0;
        if (!argument.name().equals(TaskFilter.Field.ADDRESS.wireName())) {
            for (int i = 0; i < values.size(); i++) {
                try {
                    TaskFilter.of(Map.of(argument.name(), values.get(i)));
                } catch (DispatchException refused) {
                    at = i;
                    break;
                }
            }
        }
        throw Refused.argumentInvalid(in.call(), position(argument, values, at),
            filterForm(argument.name()));
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

    /**
     * The path a refusal of one of several values names: the argument with
     * the value's position where it carries several, the argument alone
     * where it carries one (DEC-0043 R19).
     */
    private static ArgumentPath position(Argument argument, List<String> values, int at) {
        return values.size() > 1 ? ArgumentPath.of(argument, at) : ArgumentPath.of(argument);
    }

    /** An address filter: complete addresses in, the kernel's short form out. */
    private static String shortAddresses(CallArguments in, Argument argument, String scope,
                                         String selector, List<String> values) {
        List<String> shortForms = new ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            ArgumentPath path = position(argument, values, i);
            VerbSurface.Item at = item(path, values.get(i), in.call());
            if (!at.scope().equals(scope) || !at.address().selector().equals(selector)) {
                throw Refused.argumentInvalid(in.call(), path, Form.ADDRESS_FILTER_ELSEWHERE);
            }
            shortForms.add(at.address().toString());
        }
        return String.join(",", shortForms);
    }

    // ======================================================================
    // What a transition carries
    // ======================================================================

    private static TaskCall callOf(Actor actor, CallArguments in, TaskInput payload) {
        return new TaskCall(actor, in.top(RECEIPT), in.top(CONFLICT_TOKEN),
            in.top(CONFIRMATION), payload);
    }

    /**
     * The payload of one transition, built from arguments the declaration has
     * already admitted, and refused by name where a combination is not one the
     * kernel can take. No call reaches a guard of the kernel that would answer
     * with a defect.
     */
    private static TaskInput payloadOf(TaskVerb verb, CallArguments in) {
        return switch (verb) {
            case SEND, ACCEPT -> TaskInput.NONE;
            case CLAIM, CLAIM_NEXT, RENEW, RESUME -> lease(in);
            case RELEASE, WITHDRAW -> new TaskInput.Remark(in.field(REMARK));
            case REJECT, FAIL, REWORK -> new TaskInput.RequiredRemark(in.field(REMARK));
            case DEFER -> new TaskInput.Deferral(instant(in, "not_before"), in.field(REMARK));
            case ASK -> question(in);
            case ANSWER -> reply(in);
            case HOLD -> new TaskInput.Pause(
                HoldReason.valueOf(in.field("reason").toUpperCase(Locale.ROOT)),
                in.field(REMARK));
            case DELIVER -> new TaskInput.Delivery(in.field(TEXT), in.metadata().orElse(null));
        };
    }

    private static TaskInput lease(CallArguments in) {
        String raw = in.top(DURATION);
        if (raw == null) {
            return TaskInput.NONE;
        }
        Duration duration;
        try {
            duration = Duration.parse(raw);
        } catch (DateTimeParseException e) {
            throw Refused.claimDurationInvalid(in.call(), in.topPath(DURATION));
        }
        if (duration.compareTo(Duration.between(Instant.now(), LATEST)) >= 0) {
            throw Refused.argumentInvalid(in.call(), in.topPath(DURATION), Form.LEASE_END);
        }
        return new TaskInput.Lease(duration);
    }

    private static Instant instant(CallArguments in, String name) {
        Instant at = parsedInstant(in.field(name));
        if (at == null) {
            throw Refused.argumentInvalid(in.call(), in.fieldPath(name), Form.INSTANT);
        }
        if (at.isBefore(EARLIEST) || !at.isBefore(LATEST)) {
            throw Refused.argumentInvalid(in.call(), in.fieldPath(name), Form.INSTANT_RANGE);
        }
        return at;
    }

    /** The instant ISO-8601 text names, in UTC or with an offset; null where it names none. */
    private static Instant parsedInstant(String raw) {
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException notUtc) {
            try {
                return OffsetDateTime.parse(raw).toInstant();
            } catch (DateTimeParseException e) {
                return null;
            }
        }
    }

    private static TaskInput question(CallArguments in) {
        List<String> options = in.list("options", Argument.Placement.FIELDS);
        boolean freeText = in.flag("free_text");
        if (options.isEmpty() && !freeText) {
            throw Refused.argumentMissing(in.call(), Refused.Missing.OPTIONS,
                List.of(in.fieldPath("options")));
        }
        return new TaskInput.Question(in.field("question"), options, freeText);
    }

    private static TaskInput reply(CallArguments in) {
        String option = in.field(OPTION);
        String text = in.field(TEXT);
        if (option == null && text == null) {
            throw Refused.argumentMissing(in.call(), Refused.Missing.ANSWER,
                List.of(in.fieldPath(OPTION), in.fieldPath(TEXT)));
        }
        if (option != null && text != null) {
            throw Refused.argumentInvalid(in.call(), in.fieldPath(TEXT), Form.ANSWER_ONE_OF_TWO);
        }
        return new TaskInput.Reply(option, text);
    }

    private static TaskService.Draft changes(CallArguments in) {
        TaskService.Draft changes = new TaskService.Draft(in.field("title"),
            in.field(APPARATUS), in.field(TEXT), in.metadata().orElse(null));
        if (changes.title() == null && changes.apparatus() == null && changes.text() == null
                && changes.metadata() == null) {
            throw Refused.argumentMissing(in.call(), Refused.Missing.CHANGE,
                List.of(ArgumentPath.FIELDS));
        }
        return changes;
    }

    // ======================================================================
    // Addresses, read once and refused by the argument that carried them
    // ======================================================================

    private static VerbSurface.Item item(CallArguments in, String argument) {
        return item(in.topPath(argument), in.top(argument), in.call());
    }

    private static VerbSurface.Item item(ArgumentPath path, String raw, VerbName call) {
        try {
            AddressParser.Parts parts = AddressParser.uri(raw);
            return new VerbSurface.Item(parts.scope(),
                AddressParser.item(parts.selector(), parts.id()));
        } catch (SurfaceException malformed) {
            throw Refused.argumentInvalid(call, path, Form.ADDRESS);
        }
    }

    private static String scope(CallArguments in, String raw) {
        try {
            return AddressParser.scope(raw);
        } catch (SurfaceException malformed) {
            throw Refused.argumentInvalid(in.call(), in.topPath(SCOPE), Form.SCOPE);
        }
    }

    private static String selector(CallArguments in, String raw) {
        try {
            return AddressParser.selector(raw);
        } catch (SurfaceException malformed) {
            throw Refused.argumentInvalid(in.call(), in.topPath(SELECTOR), Form.SELECTOR);
        }
    }

    // ======================================================================
    // Answers
    // ======================================================================

    private static Answered answered(Surface surface, Actor actor, ProcessVerb call,
                                     VerbSurface.Result result, boolean head, String receipt) {
        boolean afterClaim = call == ProcessVerb.CLAIM || call == ProcessVerb.CLAIM_NEXT;
        List<NextList.Step> next = NextList.of(surface, result.view(), actor, afterClaim);
        return new Answered(call, result.address(), result.view(), head, next,
            NextList.waitingFor(result.view(), next, Instant.now()), receipt);
    }

    // ======================================================================
    // Refusals from below, in the caller's vocabulary
    // ======================================================================

    /**
     * A kernel refusal as the caller is told it. The kernel's message is
     * discarded, and so are the values it names; what the caller reads is a
     * declared message, with the state read again after the refused call
     * rolled back.
     */
    private Refused dress(Surface surface, Actor actor, ProcessVerb verb, VerbName call,
                          Map<String, Object> given, DispatchException e) {
        RefusalCode code = ReasonMapping.of(e.reason());
        return switch (code) {
            case NOT_FOUND -> Refused.notFound();
            case UNEXPECTED_FAILURE -> UnexpectedFailures.refuse(call, whereIn(given), e);
            case SCOPE_KIND_UNSUPPORTED -> Refused.scopeKindUnsupported(call);
            case SCOPE_READ_ONLY -> Refused.scopeReadOnly(call);
            case SCOPE_LOCKED -> Refused.scopeLocked(call);
            case SELECTOR_UNKNOWN -> Refused.selectorUnknown(call);
            case IDEMPOTENCY_KEY_REUSED -> Refused.idempotencyKeyReused(call);
            case NOTHING_TO_TAKE -> Refused.nothingToTake(call,
                VerbName.of(ProcessVerb.QUERY, surface));
            case CLAIM_DURATION_INVALID -> Refused.claimDurationInvalid(call,
                pathIn(verb, DURATION, Argument.Placement.TOP));
            case PAGE_SIZE_REJECTED -> Refused.pageSizeRejected(call,
                pathIn(verb, PAGE_SIZE, Argument.Placement.TOP));
            case CURSOR_MALFORMED -> Refused.cursorMalformed(call,
                VerbName.of(ProcessVerb.QUERY, surface), pathIn(verb, AFTER, Argument.Placement.TOP));
            case CURSOR_FOREIGN -> Refused.cursorForeign(call, differing(verb, e.offenders()),
                pathIn(verb, AFTER, Argument.Placement.TOP));
            case SORT_UNDECLARED -> Refused.sortUndeclared(call,
                pathIn(verb, SORT, Argument.Placement.TOP));
            case ARGUMENT_MISSING -> missing(verb, call, e);
            case ARGUMENT_UNKNOWN -> Refused.argumentUnknown(call, false,
                ArgumentPath.of(verb.topArguments()));
            case ARGUMENT_INVALID -> invalid(verb, call, given, e);
            default -> about(surface, actor, verb, call, given, e, code);
        };
    }

    /** The refusals about a task the caller may see: they carry its situation. */
    private Refused about(Surface surface, Actor actor, ProcessVerb verb, VerbName call,
                          Map<String, Object> given, DispatchException e, RefusalCode code) {
        String target = targetIn(verb, given);
        if (actor == null) {
            // A token with no usable capacity reads nothing, so there is no
            // state to name; the refusal is about who the caller is.
            return Refused.ofRole(call, Refused.Part.CAPACITY);
        }
        if (target == null && code == RefusalCode.ROLE_DOES_NOT_ALLOW) {
            // A call on a collection that is the other part's: a draw by a
            // commissioner. No task is named, so none has a state to name.
            return Refused.ofRole(call, part(verb, e));
        }
        VerbSurface.Result result = target == null ? null : readQuietly(actor, target);
        if (result == null) {
            return Refused.notFound();
        }
        Refused.Situation at = situationOf(surface, actor, result);
        TaskView view = result.view();
        return switch (code) {
            case STATE_DOES_NOT_ALLOW -> Refused.ofState(call, at,
                view.state() == TaskState.CLOSED);
            case ROLE_DOES_NOT_ALLOW -> Refused.ofRole(call, at, part(verb, e));
            case NOT_THE_HOLDER -> Refused.notTheHolder(call, at, unheld(view, e),
                verb == ProcessVerb.READ_TEXT);
            case RECEIPT_WRONG -> Refused.receiptWrong(call, at);
            case CHILDREN_NOT_FINISHED -> Refused.childrenNotFinished(call, at,
                children(surface, actor, result, e), e.confirmation().orElse(null),
                e.reason() == DispatchException.Reason.CONFIRMATION_STALE);
            case DEFERRAL_PENDING -> Refused.deferralPending(call,
                VerbName.of(ProcessVerb.READ, surface), at);
            case CONFLICT_TOKEN_STALE -> Refused.conflictTokenStale(call, at,
                view.conflictToken());
            default -> UnexpectedFailures.refuse(call, result.address(), e);
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
     * What differs between a cursor's listing and the call, as the arguments
     * that carry it: the kernel says {@code filter} for the four filters, and
     * the refusal names them.
     */
    private static List<ArgumentName> differing(ProcessVerb verb, List<String> differ) {
        List<ArgumentName> names = new ArrayList<>();
        for (String part : differ) {
            List<String> carried = "filter".equals(part) ? FILTERS : List.of(part);
            for (String name : carried) {
                Argument argument = verb.argument(name, Argument.Placement.TOP);
                if (argument != null) {
                    names.add(ArgumentName.of(argument));
                }
            }
        }
        return names;
    }

    private static Refused missing(ProcessVerb verb, VerbName call, DispatchException e) {
        return switch (e.reason()) {
            case RECEIPT_ABSENT -> Refused.argumentMissing(call,
                pathIn(verb, RECEIPT, Argument.Placement.TOP));
            case CONFLICT_TOKEN_MISSING -> Refused.argumentMissing(call,
                pathIn(verb, CONFLICT_TOKEN, Argument.Placement.TOP));
            case ADDENDUM_TEXT_MISSING -> Refused.argumentMissing(call,
                pathIn(verb, TEXT, Argument.Placement.FIELDS));
            default -> Refused.argumentMissing(call, ArgumentPath.FIELDS);
        };
    }

    private Refused invalid(ProcessVerb verb, VerbName call, Map<String, Object> given,
                            DispatchException e) {
        return switch (e.reason()) {
            case METADATA_REFUSED -> Refused.argumentInvalid(call,
                pathIn(verb, "metadata", Argument.Placement.FIELDS), Form.METADATA);
            case CURATION_TARGET_SELF -> Refused.argumentInvalid(call,
                pathIn(verb, CURATED_IN, Argument.Placement.FIELDS), Form.CURATION_TARGET);
            case ADDENDUM_SUFFIX_EXHAUSTED -> Refused.argumentInvalid(call,
                pathIn(verb, PART, Argument.Placement.FIELDS), Form.ADDENDA_EXHAUSTED);
            case ANSWER_NOT_AN_OPTION -> {
                // The options are the question's text, so they are pointed
                // at and not listed: a refusal carries no text of a task.
                boolean option = fieldIn(given, OPTION) != null;
                yield option
                    ? Refused.argumentInvalid(call,
                        pathIn(verb, OPTION, Argument.Placement.FIELDS), Form.ANSWER_OPTION)
                    : Refused.argumentInvalid(call,
                        pathIn(verb, TEXT, Argument.Placement.FIELDS), Form.ANSWER_FREE_TEXT);
            }
            default -> Refused.valueInvalid(call);
        };
    }

    /** Whom the call belongs to, as a refusal of the caller's part names it. */
    private static Refused.Part part(ProcessVerb verb, DispatchException e) {
        if (e.reason() == DispatchException.Reason.RATIFICATION_NOT_PERMITTED) {
            return Refused.Part.COMMISSIONER_OTHER_THAN_THE_DELIVERER;
        }
        if (verb.role() == null) {
            return Refused.Part.AUTHOR;
        }
        return switch (verb.role()) {
            case COMMISSIONER, BYSTANDER -> Refused.Part.COMMISSIONER;
            case CANDIDATE -> Refused.Part.EXECUTOR;
            case HOLDER -> Refused.Part.HOLDER;
        };
    }

    private static Refused.Unheld unheld(TaskView view, DispatchException e) {
        if (e.reason() == DispatchException.Reason.LEASE_LAPSED) {
            return Refused.Unheld.LAPSED;
        }
        return view.holder() == HolderState.OTHER ? Refused.Unheld.OTHER : Refused.Unheld.NOBODY;
    }

    /** Each unfinished child, complete, with its state and the calls open on it. */
    private List<Map<String, Object>> children(Surface surface, Actor actor,
                                               VerbSurface.Result root, DispatchException e) {
        List<Map<String, Object>> children = new ArrayList<>();
        for (String named : e.offenders()) {
            int space = named.indexOf(' ');
            String shortForm = space < 0 ? named : named.substring(0, space);
            int slash = shortForm.indexOf('/');
            ExchangeAddress child = AddressParser.item(shortForm.substring(0, slash),
                shortForm.substring(slash + 1));
            VerbSurface.Result read = readQuietly(actor,
                new VerbSurface.Item(root.scope(), child).complete());
            Map<String, Object> unfinished = new LinkedHashMap<>();
            unfinished.put(ADDRESS, child.complete(root.scope()));
            unfinished.put(Refused.STATE, read == null
                ? named.substring(space + 2, named.length() - 1)
                : stateOf(read.view()));
            unfinished.put(Refused.NEXT, read == null ? List.of()
                : NextList.wire(NextList.of(surface, read.view(), actor, false)));
            children.add(unfinished);
        }
        return children;
    }

    private Refused.Situation situationOf(Surface surface, Actor actor,
                                          VerbSurface.Result result) {
        List<NextList.Step> next = NextList.of(surface, result.view(), actor, false);
        return new Refused.Situation(stateOf(result.view()), next,
            NextList.waitingFor(result.view(), next, Instant.now()));
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
        try {
            AddressParser.Parts parts = AddressParser.uri(raw);
            return new VerbSurface.Item(parts.scope(),
                AddressParser.item(parts.selector(), parts.id())).complete();
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    private VerbSurface.Result readQuietly(Actor actor, String complete) {
        if (complete == null) {
            return null;
        }
        try {
            AddressParser.Parts parts = AddressParser.uri(complete);
            return verbs.read(actor, new VerbSurface.Item(parts.scope(),
                AddressParser.item(parts.selector(), parts.id())));
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    /**
     * The address or the collection a call named, for the log line of an
     * unexpected failure (DEC-0051 decides what that line may carry).
     */
    private static String whereIn(Map<String, Object> given) {
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
        try {
            return AddressParser.completeCollection(AddressParser.scope(String.valueOf(scope)),
                AddressParser.selector(String.valueOf(selector)));
        } catch (RuntimeException malformed) {
            return UNREAD;
        }
    }

    @SuppressWarnings("unchecked")
    private static Object fieldIn(Map<String, Object> given, String name) {
        Object fields = given.get(CallArguments.FIELDS);
        return fields instanceof Map ? ((Map<String, Object>) fields).get(name) : null;
    }
}
