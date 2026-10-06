package ai.kumbuka.dispatch.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Why a task in {@code on_hold} is paused.
 *
 * <p>{@code question} is set by {@code ask} and lifted by the commissioner's
 * {@code answer}; {@code dependency} and {@code external} are set by
 * {@code hold} and lifted by the holder's {@code resume}. A task parked after
 * its third lapse is {@code external} without a holder.
 */
public enum HoldReason {

    QUESTION("question"),
    DEPENDENCY("dependency"),
    EXTERNAL("external");

    private final String wireName;

    HoldReason(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    /** The constant a stored value names, or null for a stored null. */
    static HoldReason fromWireName(String value) {
        if (value == null) {
            return null;
        }
        for (HoldReason r : values()) {
            if (r.wireName.equals(value)) {
                return r;
            }
        }
        throw new IllegalArgumentException("no hold reason '" + value + "'");
    }
}
