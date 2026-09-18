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
     * <p>Takes no arguments, which is the enforcement. A factory that accepted
     * an address or a call name would eventually be called with one, and the
     * three causes this refusal exists to blur would become distinguishable by
     * whichever detail leaked first.
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
    public static Refused ofState(String call, String address, String state, boolean terminal,
                                  List<NextCalculator.Step> next) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("call", call);
        values.put("address", address);
        values.put("state", state);
        values.put("calls", next.isEmpty()
            ? "nothing"
            : String.join(", ", next.stream().map(NextCalculator.Step::call).toList()));

        String message = terminal
            ? ReasonCatalogue.terminalStateMessage(values)
            : ReasonCatalogue.message(RefusalCode.STATE_DOES_NOT_ALLOW, values);

        return new Refused(RefusalCode.STATE_DOES_NOT_ALLOW, message,
            situation(call, state, next));
    }

    /** The call belongs to the other role, and the caller is told which part it has. */
    public static Refused ofRole(String call, String address, String role,
                                 Participation participation, String state,
                                 List<NextCalculator.Step> next) {
        String message = ReasonCatalogue.message(RefusalCode.ROLE_DOES_NOT_ALLOW, Map.of(
            "call", call,
            "role", role,
            "address", address,
            "participation", participation.name().toLowerCase(java.util.Locale.ROOT)));
        return new Refused(RefusalCode.ROLE_DOES_NOT_ALLOW, message,
            situation(call, state, next));
    }

    /** Somebody else holds it, and the caller is told until when. */
    public static Refused notTheHolder(String call, String address, String until, String does,
                                       String state, List<NextCalculator.Step> next) {
        String message = ReasonCatalogue.message(RefusalCode.NOT_THE_HOLDER, Map.of(
            "address", address, "until", until, "does", does));
        return new Refused(RefusalCode.NOT_THE_HOLDER, message, situation(call, state, next));
    }

    /** A receipt was needed and none arrived, or the wrong one did. */
    public static Refused receipt(RefusalCode code, String call, String address,
                                  String state, List<NextCalculator.Step> next) {
        String message = ReasonCatalogue.message(code,
            Map.of("call", call, "address", address));
        return new Refused(code, message, situation(call, state, next));
    }

    /** There is no delivered answer to accept. */
    public static Refused noAnswerDelivered(String call, String address, String state,
                                            List<NextCalculator.Step> next) {
        String message = ReasonCatalogue.message(RefusalCode.NO_ANSWER_DELIVERED,
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
     */
    public static Refused childrenNotFinished(String call, String root,
                                              List<Map<String, Object>> offenders,
                                              String state,
                                              List<NextCalculator.Step> next) {
        String named = String.join(", ", offenders.stream()
            .map(o -> o.get(ATTEMPTED) == null
                ? o.get("address") + " (" + o.get(STATE) + ")"
                : String.valueOf(o.get("address")))
            .toList());

        String message = ReasonCatalogue.message(RefusalCode.CHILDREN_NOT_FINISHED, Map.of(
            "root", root, "count", String.valueOf(offenders.size()), "offenders", named));

        Map<String, Object> data = new LinkedHashMap<>(situation(call, state, next));
        data.put(OFFENDERS, List.copyOf(offenders));
        return new Refused(RefusalCode.CHILDREN_NOT_FINISHED, message, data);
    }

    /** The draw found nothing free in a collection that exists. */
    public static Refused nothingToTake(String call, String collection) {
        String message = ReasonCatalogue.message(RefusalCode.NOTHING_TO_TAKE,
            Map.of("collection", collection));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(ATTEMPTED, call);
        data.put(NEXT, List.of(new NextCalculator.Step(ProcessVerb.QUERY.call(),
            "Lists the exchanges of this bracket kind.")));
        return new Refused(RefusalCode.NOTHING_TO_TAKE, message, data);
    }

    // ======================================================================
    // Form faults: nothing was written, so no state travels
    // ======================================================================

    /** An argument the call does not declare, at any depth. */
    public static Refused argumentUnknown(String call, String name, List<String> declared) {
        String message = ReasonCatalogue.message(RefusalCode.ARGUMENT_UNKNOWN, Map.of(
            "call", call, "name", name, "arguments", String.join(", ", declared)));
        return new Refused(RefusalCode.ARGUMENT_UNKNOWN, message, Map.of(ATTEMPTED, call));
    }

    /** A required argument did not arrive. */
    public static Refused argumentMissing(String call, String name, String what) {
        String message = ReasonCatalogue.message(RefusalCode.ARGUMENT_MISSING, Map.of(
            "call", call, "name", name, "what", what));
        return new Refused(RefusalCode.ARGUMENT_MISSING, message, Map.of(ATTEMPTED, call));
    }

    /** An argument arrived with a value it cannot take. */
    public static Refused argumentInvalid(String call, String name, String value,
                                          String why) {
        String message = ReasonCatalogue.message(RefusalCode.ARGUMENT_INVALID, Map.of(
            "call", call, "name", name, "value", String.valueOf(value), "why", why));
        return new Refused(RefusalCode.ARGUMENT_INVALID, message, Map.of(ATTEMPTED, call));
    }

    /** A duration that is not a positive ISO-8601 duration. */
    public static Refused claimDurationInvalid(String call, String value) {
        String message = ReasonCatalogue.message(RefusalCode.CLAIM_DURATION_INVALID,
            Map.of("value", String.valueOf(value)));
        return new Refused(RefusalCode.CLAIM_DURATION_INVALID, message,
            Map.of(ATTEMPTED, call));
    }

    /** A conflict token was required and none arrived, or a stale one did. */
    public static Refused conflictToken(RefusalCode code, String call, String address) {
        String message = ReasonCatalogue.message(code,
            Map.of("call", call, "address", address));
        return new Refused(code, message, Map.of(ATTEMPTED, call));
    }

    /** The bracket kind is not declared in this scope. */
    public static Refused selectorUnknown(String call, String selector, String scope,
                                          List<String> declared) {
        String message = ReasonCatalogue.message(RefusalCode.SELECTOR_UNKNOWN, Map.of(
            "selector", selector, "scope", scope,
            "declared", declared.isEmpty() ? "none" : String.join(", ", declared)));
        return new Refused(RefusalCode.SELECTOR_UNKNOWN, message, Map.of(ATTEMPTED, call));
    }

    /**
     * Ours, not the caller's.
     *
     * <p>Says that nothing was changed, because a caller that cannot tell a
     * failed call from a half-done one has to assume the worst and retry into
     * whatever state the failure left. The reference is what makes the report
     * actionable without the caller quoting a stack trace it cannot read.
     */
    public static Refused unexpected(String call, String address, String reference) {
        String message = ReasonCatalogue.message(RefusalCode.UNEXPECTED_FAILURE, Map.of(
            "call", call, "address", address, "reference", reference));
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
}
