package ai.kumbuka.dispatch.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Whether one call of one verb is permitted on one task, and if not, why.
 *
 * <h2>The order of the checks is fixed (concept section 2.3)</h2>
 *
 * {@link #ORDER}: state; condition on an attribute; relation of the caller;
 * the lock on acceptance; receipt or conflict token; confirmation; payload.
 * The first that fails answers, so one fault always answers with one reason,
 * whatever else is wrong with the call.
 *
 * <p>The first four are about the situation and the caller; a list of the
 * calls open to a caller is the verbs for which they pass ({@link #open}). The
 * last three are faults of a request, which no list can know.
 *
 * <p>A pure function: it reads the {@link TaskSituation} and the {@link TaskCall}
 * and writes nothing. {@link TaskService} calls it before every write, under
 * the row lock, on the situation computed in the same transaction.
 */
public sealed interface Decision {

    /** The checks, in the order they run. */
    enum Check {
        STATE(true),
        CONDITION(true),
        RELATION(true),
        LOCK(true),
        PROOF(false),
        CONFIRMATION(false),
        PAYLOAD(false);

        private final boolean situational;

        Check(boolean situational) {
            this.situational = situational;
        }

        /** Whether the check is about the situation and the caller, not the request. */
        public boolean situational() {
            return situational;
        }
    }

    /** The order every decision runs its checks in. */
    List<Check> ORDER = List.of(Check.STATE, Check.CONDITION, Check.RELATION, Check.LOCK,
        Check.PROOF, Check.CONFIRMATION, Check.PAYLOAD);

    /** The call may proceed. */
    record Permitted() implements Decision {
    }

    /**
     * The call would close the task with the outcome it already closed with.
     *
     * <p>It succeeds and writes nothing. A sequence across two services that
     * is retried after a partial failure finds the task it already closed;
     * told that it is too late, it could never complete. The rule of the
     * running service, carried unchanged by the operator's decision on the
     * rules the target is silent on. Never listed as open: it does nothing.
     */
    record AlreadyThere() implements Decision {
    }

    /**
     * The call is refused.
     *
     * @param check        the check that failed
     * @param reason       the typed reason, from the service's catalogue
     * @param message      the specifics, for a human
     * @param offenders    what caused it, where naming it is the point
     * @param confirmation the confirmation handed out, or null
     */
    record Refused(Check check, DispatchException.Reason reason, String message,
                   List<String> offenders, String confirmation) implements Decision {

        public Refused {
            offenders = List.copyOf(offenders);
        }

        /** The refusal as the typed exception every caller of the kernel catches. */
        public DispatchException asException() {
            return new DispatchException(reason, message, offenders, confirmation);
        }
    }

    /** Decides one call of {@code verb} on {@code situation}. */
    static Decision of(TaskVerb verb, TaskSituation situation, TaskCall call) {
        if (verb.closes() && situation.state() == TaskState.CLOSED
                && situation.outcome() == verb.outcome()) {
            return new AlreadyThere();
        }
        for (Check check : ORDER) {
            Optional<Refused> refused = run(check, verb, situation, call);
            if (refused.isPresent()) {
                return refused.get();
            }
        }
        return new Permitted();
    }

    /** Whether {@code verb} is open to {@code caller}: the situational checks pass. */
    static boolean open(TaskVerb verb, TaskSituation situation, Actor caller) {
        TaskCall probe = TaskCall.by(caller);
        for (Check check : ORDER) {
            if (check.situational() && run(check, verb, situation, probe).isPresent()) {
                return false;
            }
        }
        return true;
    }

    /** The verbs open to {@code caller}, in table order. */
    static List<TaskVerb> openVerbs(TaskSituation situation, Actor caller) {
        return java.util.Arrays.stream(TaskVerb.values())
            .filter(verb -> open(verb, situation, caller))
            .toList();
    }

    private static Optional<Refused> run(Check check, TaskVerb verb, TaskSituation s, TaskCall call) {
        return switch (check) {
            case STATE -> Checks.state(verb, s, call.caller());
            case CONDITION -> Checks.condition(verb, s);
            case RELATION -> Checks.relation(verb, s, call.caller());
            case LOCK -> Checks.lock(verb, s, call.caller());
            case PROOF -> Checks.proof(verb, s, call);
            case CONFIRMATION -> Checks.confirmation(verb, s, call);
            case PAYLOAD -> Checks.payload(verb, s, call);
        };
    }

    /**
     * The confirmation for closing a bracket root with {@code verb}.
     *
     * <p>Computed from the root, the verb and the set of unfinished children,
     * and stored nowhere: when the set changes, so does the value, and the one
     * handed out earlier no longer matches.
     */
    static String confirmationFor(TaskVerb verb, TaskSituation root) {
        StringBuilder material = new StringBuilder()
            .append(root.identity()).append('\u001F').append(verb.wireName());
        root.unfinishedChildren().stream()
            .map(child -> child.identity().toString())
            .sorted()
            .forEach(id -> material.append('\u001F').append(id));
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                sha256.digest(material.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the platform", impossible);
        }
    }
}
