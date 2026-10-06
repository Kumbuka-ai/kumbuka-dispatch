package ai.kumbuka.dispatch.domain;

import java.util.Objects;

/**
 * One call of a transition: who makes it, and what it presents.
 *
 * <p>The receipt, the conflict token and the confirmation are transport
 * artefacts (concept section 3.1); absent ones are null here and their
 * absence is what check 5 and check 6 of {@link Decision} refuse.
 *
 * @param caller        who calls; authorship is derived from it
 * @param receipt       the receipt a claim handed out, or null
 * @param conflictToken the token of the caller's last read, or null
 * @param confirmation  the confirmation a refusal handed out, or null
 * @param payload       what the verb carries; {@link TaskPayload#NONE} for nothing
 */
public record TaskCall(Actor caller, String receipt, String conflictToken,
                       String confirmation, TaskPayload payload) {

    public TaskCall {
        Objects.requireNonNull(caller, "caller");
        payload = payload == null ? TaskPayload.NONE : payload;
    }

    /** A call by {@code caller} presenting nothing yet. */
    public static TaskCall by(Actor caller) {
        return new TaskCall(caller, null, null, null, TaskPayload.NONE);
    }

    public TaskCall withReceipt(String value) {
        return new TaskCall(caller, value, conflictToken, confirmation, payload);
    }

    public TaskCall withConflictToken(String value) {
        return new TaskCall(caller, receipt, value, confirmation, payload);
    }

    public TaskCall withConfirmation(String value) {
        return new TaskCall(caller, receipt, conflictToken, value, payload);
    }

    public TaskCall with(TaskPayload value) {
        return new TaskCall(caller, receipt, conflictToken, confirmation, value);
    }
}
