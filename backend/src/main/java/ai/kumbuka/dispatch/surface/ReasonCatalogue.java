package ai.kumbuka.dispatch.surface;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The message pattern and the remedy of every declared refusal.
 *
 * <p>This is the half of the declaration that makes "a refusal explains
 * itself" checkable rather than hoped for. A pattern here is the shape the
 * message takes; the values are filled in where the refusal is raised, because
 * that is the only place that knows them. Keeping the shape here and the
 * values there is what stops the same refusal being worded three ways by three
 * throw sites — the failure mode the measured surface had, where one refusal
 * named the kernel and another named the caller.
 *
 * <p><strong>The start-up guard reads this.</strong> Every {@link RefusalCode}
 * must have an entry and every entry must have a code; {@link
 * #requireComplete(Map)} is called at start-up and throws if either set has a
 * member the other lacks. That is the mechanism behind "a reason not in the
 * table cannot be returned": not a rule somebody keeps, but a service that
 * does not come up.
 *
 * <h2>Placeholders</h2>
 *
 * A pattern carries {@code {name}} placeholders, filled by {@link
 * #message(RefusalCode, Map)}. A placeholder with no value left is a defect
 * and is refused loudly rather than rendered as literal braces into a caller's
 * message — a half-filled message is worse than none, because it reads as
 * finished.
 */
public final class ReasonCatalogue {

    private ReasonCatalogue() {
    }

    /**
     * One declared reason: the code, the shape of its message, and what the
     * caller can do about it.
     *
     * @param code    the caller-facing reason, stable across rewordings
     * @param pattern the message shape, with {@code {name}} placeholders
     * @param remedy  what the caller can do, in the contract's own words. Not
     *                sent on the wire — {@code data.next} carries the calls —
     *                but declared, because a reason nobody could act on is one
     *                that should not have been declared.
     */
    public record Reason(RefusalCode code, String pattern, String remedy) {
    }

    /**
     * The one message the not-found class carries, transcribed from the
     * platform.
     *
     * <p><strong>Source:</strong> {@code Kumbuka-ai/platform}, {@code main},
     * {@code router/src/main/java/ai/kumbuka/router/surface/RouterException.java},
     * constant {@code NOT_FOUND_MESSAGE} (lines 35-37, read 2026-09-21) —
     * character for character. Copied and not imported: this service does not
     * compile against the router and must not.
     *
     * <p><strong>Why the router's words and not this service's own.</strong>
     * The DEC-0042 clause does not stop at the service boundary. Two hops
     * answering one condition in two wordings are two answers a caller can
     * tell apart by reading them: it learns which hop answered, and from that
     * whether a service stands behind a scheme at all — the enumeration oracle
     * ADR-0011 exists against. That this service's own three answers agree
     * with each other is the smaller half of the clause, and it was the only
     * half anything checked.
     *
     * <p>A constant rather than a pattern: it must be byte-identical across
     * "does not exist", "not visible to you" and "cannot be routed", and a
     * pattern with no placeholders is an invitation to add one.
     *
     * <p>What the text this replaced said at length, and what this one
     * deliberately leaves unsaid: that the causes are different conditions.
     * They answer alike because an answer that told them apart would let a
     * caller map what it may not see. The message names the remedy instead,
     * because the same remedy is true of all of them. That reasoning belongs
     * here, where a reader changing the constant meets it; on the wire it is
     * one more thing that could differ between two services saying the same
     * thing.
     *
     * <p>The contract copy of this service ({@code contract/assistant-surface.md})
     * carries the same text, so the comparison against it holds this constant.
     */
    public static final String NOT_FOUND_MESSAGE =
        "nothing is addressed here. Check the address, and that you are a member of "
            + "the scope it names.";

    /**
     * The terminal variant of {@link RefusalCode#STATE_DOES_NOT_ALLOW}.
     *
     * <p>Declared beside the ordinary pattern rather than derived from it: a
     * closed task has no way out, and "you can: nothing" is a sentence that
     * trails off where the honest answer is that the task is over.
     */
    public static final String TERMINAL_STATE_PATTERN =
        "{call} is not possible on {address}: it is {state} and finished. Nothing further "
            + "can be done with it.";

    /**
     * The remedy of every refusal a caller can act on by reading its own
     * {@code data}. Several reasons share it, and they share it because it is
     * one instruction.
     */
    private static final String READ_THE_NEXT_LIST = "the calls in data.next";

    private static final Map<RefusalCode, Reason> DECLARED = declare();

    private static Map<RefusalCode, Reason> declare() {
        Map<RefusalCode, Reason> declared = new LinkedHashMap<>();

        put(declared, RefusalCode.NOT_FOUND,
            NOT_FOUND_MESSAGE,
            "check scope and address");

        put(declared, RefusalCode.STATE_DOES_NOT_ALLOW,
            "{call} is not possible on {address}: it is {state}, and {call} applies {applies}. "
                + "You can: {calls}.",
            READ_THE_NEXT_LIST);

        put(declared, RefusalCode.ROLE_DOES_NOT_ALLOW,
            "{call} can only be made by {role}. You take part in {address} as "
                + "{participation}.",
            READ_THE_NEXT_LIST);

        put(declared, RefusalCode.NOT_THE_HOLDER,
            "{call} is the holder's call, and you do not hold {address}: {why}.",
            "take the task up first, or wait for its holder");

        put(declared, RefusalCode.RECEIPT_WRONG,
            "{call} needs the receipt your claim handed out for {address}: the receipt given "
                + "is not the one it holds.",
            "pass the receipt from the latest claim");

        put(declared, RefusalCode.CHILDREN_NOT_FINISHED,
            "{call} would close the bracket root {address} while {count} task(s) of the "
                + "bracket are unfinished: {offenders}. {confirm}",
            "repeat the call with data.confirmation, or finish the offenders first");

        put(declared, RefusalCode.DEFERRAL_PENDING,
            "{call} is not possible on {address} before {not_before}: the task was deferred "
                + "until then.",
            "take it up after that instant, or another task now");

        put(declared, RefusalCode.NOTHING_TO_TAKE,
            "Nothing in {collection} that your patterns match can be taken up right now: "
                + "every such task is a draft, held, paused, delivered, closed or deferred.",
            "list the tasks with the query call");

        put(declared, RefusalCode.CLAIM_DURATION_INVALID,
            "The duration {value} is not a positive ISO-8601 duration such as PT2H.",
            "correct the duration");

        put(declared, RefusalCode.ARGUMENT_UNKNOWN,
            "{subject} has no {kind} named {name}. It has: {known}.",
            "correct the name");

        put(declared, RefusalCode.ARGUMENT_MISSING,
            "{call} needs {name}: {what}.",
            "supply it");

        put(declared, RefusalCode.ARGUMENT_INVALID,
            "{name} = {value} is not valid for {call}: {why}.",
            "correct the value");

        put(declared, RefusalCode.CONFLICT_TOKEN_STALE,
            "{call} on {address} carries a conflict token that is not the one it holds: it "
                + "was changed since you read it. Its current token is {current}.",
            "read the task again and repeat with data.conflict_token");

        put(declared, RefusalCode.SELECTOR_UNKNOWN,
            "{selector} is not a bracket kind declared in scope {scope}. Declared: "
                + "{declared}.",
            "use a declared one");

        put(declared, RefusalCode.SCOPE_KIND_UNSUPPORTED,
            "{scope} is a {kind} scope, and this service does not carry tasks in one. Name a "
                + "project or a global scope instead.",
            "name a project or a global scope");

        put(declared, RefusalCode.SCOPE_READ_ONLY,
            "{call} writes, and you may read {scope} without writing to it. Reading it "
                + "is unaffected; the write right is granted with the membership.",
            "ask whoever administers the membership of this scope");

        put(declared, RefusalCode.SCOPE_LOCKED,
            "{scope} is locked, so it refuses every write whatever your role. Reading "
                + "it is unaffected. The lock is lifted where it was set.",
            "wait for the lock to be lifted, or read instead");

        put(declared, RefusalCode.IDEMPOTENCY_KEY_REUSED,
            "The idempotency key {key} was spent on a different call, or with different "
                + "arguments, in scope {scope} within the last 24 hours.",
            "choose a new key");

        put(declared, RefusalCode.CALL_NOT_AT_THIS_ADDRESS,
            "{call} cannot be made on {address}: it applies to {applies}.",
            "address the call where it applies");

        put(declared, RefusalCode.UNEXPECTED_FAILURE,
            "{call} on {address} failed unexpectedly. This is a defect, not a rule. "
                + "Nothing was changed. Report reference {reference}.",
            "report the reference");

        return java.util.Collections.unmodifiableMap(declared);
    }

    private static void put(Map<RefusalCode, Reason> into, RefusalCode code, String pattern,
                            String remedy) {
        into.put(code, new Reason(code, pattern, remedy));
    }

    /**
     * The declared reasons by code, for the start-up guard.
     *
     * <p>The map rather than the list, because the guard's question is "is
     * every code covered" and a list would have to be indexed to answer it.
     */
    public static Map<RefusalCode, Reason> byCode() {
        return DECLARED;
    }

    /** Every declared reason, in the order of the catalogue. */
    public static List<Reason> declared() {
        return List.copyOf(DECLARED.values());
    }

    /** One declared reason, or a failure that names the gap. */
    public static Reason of(RefusalCode code) {
        Reason reason = DECLARED.get(code);
        if (reason == null) {
            throw new IllegalStateException(
                code + " is not declared in the reason catalogue. A refusal with no "
                    + "declared pattern cannot be worded, and wording one here would be "
                    + "the second place refusals are decided.");
        }
        return reason;
    }

    /**
     * The message for a refusal, with its values filled in.
     *
     * <p>The values come from the place that raises the refusal, which is the
     * only place that knows them; the shape comes from here, so one refusal is
     * worded one way wherever it is raised.
     *
     * <p>An unfilled placeholder throws. A caller reading a message with a
     * brace left in it learns nothing and cannot tell the gap from the
     * service's ordinary prose — and the throw lands as {@code
     * UNEXPECTED_FAILURE}, which is the honest name for it.
     */
    public static String message(RefusalCode code, Map<String, String> values) {
        String rendered = fill(of(code).pattern(), values);
        requireFilled(code, rendered);
        return rendered;
    }

    /** The terminal-state variant, filled the same way. */
    public static String terminalStateMessage(Map<String, String> values) {
        String rendered = fill(TERMINAL_STATE_PATTERN, values);
        requireFilled(RefusalCode.STATE_DOES_NOT_ALLOW, rendered);
        return rendered;
    }

    private static String fill(String pattern, Map<String, String> values) {
        String rendered = pattern;
        for (Map.Entry<String, String> value : values.entrySet()) {
            rendered = rendered.replace("{" + value.getKey() + "}",
                String.valueOf(value.getValue()));
        }
        return rendered;
    }

    private static void requireFilled(RefusalCode code, String rendered) {
        if (rendered.contains("{") && rendered.contains("}")) {
            throw new IllegalStateException(
                "the message for " + code + " still carries an unfilled placeholder: "
                    + rendered + ". A half-filled message reads as finished, which is "
                    + "worse than no message at all.");
        }
    }

    /**
     * Refuses a catalogue that does not cover the codes, in either direction.
     *
     * <p>Called from the start-up guard. Both directions matter and they fail
     * differently: a code with no entry is a refusal that cannot be worded and
     * would escape as an unexpected failure the first time it is raised; an
     * entry with no code is a published promise that nothing can keep.
     *
     * <p>Public so that A8's red probe is a probe rather than a description.
     * The rule "the service refuses to start with an undeclared reason" can
     * only be OBSERVED if the check can be run against a catalogue that is
     * missing one, and the real catalogue is a constant that cannot be made
     * incomplete at runtime. Taking the map as an argument is what makes the
     * guard's behaviour testable without a switch nobody would ever flip in
     * production.
     */
    public static void requireComplete(Map<RefusalCode, Reason> catalogue) {
        List<String> undeclared = java.util.Arrays.stream(RefusalCode.values())
            .filter(code -> !catalogue.containsKey(code))
            .map(Enum::name)
            .toList();

        if (!undeclared.isEmpty()) {
            throw new IllegalStateException(
                "the reason catalogue does not declare " + undeclared + ". A reason not "
                    + "in the table cannot be returned, and the service refuses to start "
                    + "rather than raise one it cannot word.");
        }

        for (Reason reason : catalogue.values()) {
            if (reason.pattern() == null || reason.pattern().isBlank()
                || reason.remedy() == null || reason.remedy().isBlank()) {
                throw new IllegalStateException(
                    reason.code() + " is declared without a pattern or without a remedy. "
                        + "A refusal that cannot say what happened, or cannot say what to "
                        + "do about it, is not a declared refusal.");
            }
        }
    }
}
