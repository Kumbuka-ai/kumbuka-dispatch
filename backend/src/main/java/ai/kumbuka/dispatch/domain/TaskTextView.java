package ai.kumbuka.dispatch.domain;

import java.time.Instant;
import java.util.List;

/**
 * One part of the text of one task: what {@code read_text} answers, and the
 * only answer of the kernel that carries text.
 *
 * @param part     the part that was read
 * @param entries  the texts of that part, in the order written
 * @param addenda  how many addenda the part's text has, without their content;
 *                 counted for {@code dispatch} and {@code return}, zero otherwise
 */
public record TaskTextView(TextPart part, List<Entry> entries, int addenda) {

    /** One text, with its type, suffix and creation instant. */
    public record Entry(TextType type, String addendumSuffix, String text, Instant createdAt) {
    }

    public TaskTextView {
        entries = List.copyOf(entries);
    }
}
