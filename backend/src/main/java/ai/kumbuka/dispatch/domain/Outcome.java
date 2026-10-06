package ai.kumbuka.dispatch.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/** How a {@code closed} task ended. Set exactly in {@code closed}. */
public enum Outcome {

    ACCEPTED("accepted"),
    REJECTED("rejected"),
    FAILED("failed"),
    WITHDRAWN("withdrawn");

    private final String wireName;

    Outcome(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    /** The constant a stored value names, or null for a stored null. */
    static Outcome fromWireName(String value) {
        if (value == null) {
            return null;
        }
        for (Outcome o : values()) {
            if (o.wireName.equals(value)) {
                return o;
            }
        }
        throw new IllegalArgumentException("no outcome '" + value + "'");
    }
}
