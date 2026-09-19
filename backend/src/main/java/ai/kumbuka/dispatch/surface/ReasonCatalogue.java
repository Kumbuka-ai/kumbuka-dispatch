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
 * #requireComplete()} is called at start-up and throws if either set has a
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
     * The exact text of section 4.3, which is the same for all three of its
     * causes and carries no values at all.
     *
     * <p>A constant rather than a pattern: it must be byte-identical across
     * "does not exist", "not visible to you" and "cannot be routed", and a
     * pattern with no placeholders is an invitation to add one.
     */
    public static final String NOT_FOUND_MESSAGE =
        "Nothing is visible to you at the address you gave. The address or the scope may "
            + "be wrong, or you may lack access; for your protection and that of others "
            + "these cases are not told apart. Check the scope name and the number.";

    /**
     * The terminal variant of {@link RefusalCode#STATE_DOES_NOT_ALLOW}.
     *
     * <p>Declared beside the ordinary pattern rather than derived from it: the
     * contract gives two sentences, and "from closed you can: " followed by
     * nothing is a sentence that trails off where the honest answer is that
     * the exchange is over.
     */
    public static final String TERMINAL_STATE_PATTERN =
        "{call} is not possible on {address}: it is {state} and finished. Nothing further "
            + "can be done with it.";

    /**
     * The remedy of every refusal a caller can act on by reading its own
     * {@code data}. Three reasons share it, and they share it because it is
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
            "{call} is not possible on {address}: it is {state}. From {state} you can: "
                + "{calls}.",
            READ_THE_NEXT_LIST);

        put(declared, RefusalCode.ROLE_DOES_NOT_ALLOW,
            "{call} can only be made by the {role}. You take part in {address} as "
                + "{participation}.",
            READ_THE_NEXT_LIST);

        put(declared, RefusalCode.NOT_THE_HOLDER,
            "{address} is held by someone else until {until}. Only the holder can {does}.",
            "wait, or {read}");

        put(declared, RefusalCode.RECEIPT_MISSING,
            "{call} needs the receipt you received from {take} for {address}.",
            "pass the receipt from the take");

        put(declared, RefusalCode.RECEIPT_WRONG,
            "{call} needs the receipt you received from {take} for {address}: the "
                + "receipt given is not the one issued for {address}.",
            "pass the receipt from the take");

        put(declared, RefusalCode.NO_ANSWER_DELIVERED,
            "{address} has no delivered answer to accept. The holder delivers with "
                + "{deliver}.",
            "wait for the executor");

        put(declared, RefusalCode.CHILDREN_NOT_FINISHED,
            "{root} cannot be {ending} while {count} exchange(s) of the bracket are "
                + "unfinished: {offenders}.",
            "the calls in each offender's next");

        put(declared, RefusalCode.NOTHING_TO_TAKE,
            "Nothing in {collection} can be taken up right now: every exchange is "
                + "finished, held by someone, or waiting for its commissioner.",
            "{query}");

        put(declared, RefusalCode.CLAIM_DURATION_INVALID,
            "The duration {value} is not a positive ISO-8601 duration such as PT2H.",
            "correct the duration");

        put(declared, RefusalCode.ARGUMENT_UNKNOWN,
            "{call} has no argument named {name}. Its arguments are: {arguments}.",
            "correct the name");

        put(declared, RefusalCode.ARGUMENT_MISSING,
            "{call} needs {name}: {what}.",
            "supply it");

        put(declared, RefusalCode.ARGUMENT_INVALID,
            "{name} = {value} is not valid for {call}: {why}.",
            "correct the value");

        put(declared, RefusalCode.CONFLICT_TOKEN_MISSING,
            "{call} on {address} repeats the conflict token and none arrived. The token "
                + "is the one handed out with the last read of {address}.",
            "read again and repeat");

        put(declared, RefusalCode.CONFLICT_TOKEN_STALE,
            "{call} on {address} carries a conflict token that is not the one it holds: "
                + "{address} was changed since it was read.",
            "read again and repeat");

        put(declared, RefusalCode.SELECTOR_UNKNOWN,
            "{selector} is not a bracket kind declared in scope {scope}. Declared: "
                + "{declared}.",
            "use a declared one");

        put(declared, RefusalCode.IDEMPOTENCY_KEY_REUSED,
            "The idempotency key {key} was used for a different {call} in scope {scope} "
                + "within the last 24 hours.",
            "choose a new key");

        put(declared, RefusalCode.CALL_NOT_AT_THIS_ADDRESS,
            "{call} cannot be made on {address}: it applies to {applies}. On {address} you "
                + "can: {calls}.",
            READ_THE_NEXT_LIST);

        put(declared, RefusalCode.UNEXPECTED_FAILURE,
            "{call} on {address} failed unexpectedly. This is a defect, not a rule. "
                + "Nothing was changed. Report reference {reference}.",
            "report the reference");

        return Map.copyOf(declared);
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

    /** Every declared reason, in the order of section 4.4. */
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
     * The message for a refusal, with its step names and its values filled in.
     *
     * <p>Two fillings in one pass, and they are not the same kind of thing.
     * The <em>step</em> names come from the surface the caller called through,
     * per section 4.2, and no throw site supplies them; the <em>values</em>
     * come from the throw site, which is the only place that knows them.
     *
     * <p>An unfilled placeholder throws. A caller reading a message with a
     * brace left in it learns nothing and cannot tell the gap from the
     * service's ordinary prose — and the throw lands as {@code
     * UNEXPECTED_FAILURE}, which is the honest name for it.
     */
    public static String message(RefusalCode code, Surface surface,
                                 Map<String, String> values) {
        String rendered = fill(inVocabularyOf(of(code).pattern(), surface), values);
        requireFilled(code, rendered);
        return rendered;
    }

    /** The terminal-state variant, filled the same way. */
    public static String terminalStateMessage(Surface surface,
                                              Map<String, String> values) {
        String rendered = fill(inVocabularyOf(TERMINAL_STATE_PATTERN, surface), values);
        requireFilled(RefusalCode.STATE_DOES_NOT_ALLOW, rendered);
        return rendered;
    }

    /**
     * One pattern or remedy with its step names taken from a surface.
     *
     * <p>Public because the declaration publishes the patterns of the
     * assistant surface, and publishing them with a step placeholder still in
     * them would publish a shape no caller can read.
     */
    public static String inVocabularyOf(String pattern, Surface surface) {
        String rendered = pattern;
        for (SurfaceStep step : SurfaceStep.values()) {
            rendered = rendered.replace("{" + step.placeholder() + "}", step.on(surface));
        }
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
     */
    public static void requireComplete() {
        requireComplete(DECLARED);
    }

    /**
     * The same check, against a catalogue handed in.
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
