package ai.kumbuka.dispatch.domain;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Which rows of a task's texts make up one part (concept section 3.4).
 *
 * <p>{@code dispatch}: the commission. {@code return}: the youngest delivery,
 * the valid answer. {@code thread}: questions, their answers, remarks and the
 * earlier deliveries, in the order written. {@code addenda}: every addendum.
 * The answer to {@code dispatch} or {@code return} counts that text's addenda
 * without their content.
 */
final class TaskTexts {

    private TaskTexts() {
    }

    /** The part {@code part} of the texts of one task, given in the order written. */
    static TaskTextView part(TextPart part, List<TaskText> written) {
        return switch (part) {
            case DISPATCH -> single(part, written, TextType.DISPATCH);
            case RETURN -> single(part, written, TextType.RETURN);
            case THREAD -> new TaskTextView(part, entries(written, thread(written)), 0);
            case ADDENDA -> new TaskTextView(part, entries(written, TaskText::isAddendum), 0);
        };
    }

    /** The youngest base text of a type: the valid one where several exist. */
    static Optional<TaskText> youngestBase(List<TaskText> written, TextType type) {
        return written.stream()
            .filter(x -> !x.isAddendum() && x.type() == type)
            .max(Comparator.comparing(x -> x.id));
    }

    private static TaskTextView single(TextPart part, List<TaskText> written, TextType type) {
        Optional<TaskText> base = youngestBase(written, type);
        int addenda = (int) written.stream()
            .filter(x -> x.isAddendum() && x.type() == type)
            .count();
        return new TaskTextView(part, base.map(x -> List.of(entry(x))).orElse(List.of()),
            addenda);
    }

    /** Questions, answers, remarks, and every delivery but the valid one. */
    private static Predicate<TaskText> thread(List<TaskText> written) {
        Optional<TaskText> valid = youngestBase(written, TextType.RETURN);
        return x -> !x.isAddendum() && switch (x.type()) {
            case QUESTION, ANSWER, REMARK -> true;
            case RETURN -> valid.map(v -> !v.id.equals(x.id)).orElse(true);
            case DISPATCH -> false;
        };
    }

    private static List<TaskTextView.Entry> entries(List<TaskText> written,
                                                    Predicate<TaskText> in) {
        return written.stream().filter(in).map(TaskTexts::entry).toList();
    }

    private static TaskTextView.Entry entry(TaskText x) {
        return new TaskTextView.Entry(x.type(), x.addendumSuffix, x.text, x.createdAt);
    }
}
