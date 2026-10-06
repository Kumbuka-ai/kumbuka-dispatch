package ai.kumbuka.dispatch.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The six states of a task (TAR-0004 section 2).
 *
 * <p>The wire name is what the column {@code task.state} stores and what its
 * check constraint admits; the constant is a name for readers of the code.
 *
 * <p>What a task is in for a caller is never read off this column directly.
 * A lapsed lease leaves {@code active} in the row and means {@code open} or a
 * parked {@code on_hold}; {@link Situation#of} is the one place that turns the
 * stored value into the one that holds.
 */
public enum TaskState {

    /** Being written by the commissioner; mutable. */
    DRAFT("draft"),
    /** Sent; frozen against the verbs; drawable. */
    OPEN("open"),
    /** Held by one executor under a lease. */
    ACTIVE("active"),
    /** Started and paused; carries a {@link HoldReason}. */
    ON_HOLD("on_hold"),
    /** The answer is written and frozen against the executor; awaiting acceptance. */
    DELIVERED("delivered"),
    /** Terminal; carries an {@link Outcome}. */
    CLOSED("closed");

    private final String wireName;

    TaskState(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    /** The constant a stored or wire value names. */
    public static TaskState fromWireName(String value) {
        for (TaskState s : values()) {
            if (s.wireName.equals(value)) {
                return s;
            }
        }
        throw new IllegalArgumentException("no task state '" + value + "'");
    }
}
