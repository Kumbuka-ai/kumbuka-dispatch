package ai.kumbuka.dispatch.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The kind of one {@code task_text} row.
 *
 * <p>The reason given with {@code fail}, {@code reject}, {@code withdraw},
 * {@code hold}, {@code defer} or {@code release}, and the remark of a
 * {@code rework}, are {@link #REMARK} rows. A second delivery after a rework is
 * a second {@link #RETURN} row; the youngest is the valid one.
 */
public enum TextType {

    DISPATCH("dispatch"),
    RETURN("return"),
    QUESTION("question"),
    ANSWER("answer"),
    REMARK("remark");

    private final String wireName;

    TextType(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }

    /** The constant a stored value names. */
    public static TextType fromWireName(String value) {
        for (TextType t : values()) {
            if (t.wireName.equals(value)) {
                return t;
            }
        }
        throw new IllegalArgumentException("no text type '" + value + "'");
    }
}
