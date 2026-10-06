package ai.kumbuka.dispatch.surface;

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
 * <p>The message is built here, from the pattern in {@link ReasonCatalogue}
 * and the values the raising place supplies, so one refusal is worded one way
 * wherever it is raised. Every call name a factory receives is already the
 * name on the caller's surface: {@code dispatch_accept} on MCP, {@code accept}
 * on REST.
 *
 * <p>No refusal carries a text of a task: the values filled in are addresses,
 * states, call names and the caller's own arguments.
 */
public class Refused extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Keys of the refusal's {@code data}. */
    public static final String ATTEMPTED = "attempted";
    public static final String STATE = "state";
    public static final String NEXT = "next";
    public static final String WAITING_FOR = "waiting_for";
    public static final String OFFENDERS = "offenders";
    public static final String CONFIRMATION = "confirmation";
    public static final String CONFLICT_TOKEN = "conflict_token";

    private static final String P_CALL = "call";
    private static final String P_SCOPE = "scope";
    private static final String P_ADDRESS = "address";
    private static final String P_STATE = "state";
    private static final String P_NAME = "name";

    private final transient RefusalCode code;
    private final transient Map<String, Object> data;

    private Refused(RefusalCode code, String message, Map<String, Object> data) {
        super(message);
        this.code = code;
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
     * Where a refused call stood: the task's complete address, its state as a
     * caller reads it, the calls open to the caller with one sentence each,
     * and what the task waits for where none is.
     */
    public record Situation(String address, String state, List<NextList.Step> next,
                            String waitingFor) {

        public Situation {
            next = next == null ? List.of() : List.copyOf(next);
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
        return new Refused(RefusalCode.NOT_FOUND, ReasonCatalogue.NOT_FOUND_MESSAGE, null);
    }

    // ======================================================================
    // The ones about a task the caller may see: they carry its situation
    // ======================================================================

    /**
     * The state, or the condition on the hold, does not permit the call.
     *
     * @param applies where the call applies, as a phrase: "only in draft"
     */
    public static Refused ofState(String call, Situation at, boolean closed, String applies) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(P_CALL, call);
        values.put(P_ADDRESS, at.address());
        values.put(P_STATE, at.state());
        values.put("applies", applies);
        values.put("calls", named(at.next()));
        String message = closed
            ? ReasonCatalogue.terminalStateMessage(values)
            : ReasonCatalogue.message(RefusalCode.STATE_DOES_NOT_ALLOW, values);
        return new Refused(RefusalCode.STATE_DOES_NOT_ALLOW, message, situation(call, at));
    }

    /**
     * The call belongs to another part, and the caller is told which part it
     * has.
     *
     * @param role who makes the call, as a phrase: "the commissioner"
     */
    public static Refused ofRole(String call, Situation at, String role,
                                 Participation participation) {
        String message = ReasonCatalogue.message(RefusalCode.ROLE_DOES_NOT_ALLOW, Map.of(
            P_CALL, call, "role", role, P_ADDRESS, at.address(),
            "participation", participation.wireName()));
        return new Refused(RefusalCode.ROLE_DOES_NOT_ALLOW, message, situation(call, at));
    }

    /**
     * The call is the holder's and the caller does not hold the task.
     *
     * @param why which of the three it is: another holds it, nobody does, or
     *            the caller's lease ended
     */
    public static Refused notTheHolder(String call, Situation at, String why) {
        String message = ReasonCatalogue.message(RefusalCode.NOT_THE_HOLDER, Map.of(
            P_CALL, call, P_ADDRESS, at.address(), "why", why));
        return new Refused(RefusalCode.NOT_THE_HOLDER, message, situation(call, at));
    }

    /** A receipt arrived and is not the one the task holds. */
    public static Refused receiptWrong(String call, Situation at) {
        String message = ReasonCatalogue.message(RefusalCode.RECEIPT_WRONG,
            Map.of(P_CALL, call, P_ADDRESS, at.address()));
        return new Refused(RefusalCode.RECEIPT_WRONG, message, situation(call, at));
    }

    /**
     * The call would close a bracket root with unfinished children.
     *
     * @param offenders    each unfinished child: complete address, state, and
     *                     the calls open to the caller on it
     * @param confirmation what the call is repeated with to close them all
     * @param stale        whether the call carried a confirmation that no
     *                     longer holds
     */
    public static Refused childrenNotFinished(String call, Situation at,
                                              List<Map<String, Object>> offenders,
                                              String confirmation, boolean stale) {
        String listed = String.join(", ", offenders.stream()
            .map(o -> o.get(P_ADDRESS) + " (" + o.get(STATE) + ")")
            .toList());
        String confirm = stale
            ? "The confirmation given no longer holds: the unfinished tasks changed since it "
                + "was handed out. Repeat the call with the one in data.confirmation."
            : "Repeat the call with data.confirmation to withdraw them and close the root.";
        String message = ReasonCatalogue.message(RefusalCode.CHILDREN_NOT_FINISHED, Map.of(
            P_CALL, call, P_ADDRESS, at.address(),
            "count", String.valueOf(offenders.size()), OFFENDERS, listed, "confirm", confirm));
        Map<String, Object> data = situation(call, at);
        data.put(OFFENDERS, List.copyOf(offenders));
        data.put(CONFIRMATION, confirmation);
        return new Refused(RefusalCode.CHILDREN_NOT_FINISHED, message, data);
    }

    /** A claim on a task deferred until an instant that has not come. */
    public static Refused deferralPending(String call, Situation at, String notBefore) {
        String message = ReasonCatalogue.message(RefusalCode.DEFERRAL_PENDING, Map.of(
            P_CALL, call, P_ADDRESS, at.address(), "not_before", notBefore));
        return new Refused(RefusalCode.DEFERRAL_PENDING, message, situation(call, at));
    }

    /**
     * The token presented is not the one the task holds; the current one
     * travels in the message and in {@code data.conflict_token}.
     */
    public static Refused conflictTokenStale(String call, Situation at, String current) {
        String message = ReasonCatalogue.message(RefusalCode.CONFLICT_TOKEN_STALE,
            Map.of(P_CALL, call, P_ADDRESS, at.address(), "current", current));
        Map<String, Object> data = situation(call, at);
        data.put(CONFLICT_TOKEN, current);
        return new Refused(RefusalCode.CONFLICT_TOKEN_STALE, message, data);
    }

    // ======================================================================
    // The ones decided before a task is resolved: no state travels, because
    // naming one would tell a task the caller may see from one it may not
    // ======================================================================

    /** The draw found nothing in a collection that exists. */
    public static Refused nothingToTake(String call, String collection, String query) {
        String message = ReasonCatalogue.message(RefusalCode.NOTHING_TO_TAKE,
            Map.of("collection", collection));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(ATTEMPTED, call);
        data.put(NEXT, List.of(new NextList.Step(query,
            "Lists the tasks of this bracket kind with their state and apparatus.")));
        return new Refused(RefusalCode.NOTHING_TO_TAKE, message, data);
    }

    /** A duration that is not a positive ISO-8601 duration. */
    public static Refused claimDurationInvalid(String call, String value) {
        String message = ReasonCatalogue.message(RefusalCode.CLAIM_DURATION_INVALID,
            Map.of("value", String.valueOf(value)));
        return new Refused(RefusalCode.CLAIM_DURATION_INVALID, message, attempted(call));
    }

    /**
     * Something the declaration does not have: an argument at either level,
     * a filter, or a call.
     *
     * @param subject the call whose argument it is, or "this service" for a call
     * @param kind    "argument", "argument under fields", or "call"
     * @param known   what it has instead
     */
    public static Refused argumentUnknown(String attempted, String subject, String kind,
                                          String name, List<String> known) {
        String message = ReasonCatalogue.message(RefusalCode.ARGUMENT_UNKNOWN, Map.of(
            "subject", subject, "kind", kind, P_NAME, name,
            "known", known.isEmpty() ? "none" : String.join(", ", known)));
        return new Refused(RefusalCode.ARGUMENT_UNKNOWN, message, attempted(attempted));
    }

    /** A required argument did not arrive. */
    public static Refused argumentMissing(String call, String name, String what) {
        String message = ReasonCatalogue.message(RefusalCode.ARGUMENT_MISSING,
            Map.of(P_CALL, call, P_NAME, name, "what", what));
        return new Refused(RefusalCode.ARGUMENT_MISSING, message, attempted(call));
    }

    /**
     * An argument arrived with a value it cannot take.
     *
     * @param why a sentence of the surface's own, never the kernel's message
     */
    public static Refused argumentInvalid(String call, String name, String value, String why) {
        String message = ReasonCatalogue.message(RefusalCode.ARGUMENT_INVALID, Map.of(
            P_CALL, call, P_NAME, name, "value", String.valueOf(value), "why", why));
        return new Refused(RefusalCode.ARGUMENT_INVALID, message, attempted(call));
    }

    /** The bracket kind is not declared in this scope; the declared ones are named. */
    public static Refused selectorUnknown(String call, String selector, String scope,
                                          List<String> declared) {
        String message = ReasonCatalogue.message(RefusalCode.SELECTOR_UNKNOWN, Map.of(
            "selector", selector, P_SCOPE, scope,
            "declared", declared.isEmpty() ? "none" : String.join(", ", declared)));
        return new Refused(RefusalCode.SELECTOR_UNKNOWN, message, attempted(call));
    }

    /** The scope is of a kind this service does not carry tasks in. */
    public static Refused scopeKindUnsupported(String call, String scope, String kind) {
        String message = ReasonCatalogue.message(RefusalCode.SCOPE_KIND_UNSUPPORTED,
            Map.of(P_SCOPE, scope, "kind", kind));
        return new Refused(RefusalCode.SCOPE_KIND_UNSUPPORTED, message, attempted(call));
    }

    /** The caller may read this scope and not write to it. */
    public static Refused scopeReadOnly(String call, String scope) {
        String message = ReasonCatalogue.message(RefusalCode.SCOPE_READ_ONLY,
            Map.of(P_CALL, call, P_SCOPE, scope));
        return new Refused(RefusalCode.SCOPE_READ_ONLY, message, attempted(call));
    }

    /** The scope is locked and refuses every write. */
    public static Refused scopeLocked(String call, String scope) {
        String message = ReasonCatalogue.message(RefusalCode.SCOPE_LOCKED,
            Map.of(P_SCOPE, scope));
        return new Refused(RefusalCode.SCOPE_LOCKED, message, attempted(call));
    }

    /** The same key, a different call or different arguments. */
    public static Refused idempotencyKeyReused(String call, String key, String scope) {
        String message = ReasonCatalogue.message(RefusalCode.IDEMPOTENCY_KEY_REUSED,
            Map.of("key", key, P_SCOPE, scope));
        return new Refused(RefusalCode.IDEMPOTENCY_KEY_REUSED, message, attempted(call));
    }

    /** The call is real and does not apply where it was addressed. */
    public static Refused callNotAtThisAddress(String call, String address, String applies) {
        String message = ReasonCatalogue.message(RefusalCode.CALL_NOT_AT_THIS_ADDRESS,
            Map.of(P_CALL, call, P_ADDRESS, address, "applies", applies));
        return new Refused(RefusalCode.CALL_NOT_AT_THIS_ADDRESS, message, attempted(call));
    }

    /**
     * Ours, not the caller's. Says that nothing was changed, and carries the
     * reference {@link UnexpectedFailures} logged it under; call that, not this.
     */
    public static Refused unexpected(String call, String address, String reference) {
        String message = ReasonCatalogue.message(RefusalCode.UNEXPECTED_FAILURE,
            Map.of(P_CALL, call, P_ADDRESS, address, "reference", reference));
        Map<String, Object> data = attempted(call);
        data.put("reference", reference);
        return new Refused(RefusalCode.UNEXPECTED_FAILURE, message, data);
    }

    // ======================================================================

    private static Map<String, Object> attempted(String call) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(ATTEMPTED, call);
        return data;
    }

    /** The members every refusal about a task the caller may see carries. */
    private static Map<String, Object> situation(String call, Situation at) {
        Map<String, Object> data = attempted(call);
        data.put(STATE, at.state());
        data.put(NEXT, at.next());
        if (at.waitingFor() != null) {
            data.put(WAITING_FOR, at.waitingFor());
        }
        return data;
    }

    /** The offered calls as the message lists them, or the honest nothing. */
    private static String named(List<NextList.Step> next) {
        return next.isEmpty()
            ? "nothing"
            : String.join(", ", next.stream().map(NextList.Step::call).toList());
    }
}
