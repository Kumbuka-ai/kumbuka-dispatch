package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.domain.Actor;
import ai.kumbuka.dispatch.domain.DispatchException;
import ai.kumbuka.dispatch.domain.ExchangeAddress;
import ai.kumbuka.dispatch.domain.HoldReason;
import ai.kumbuka.dispatch.domain.HolderState;
import ai.kumbuka.dispatch.domain.IdempotencyKey;
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

    /** The page bound of a listing that names none. */
    public static final int DEFAULT_PAGE = 100;

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
    private static final String NOT_VISIBLE = "not visible to you";

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

    /** A listing: full heads, and whether the page bound cut it. */
    public record Listed(List<Answered> heads, boolean cut) implements Outcome {
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
        ProcessVerb verb = ProcessVerb.byName(surface, name);
        if (verb == null) {
            return new Refusal(Refused.argumentUnknown(String.valueOf(name), "this service",
                "call", String.valueOf(name), ProcessVerb.names(surface)));
        }
        Map<String, Object> given = arguments == null ? Map.of() : arguments;
        Actor actor = null;
        try {
            CallArguments in = new CallArguments(verb, name, given);
            actor = caller.get();
            return invoke(surface, actor, in);
        } catch (Refused refused) {
            return new Refusal(refused);
        } catch (DispatchException refused) {
            return new Refusal(dress(surface, actor, verb, name, given, refused));
        } catch (RuntimeException failure) {
            return new Refusal(UnexpectedFailures.refuse(name, whereIn(given), failure));
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
                in.call()), false, null);
            case RELATE -> answered(surface, actor, verb, verbs.relate(actor, item(in, ADDRESS),
                item(in, "curated_in", in.field("curated_in")), in.top(CONFLICT_TOKEN)),
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
        String parent = in.top("parent");
        if (parent != null) {
            VerbSurface.Item root = item(in, "parent", parent);
            if (!root.scope().equals(scope) || !root.address().selector().equals(selector)) {
                throw Refused.argumentInvalid(in.call(), "parent", parent,
                    "it names another scope or bracket kind than the call does, and a child "
                        + "is created in its root's bracket");
            }
            if (root.address().sub() != 0) {
                throw Refused.argumentInvalid(in.call(), "parent", parent,
                    "a parent is a bracket root, the <number>.0 of its bracket");
            }
            parentNumber = root.address().number();
        }
        TaskService.Draft draft = new TaskService.Draft(in.field("title"),
            in.field("apparatus"), in.field(TEXT), in.metadata());
        return answered(surface, actor, ProcessVerb.CREATE, verbs.create(actor, scope, selector,
            parentNumber, draft, IdempotencyKey.of(in.top(KEY))), false, null);
    }

    private Outcome claimNext(Surface surface, Actor actor, CallArguments in) {
        List<String> patterns = in.list("apparatus", Argument.Placement.TOP);
        String malformed = ApparatusPatterns.firstMalformed(patterns);
        if (malformed != null) {
            throw Refused.argumentInvalid(in.call(), "apparatus", malformed,
                "a pattern is one or more of A-Z, a-z, 0-9, '+', '-' and '*', and '*' is the "
                    + "only wildcard; the underscore and the percent sign carry a meaning of "
                    + "their own in the comparison and are excluded");
        }
        String unbounded = ApparatusPatterns.firstUnbounded(patterns);
        if (unbounded != null) {
            throw Refused.argumentInvalid(in.call(), "apparatus", unbounded,
                "a pattern of nothing but '*' matches every apparatus, which is the blind "
                    + "draw the argument exists to refuse");
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
            String value = filter.getKey().equals(TaskFilter.Field.ADDRESS.wireName())
                ? shortAddresses(in, scope, selector, filter.getValue())
                : filter.getValue();
            if (filter.getKey().equals(TaskFilter.Field.APPARATUS.wireName())) {
                String malformed = ApparatusPatterns.firstMalformed(
                    List.of(value.split(",", -1)).stream().map(String::trim).toList());
                if (malformed != null) {
                    throw Refused.argumentInvalid(in.call(), filter.getKey(), malformed,
                        "a pattern is one or more of A-Z, a-z, 0-9, '+', '-' and '*'");
                }
            }
            try {
                TaskFilter.of(Map.of(filter.getKey(), value));
            } catch (DispatchException refused) {
                throw Refused.argumentInvalid(in.call(), filter.getKey(), filter.getValue(),
                    "it carries a value the filter cannot take: " + String.join(", ",
                        refused.offenders()));
            }
            filters.put(filter.getKey(), value);
        }
        Integer limit = in.integer("limit");
        if (limit != null && limit < 1) {
            throw Refused.argumentInvalid(in.call(), "limit", String.valueOf(limit),
                "the page bound is at least 1");
        }
        VerbSurface.Listing listing = verbs.query(actor, scope, selector,
            TaskFilter.of(filters), limit == null ? DEFAULT_PAGE : limit);
        List<Answered> heads = new ArrayList<>();
        for (TaskView view : listing.listing().tasks()) {
            heads.add(answered(surface, actor, ProcessVerb.QUERY,
                new VerbSurface.Result(listing.scope(), view), true, null));
        }
        return new Listed(List.copyOf(heads), listing.listing().cut());
    }

    /** An address filter: complete addresses in, the kernel's short form out. */
    private static String shortAddresses(CallArguments in, String scope, String selector,
                                         String value) {
        List<String> shortForms = new ArrayList<>();
        for (String raw : value.split(",", -1)) {
            VerbSurface.Item at = item(in, ADDRESS, raw.trim());
            if (!at.scope().equals(scope) || !at.address().selector().equals(selector)) {
                throw Refused.argumentInvalid(in.call(), ADDRESS, raw.trim(),
                    "a listing filters the tasks of its own scope and bracket kind");
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
            case DELIVER -> new TaskInput.Delivery(in.field(TEXT), in.metadata());
        };
    }

    private static TaskInput lease(CallArguments in) {
        String raw = in.top(DURATION);
        if (raw == null) {
            return TaskInput.NONE;
        }
        try {
            return new TaskInput.Lease(Duration.parse(raw));
        } catch (DateTimeParseException e) {
            throw Refused.claimDurationInvalid(in.call(), raw);
        }
    }

    private static Instant instant(CallArguments in, String name) {
        String raw = in.field(name);
        try {
            return Instant.parse(raw);
        } catch (DateTimeParseException notUtc) {
            try {
                return OffsetDateTime.parse(raw).toInstant();
            } catch (DateTimeParseException e) {
                throw Refused.argumentInvalid(in.call(), name, raw,
                    "an instant is written in ISO-8601 with its offset, such as "
                        + "2026-10-07T09:00:00Z");
            }
        }
    }

    private static TaskInput question(CallArguments in) {
        List<String> options = in.list("options", Argument.Placement.FIELDS);
        boolean freeText = in.flag("free_text");
        if (options.isEmpty() && !freeText) {
            throw Refused.argumentMissing(in.call(), CallArguments.FIELDS + ".options",
                "the answers the commissioner can choose from; a question with none admits "
                    + "free text, so give options or set free_text to true");
        }
        return new TaskInput.Question(in.field("question"), options, freeText);
    }

    private static TaskInput reply(CallArguments in) {
        String option = in.field("option");
        String text = in.field(TEXT);
        if (option == null && text == null) {
            throw Refused.argumentMissing(in.call(), CallArguments.FIELDS + ".option",
                "one of the question's options, or text where free text is admitted");
        }
        if (option != null && text != null) {
            throw Refused.argumentInvalid(in.call(), CallArguments.FIELDS + ".text", text,
                "an answer names an option or carries text, not both");
        }
        return new TaskInput.Reply(option, text);
    }

    private static TaskService.Draft changes(CallArguments in) {
        TaskService.Draft changes = new TaskService.Draft(in.field("title"),
            in.field("apparatus"), in.field(TEXT), in.metadata());
        if (changes.title() == null && changes.apparatus() == null && changes.text() == null
                && changes.metadata() == null) {
            throw Refused.argumentMissing(in.call(), CallArguments.FIELDS,
                "at least one of title, apparatus, text or metadata to change");
        }
        return changes;
    }

    // ======================================================================
    // Addresses, read once and refused by the argument that carried them
    // ======================================================================

    private static VerbSurface.Item item(CallArguments in, String argument) {
        return item(in, argument, in.top(argument));
    }

    private static VerbSurface.Item item(CallArguments in, String argument, String raw) {
        try {
            AddressParser.Parts parts = AddressParser.uri(raw);
            return new VerbSurface.Item(parts.scope(),
                AddressParser.item(parts.selector(), parts.id()));
        } catch (SurfaceException malformed) {
            throw Refused.argumentInvalid(in.call(), argument, raw,
                "a task's address is written dispatch://<scope>/<selector>/<number>.<sub>");
        }
    }

    private static String scope(CallArguments in, String raw) {
        try {
            return AddressParser.scope(raw);
        } catch (SurfaceException malformed) {
            throw Refused.argumentInvalid(in.call(), SCOPE, raw,
                "a scope's name is a DNS label: lower case, digits and inner hyphens");
        }
    }

    private static String selector(CallArguments in, String raw) {
        try {
            return AddressParser.selector(raw);
        } catch (SurfaceException malformed) {
            throw Refused.argumentInvalid(in.call(), SELECTOR, raw,
                "a bracket kind's name is lower case, digits and inner hyphens");
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
     * discarded; what the caller reads is worded from the catalogue, with the
     * state read again after the refused call rolled back.
     */
    private Refused dress(Surface surface, Actor actor, ProcessVerb verb, String name,
                          Map<String, Object> given, DispatchException e) {
        RefusalCode code = ReasonMapping.of(e.reason());
        String scope = scopeIn(given);
        return switch (code) {
            case NOT_FOUND -> Refused.notFound();
            case UNEXPECTED_FAILURE -> UnexpectedFailures.refuse(name, whereIn(given), e);
            case SCOPE_KIND_UNSUPPORTED -> Refused.scopeKindUnsupported(name, scope,
                e.offenders().isEmpty() ? "kind it does not serve" : e.offenders().get(0));
            case SCOPE_READ_ONLY -> Refused.scopeReadOnly(name, scope);
            case SCOPE_LOCKED -> Refused.scopeLocked(name, scope);
            case SELECTOR_UNKNOWN -> Refused.selectorUnknown(name,
                String.valueOf(given.get(SELECTOR)), scope, declaredSelectors(actor, scope));
            case IDEMPOTENCY_KEY_REUSED -> Refused.idempotencyKeyReused(name,
                String.valueOf(given.get(KEY)), scope);
            case NOTHING_TO_TAKE -> Refused.nothingToTake(name,
                AddressParser.completeCollection(scope, String.valueOf(given.get(SELECTOR))),
                ProcessVerb.QUERY.on(surface));
            case CLAIM_DURATION_INVALID -> Refused.claimDurationInvalid(name,
                String.valueOf(given.get(DURATION)));
            case ARGUMENT_MISSING -> missing(verb, name, e);
            case ARGUMENT_UNKNOWN -> Refused.argumentUnknown(name, name, "argument",
                e.offenders().isEmpty() ? "an argument" : String.join(", ", e.offenders()),
                verb.topNames());
            case ARGUMENT_INVALID -> invalid(surface, actor, name, given, e);
            default -> about(surface, actor, verb, name, given, e, code);
        };
    }

    /** The refusals about a task the caller may see: they carry its situation. */
    private Refused about(Surface surface, Actor actor, ProcessVerb verb, String name,
                          Map<String, Object> given, DispatchException e, RefusalCode code) {
        String target = targetIn(verb, given);
        if (actor == null) {
            // A token with no usable capacity reads nothing, so there is no
            // state to name; the refusal is about who the caller is.
            Refused.Situation unseen = new Refused.Situation(
                target == null ? whereIn(given) : target, NOT_VISIBLE, List.of(), null);
            return Refused.ofRole(name, unseen, "a caller holding the commissioning or the "
                + "executing capacity, and exactly one of them", Participation.BYSTANDER);
        }
        if (target == null && code == RefusalCode.ROLE_DOES_NOT_ALLOW) {
            // A call on a collection that is the other part's: a draw by a
            // commissioner. No task is named, so none has a state to name.
            Refused.Situation collection = new Refused.Situation(whereIn(given), NOT_VISIBLE,
                List.of(), null);
            return Refused.ofRole(name, collection, role(verb, e),
                actor.isConsole() ? Participation.COMMISSIONER : Participation.BYSTANDER);
        }
        VerbSurface.Result result = target == null ? null : readQuietly(actor, target);
        if (result == null) {
            return Refused.notFound();
        }
        Refused.Situation at = situationOf(surface, actor, result);
        TaskView view = result.view();
        return switch (code) {
            case STATE_DOES_NOT_ALLOW -> Refused.ofState(name, at,
                view.state() == TaskState.CLOSED, applies(verb));
            case ROLE_DOES_NOT_ALLOW -> Refused.ofRole(name, at, role(verb, e),
                Participation.of(actor, view));
            case NOT_THE_HOLDER -> Refused.notTheHolder(name, at, whyNotHeld(verb, view, e));
            case RECEIPT_WRONG -> Refused.receiptWrong(name, at);
            case CHILDREN_NOT_FINISHED -> Refused.childrenNotFinished(name, at,
                offenders(surface, actor, result, e), e.confirmation().orElse(null),
                e.reason() == DispatchException.Reason.CONFIRMATION_STALE);
            case DEFERRAL_PENDING -> Refused.deferralPending(name, at,
                String.valueOf(view.notBefore()));
            case CONFLICT_TOKEN_STALE -> Refused.conflictTokenStale(name, at,
                view.conflictToken());
            default -> UnexpectedFailures.refuse(name, at.address(), e);
        };
    }

    private static Refused missing(ProcessVerb verb, String name, DispatchException e) {
        return switch (e.reason()) {
            case RECEIPT_ABSENT -> Refused.argumentMissing(name, RECEIPT,
                "the receipt your claim handed out for this task");
            case CONFLICT_TOKEN_MISSING -> Refused.argumentMissing(name, CONFLICT_TOKEN,
                "the conflict token handed out with your last read of this task");
            case ADDENDUM_TEXT_MISSING -> Refused.argumentMissing(name,
                CallArguments.FIELDS + "." + TEXT, "the addendum's text");
            default -> Refused.argumentMissing(name, CallArguments.FIELDS,
                "the values " + verb.on(Surface.MCP) + " writes");
        };
    }

    private Refused invalid(Surface surface, Actor actor, String name,
                            Map<String, Object> given, DispatchException e) {
        return switch (e.reason()) {
            case METADATA_REFUSED -> Refused.argumentInvalid(name,
                CallArguments.FIELDS + ".metadata", "the object given",
                "metadata carries your own keys, each value a string or a list of strings of "
                    + "at most 512 characters, and no URL carrying credentials");
            case CURATION_TARGET_SELF -> Refused.argumentInvalid(name,
                CallArguments.FIELDS + ".curated_in", String.valueOf(fieldIn(given,
                    "curated_in")), "a task cannot be curated into itself");
            case ADDENDUM_SUFFIX_EXHAUSTED -> Refused.argumentInvalid(name,
                CallArguments.FIELDS + "." + PART, String.valueOf(fieldIn(given, PART)),
                "that text already carries addenda a to z, and a further letter would "
                    + "reissue one that exists");
            case ANSWER_NOT_AN_OPTION -> {
                Object option = fieldIn(given, "option");
                VerbSurface.Result result = actor == null ? null
                    : readQuietly(actor, targetIn(ProcessVerb.ANSWER, given));
                Object options = result == null || result.view().questionOptions() == null
                    ? "the question's options"
                    : result.view().questionOptions().get("options");
                yield Refused.argumentInvalid(name, CallArguments.FIELDS
                        + (option != null ? ".option" : ".text"),
                    String.valueOf(option != null ? option : fieldIn(given, TEXT)),
                    option != null
                        ? "it is one of " + options
                        : "the question admits no free text; name one of " + options);
            }
            default -> Refused.argumentInvalid(name, "a value given",
                String.join(", ", e.offenders()), "it is not a value this call takes");
        };
    }

    /** Where the call applies, as a refusal of its state names it. */
    private static String applies(ProcessVerb verb) {
        TaskVerb row = verb.transition();
        if (row == null) {
            return switch (verb) {
                case UPDATE, DELETE -> "only to a draft";
                case ANNOTATE -> "only once the task is sent";
                case RELATE, UNRELATE -> "only to a closed task";
                case CREATE -> "only under a bracket root that is not closed";
                default -> "where the task can be read";
            };
        }
        return switch (row.condition()) {
            case QUESTION_PENDING -> "only on_hold for a question";
            case PAUSED_BY_HOLDER -> "only on_hold for a dependency or something external";
            default -> "only in " + String.join(" or ", row.states().stream()
                .map(TaskState::wireName).sorted().toList());
        };
    }

    /** Who makes the call, as a refusal of the caller's part names it. */
    private static String role(ProcessVerb verb, DispatchException e) {
        if (e.reason() == DispatchException.Reason.RATIFICATION_NOT_PERMITTED) {
            return "the commissioner, and never by the identity that delivered the answer";
        }
        if (verb.role() == null) {
            return "the identity that wrote the text it supplements";
        }
        return switch (verb.role()) {
            case COMMISSIONER -> "the commissioner";
            case CANDIDATE -> "an executor";
            case HOLDER -> "its holder";
            case BYSTANDER -> "nobody";
        };
    }

    private static String whyNotHeld(ProcessVerb verb, TaskView view, DispatchException e) {
        String why;
        if (e.reason() == DispatchException.Reason.LEASE_LAPSED) {
            why = "your lease on it ended";
        } else if (view.holder() == HolderState.OTHER) {
            why = "another executor holds it";
        } else {
            why = "nobody holds it";
        }
        return verb == ProcessVerb.READ_TEXT
            ? "an executor reads the text only of a task it holds, and " + why
            : why;
    }

    /** Each unfinished child, complete, with its state and the calls open on it. */
    private List<Map<String, Object>> offenders(Surface surface, Actor actor,
                                               VerbSurface.Result root, DispatchException e) {
        List<Map<String, Object>> offenders = new ArrayList<>();
        for (String named : e.offenders()) {
            int space = named.indexOf(' ');
            String shortForm = space < 0 ? named : named.substring(0, space);
            int slash = shortForm.indexOf('/');
            ExchangeAddress child = AddressParser.item(shortForm.substring(0, slash),
                shortForm.substring(slash + 1));
            VerbSurface.Result read = readQuietly(actor,
                new VerbSurface.Item(root.scope(), child).complete());
            Map<String, Object> offender = new LinkedHashMap<>();
            offender.put(ADDRESS, child.complete(root.scope()));
            offender.put(Refused.STATE, read == null
                ? named.substring(space + 2, named.length() - 1)
                : stateOf(read.view()));
            offender.put(Refused.NEXT, read == null ? List.of()
                : NextList.of(surface, read.view(), actor, false));
            offenders.add(offender);
        }
        return offenders;
    }

    private Refused.Situation situationOf(Surface surface, Actor actor,
                                          VerbSurface.Result result) {
        List<NextList.Step> next = NextList.of(surface, result.view(), actor, false);
        return new Refused.Situation(result.address(), stateOf(result.view()), next,
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

    /** The task a refused call was about: its address, or a create's parent. */
    private static String targetIn(ProcessVerb verb, Map<String, Object> given) {
        Object target = verb == ProcessVerb.CREATE ? given.get("parent") : given.get(ADDRESS);
        return target == null ? null : String.valueOf(target);
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

    private List<String> declaredSelectors(Actor actor, String scope) {
        if (actor == null || scope == null) {
            return List.of();
        }
        try {
            return verbs.declaredSelectors(actor, scope);
        } catch (RuntimeException invisible) {
            // A scope the caller may not see declares nothing it may know about.
            return List.of();
        }
    }

    /** The scope as the caller named it: the argument, or the scope of the address. */
    private static String scopeIn(Map<String, Object> given) {
        Object named = given.get(SCOPE);
        if (named != null) {
            return String.valueOf(named);
        }
        Object address = given.get(ADDRESS);
        if (address == null) {
            return "the scope named";
        }
        String raw = String.valueOf(address);
        int start = raw.indexOf("://");
        if (start < 0) {
            return "the scope named";
        }
        int end = raw.indexOf('/', start + 3);
        return end < 0 ? raw.substring(start + 3) : raw.substring(start + 3, end);
    }

    /** The address or the collection a call named, for a refusal that names where. */
    private static String whereIn(Map<String, Object> given) {
        Object address = given.get(ADDRESS);
        if (address != null) {
            return String.valueOf(address);
        }
        Object scope = given.get(SCOPE);
        Object selector = given.get(SELECTOR);
        return scope == null || selector == null
            ? "the address given"
            : "dispatch://" + scope + "/" + selector;
    }

    @SuppressWarnings("unchecked")
    private static Object fieldIn(Map<String, Object> given, String name) {
        Object fields = given.get(CallArguments.FIELDS);
        return fields instanceof Map ? ((Map<String, Object>) fields).get(name) : null;
    }
}
