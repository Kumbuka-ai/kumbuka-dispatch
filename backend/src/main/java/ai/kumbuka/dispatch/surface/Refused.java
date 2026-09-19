package ai.kumbuka.dispatch.surface;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A refusal in the shape section 4 fixes: a stable reason, a message that
 * explains itself, and the data a caller acts on.
 *
 * <p>Raised at the surface, never in the kernel. The kernel keeps its own
 * typed refusals — it has to, because it is reached by callers this class
 * knows nothing about — and {@link ReasonMapping} turns each into one of
 * these at the boundary. That direction matters: a kernel that knew the
 * caller's vocabulary would be a kernel with a surface inside it, and the
 * kernel is what the router will reuse.
 *
 * <h2>Why the message is built here and not at the throw site</h2>
 *
 * A throw site knows the values; the catalogue knows the shape. Twenty-eight
 * throw sites each wording their own refusal is how the measured surface came
 * to answer "cannot takeup" in one place and a full sentence in another. So a
 * throw site supplies values under the names the pattern uses, and the wording
 * happens once.
 *
 * <h2>Why every factory takes a surface</h2>
 *
 * Because section 4.2 makes the vocabulary part of the message and not a
 * rendering detail on top of it. A pattern names a step; the surface decides
 * which call that step is made with. Measured in review on 2026-09-19: a REST caller
 * was told "the holder delivers with dispatch_deliver_return", and told to
 * {@code dispatch_query} in {@code data.next} — two calls it cannot make. The
 * parameter is what stops the vocabulary being decided by whichever throw site
 * was written last.
 */
public class Refused extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Keys of the refusal's {@code data}, by the names section 4.1 gives them. */
    public static final String ATTEMPTED = "attempted";
    public static final String STATE = "state";
    public static final String NEXT = "next";
    public static final String OFFENDERS = "offenders";

    private final transient RefusalCode code;
    private final transient Map<String, Object> data;

    private Refused(RefusalCode code, String message, Map<String, Object> data) {
        super(message);
        this.code = code;
        this.data = data == null ? null : Map.copyOf(data);
    }

    public RefusalCode code() {
        return code;
    }

    /**
     * The refusal's data, or null where it carries none.
     *
     * <p>Null and not an empty map, and the difference is load-bearing exactly
     * once: {@link RefusalCode#NOT_FOUND} must be byte-identical across its
     * three causes, and an empty {@code data: {}} is a key the other refusals
     * carry and this one would have to carry identically for ever. Absent is
     * the only shape that cannot drift.
     */
    public Map<String, Object> data() {
        return data;
    }

    // ======================================================================
    // The one that says nothing
    // ======================================================================

    /**
     * The deliberately indistinguishable refusal of section 4.3.
     *
     * <p>Takes no arguments, which is the enforcement — not even a surface.
     * A factory that accepted an address or a call name would eventually be
     * called with one, and the three causes this refusal exists to blur would
     * become distinguishable by whichever detail leaked first.
     */
    public static Refused notFound() {
        return new Refused(RefusalCode.NOT_FOUND, ReasonCatalogue.NOT_FOUND_MESSAGE, null);
    }

    // ======================================================================
    // The ones that explain
    // ======================================================================

    /**
     * A refusal that names the call, its state and the way out.
     *
     * @param call  the call as the caller made it, on its own surface
     * @param state the exchange's state, where the caller may see it
     * @param next  the calls open to the caller instead
     */
    public static Refused ofState(Surface surface, String call, String address, String state,
                                  boolean terminal, List<NextCalculator.Step> next) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("call", call);
        values.put("address", address);
        values.put("state", state);
        values.put("calls", named(next));

        String message = terminal
            ? ReasonCatalogue.terminalStateMessage(surface, values)
            : ReasonCatalogue.message(RefusalCode.STATE_DOES_NOT_ALLOW, surface, values);

        return new Refused(RefusalCode.STATE_DOES_NOT_ALLOW, message,
            situation(call, state, next));
    }

    /**
     * The call belongs to another part, and the caller is told which part it
     * actually has.
     *
     * @param required      the part section 5 reserves the call for
     * @param participation the caller's own part, from section 2. Never a
     *                      stand-in: the predecessor hardcoded "bystander" and
     *                      told a holder it took no part in the exchange it
     *                      was holding.
     */
    public static Refused ofRole(Surface surface, String call, String address,
                                 String required, Participation participation, String state,
                                 List<NextCalculator.Step> next) {
        String message = ReasonCatalogue.message(RefusalCode.ROLE_DOES_NOT_ALLOW, surface,
            Map.of("call", call, "role", required, "address", address,
                "participation", participation.wireName()));
        return new Refused(RefusalCode.ROLE_DOES_NOT_ALLOW, message,
            situation(call, state, next));
    }

    /**
     * Somebody else holds it, and the caller is told until when.
     *
     * @param until the ISO-8601 instant the claim lapses at. The contract's
     *              pattern names a time and the predecessor filled it with the
     *              phrase "its claim lapses", which is the sentence with the
     *              value taken out of it.
     */
    public static Refused notTheHolder(Surface surface, String call, String address,
                                       String until, String does, String state,
                                       List<NextCalculator.Step> next) {
        String message = ReasonCatalogue.message(RefusalCode.NOT_THE_HOLDER, surface, Map.of(
            "address", address, "until", until, "does", does));
        return new Refused(RefusalCode.NOT_THE_HOLDER, message, situation(call, state, next));
    }

    /** A receipt was needed and none arrived, or the wrong one did. */
    public static Refused receipt(Surface surface, RefusalCode code, String call,
                                  String address, String state,
                                  List<NextCalculator.Step> next) {
        String message = ReasonCatalogue.message(code, surface,
            Map.of("call", call, "address", address));
        return new Refused(code, message, situation(call, state, next));
    }

    /** There is no delivered answer to accept. */
    public static Refused noAnswerDelivered(Surface surface, String call, String address,
                                            String state,
                                            List<NextCalculator.Step> next) {
        String message = ReasonCatalogue.message(RefusalCode.NO_ANSWER_DELIVERED, surface,
            Map.of("address", address));
        return new Refused(RefusalCode.NO_ANSWER_DELIVERED, message,
            situation(call, state, next));
    }

    /**
     * The bracket has unfinished exchanges, each named with what would finish
     * it.
     *
     * <p>{@code offenders} travels under {@code data} and carries structure
     * rather than prose: measured on 2026-09-18, the list arrived as
     * {@code ["satellite/26.1 (draft)"]} — a member of its own, in a form a
     * caller has to parse back out of a sentence, with an address no call
     * accepts.
     *
     * @param ending {@code closed} or {@code cancelled}, per the contract's
     *               own {@code <closed / cancelled>}. Which of the two the
     *               caller attempted is the one thing this message cannot
     *               derive, and getting it wrong describes an act the caller
     *               did not make.
     */
    public static Refused childrenNotFinished(Surface surface, String call, String root,
                                              String ending,
                                              List<Map<String, Object>> offenders,
                                              String state,
                                              List<NextCalculator.Step> next) {
        String listed = String.join(", ", offenders.stream()
            .map(o -> o.get("address") + " (" + o.get(STATE) + ")")
            .toList());

        String message = ReasonCatalogue.message(RefusalCode.CHILDREN_NOT_FINISHED, surface,
            Map.of("root", root, "ending", ending, "count", String.valueOf(offenders.size()),
                "offenders", listed));

        Map<String, Object> data = new LinkedHashMap<>(situation(call, state, next));
        data.put(OFFENDERS, List.copyOf(offenders));
        return new Refused(RefusalCode.CHILDREN_NOT_FINISHED, message, data);
    }

    /** The draw found nothing free in a collection that exists. */
    public static Refused nothingToTake(Surface surface, String call, String collection) {
        String message = ReasonCatalogue.message(RefusalCode.NOTHING_TO_TAKE, surface,
            Map.of("collection", collection));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(ATTEMPTED, call);
        data.put(NEXT, List.of(new NextCalculator.Step(SurfaceStep.QUERY.on(surface),
            "Lists the exchanges of this bracket kind.")));
        return new Refused(RefusalCode.NOTHING_TO_TAKE, message, data);
    }

    /**
     * The call is real on this surface and does not apply at this address.
     *
     * <p>Its own code rather than {@link RefusalCode#ARGUMENT_INVALID}, which
     * the predecessor pressed into service here. The address is well formed
     * and names something real; what does not fit is the pairing. A caller
     * told its argument is invalid corrects an address that was right.
     */
    public static Refused callNotAtThisAddress(Surface surface, String call, String address,
                                               String applies, String state,
                                               List<NextCalculator.Step> next) {
        String message = ReasonCatalogue.message(RefusalCode.CALL_NOT_AT_THIS_ADDRESS,
            surface, Map.of("call", call, "address", address, "applies", applies,
                "calls", named(next)));
        return new Refused(RefusalCode.CALL_NOT_AT_THIS_ADDRESS, message,
            situation(call, state, next));
    }

    // ======================================================================
    // Form faults: nothing was written, so no state travels
    // ======================================================================

    /** An argument the call does not declare, at any depth. */
    public static Refused argumentUnknown(Surface surface, String call, String name,
                                          List<String> declared) {
        String message = ReasonCatalogue.message(RefusalCode.ARGUMENT_UNKNOWN, surface,
            Map.of("call", call, "name", name, "arguments", String.join(", ", declared)));
        return new Refused(RefusalCode.ARGUMENT_UNKNOWN, message, Map.of(ATTEMPTED, call));
    }

    /** A required argument did not arrive. */
    public static Refused argumentMissing(Surface surface, String call, String name,
                                          String what) {
        String message = ReasonCatalogue.message(RefusalCode.ARGUMENT_MISSING, surface,
            Map.of("call", call, "name", name, "what", what));
        return new Refused(RefusalCode.ARGUMENT_MISSING, message, Map.of(ATTEMPTED, call));
    }

    /**
     * An argument arrived with a value it cannot take.
     *
     * @param why a sentence of the pattern's own. Section 4.4 says so in as
     *            many words, and the predecessor passed the kernel's message
     *            here — which is how a short-form address reached a caller
     *            through the one refusal that takes free text.
     */
    public static Refused argumentInvalid(Surface surface, String call, String name,
                                          String value, String why) {
        String message = ReasonCatalogue.message(RefusalCode.ARGUMENT_INVALID, surface,
            Map.of("call", call, "name", name, "value", String.valueOf(value), "why", why));
        return new Refused(RefusalCode.ARGUMENT_INVALID, message, Map.of(ATTEMPTED, call));
    }

    /** A duration that is not a positive ISO-8601 duration. */
    public static Refused claimDurationInvalid(Surface surface, String call, String value) {
        String message = ReasonCatalogue.message(RefusalCode.CLAIM_DURATION_INVALID, surface,
            Map.of("value", String.valueOf(value)));
        return new Refused(RefusalCode.CLAIM_DURATION_INVALID, message,
            Map.of(ATTEMPTED, call));
    }

    /** A conflict token was required and none arrived, or a stale one did. */
    public static Refused conflictToken(Surface surface, RefusalCode code, String call,
                                        String address) {
        String message = ReasonCatalogue.message(code, surface,
            Map.of("call", call, "address", address));
        return new Refused(code, message, Map.of(ATTEMPTED, call));
    }

    /**
     * The bracket kind is not declared in this scope.
     *
     * @param declared every selector the scope declares. The contract's
     *                 pattern names them and the remedy is "use a declared
     *                 one"; a caller told to use a declared one and not told
     *                 which has been told nothing it can act on.
     */
    public static Refused selectorUnknown(Surface surface, String call, String selector,
                                          String scope, List<String> declared) {
        String message = ReasonCatalogue.message(RefusalCode.SELECTOR_UNKNOWN, surface,
            Map.of("selector", selector, "scope", scope,
                "declared", declared.isEmpty() ? "none" : String.join(", ", declared)));
        return new Refused(RefusalCode.SELECTOR_UNKNOWN, message, Map.of(ATTEMPTED, call));
    }

    /** The same key, a different call. */
    public static Refused idempotencyKeyReused(Surface surface, String call, String key,
                                               String scope) {
        String message = ReasonCatalogue.message(RefusalCode.IDEMPOTENCY_KEY_REUSED, surface,
            Map.of("key", key, "call", call, "scope", scope));
        return new Refused(RefusalCode.IDEMPOTENCY_KEY_REUSED, message,
            Map.of(ATTEMPTED, call));
    }

    /**
     * Ours, not the caller's.
     *
     * <p>Says that nothing was changed, because a caller that cannot tell a
     * failed call from a half-done one has to assume the worst and retry into
     * whatever state the failure left. The reference is what makes the report
     * actionable without the caller quoting a stack trace it cannot read — and
     * it is worth nothing unless the same reference is in the service's log,
     * which is why {@link UnexpectedFailures} and not this factory is what a
     * throw site calls.
     */
    public static Refused unexpected(Surface surface, String call, String address,
                                     String reference) {
        String message = ReasonCatalogue.message(RefusalCode.UNEXPECTED_FAILURE, surface,
            Map.of("call", call, "address", address, "reference", reference));
        return new Refused(RefusalCode.UNEXPECTED_FAILURE, message,
            Map.of(ATTEMPTED, call, "reference", reference));
    }

    /** The three members every refusal about a real exchange carries. */
    private static Map<String, Object> situation(String call, String state,
                                                 List<NextCalculator.Step> next) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(ATTEMPTED, call);
        data.put(STATE, state);
        data.put(NEXT, next == null ? List.of() : List.copyOf(next));
        return data;
    }

    /** The offered calls as the message lists them, or the honest nothing. */
    private static String named(List<NextCalculator.Step> next) {
        return next == null || next.isEmpty()
            ? "nothing"
            : String.join(", ", next.stream().map(NextCalculator.Step::call).toList());
    }
}
