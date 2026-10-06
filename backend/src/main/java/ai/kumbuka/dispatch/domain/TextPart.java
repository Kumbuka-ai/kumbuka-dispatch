package ai.kumbuka.dispatch.domain;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * The one part of a task's text that {@code read_text} answers.
 *
 * <p>One call, one part: no call answers several (concept section 3.4).
 */
public enum TextPart {

    /** The commission. */
    DISPATCH("dispatch"),
    /** The valid answer: the youngest delivery. */
    RETURN("return"),
    /** Questions, their answers, remarks and earlier deliveries, in order. */
    THREAD("thread"),
    /** Every addendum, on whichever text it supplements. */
    ADDENDA("addenda");

    private final String wireName;

    TextPart(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }
}
