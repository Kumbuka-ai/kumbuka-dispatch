package ai.kumbuka.dispatch.domain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Acceptance criterion 8: the task kernel serves no caller yet.
 *
 * <p>No class under {@code surface/} or {@code adapter/} refers to a type of
 * the new kernel. The verb surface binds it in step 4 of REA-0009; until then
 * the running service answers through the exchange kernel alone, and a
 * reference from the surface would be a caller reaching the new kernel ahead
 * of the step that is meant to switch it over.
 *
 * <p>The types are listed here by name rather than found by reflection over
 * the domain package, so a new kernel type that is not added to the list is
 * a gap a reader can see, not one that a clever lookup hides. Comments and
 * string literals are stripped before the search, so prose that names a type
 * is not a reference.
 */
class TaskKernelUnservedTest {

    /** The types of the new kernel, under domain/ and repository/. */
    static final List<String> KERNEL_TYPES = List.of(
        "Task", "TaskText", "SpentTaskKey", "TaskState", "HoldReason", "Outcome", "TextType",
        "TextPart", "TaskVerb", "TaskSituation", "Decision", "Checks", "TaskCall", "TaskInput",
        "TaskService", "TaskView", "TaskClaim", "TaskListing", "TaskTextView", "TaskTexts",
        "TaskFilter", "TaskRepository");

    @Test
    void no_class_of_the_surface_or_the_adapters_refers_to_the_new_kernel() {
        Path main = sourceRoot();
        List<String> offenders = new ArrayList<>();
        offenders.addAll(referencesUnder(main.resolve("ai/kumbuka/dispatch/surface")));
        offenders.addAll(referencesUnder(main.resolve("ai/kumbuka/dispatch/adapter")));
        assertThat(offenders)
            .as("the task kernel serves no caller in this step; the verb surface binds it in "
                + "step 4, and a reference here would reach it before that")
            .isEmpty();
    }

    /**
     * The red state, observed on every build: the detection reports a
     * reference in code, and not one in a comment or a string.
     */
    @Test
    void the_check_reports_a_reference_in_code_and_ignores_prose() {
        String code = """
            package ai.kumbuka.dispatch.surface;
            import ai.kumbuka.dispatch.domain.TaskService;
            /** Mentions TaskView in prose only. */
            class Planted { String s = "TaskVerb"; TaskService kernel; }
            """;
        assertThat(referencesIn("Planted.java", code))
            .as("RED STATE, observed: a field of a kernel type is a reference")
            .containsExactly("Planted.java -> TaskService");
        assertThat(referencesIn("Prose.java", "/** TaskView */ class Prose { String s = \"Task\"; }"))
            .as("and prose is not")
            .isEmpty();
    }

    // -----------------------------------------------------------------------

    private static List<String> referencesUnder(Path dir) {
        assertThat(Files.isDirectory(dir)).as("%s must exist", dir).isTrue();
        try (Stream<Path> files = Files.walk(dir)) {
            List<String> found = new ArrayList<>();
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                found.addAll(referencesIn(file.toString(), Files.readString(file)));
            }
            return found;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<String> referencesIn(String name, String source) {
        String code = codeOnly(source);
        List<String> found = new ArrayList<>();
        for (String type : KERNEL_TYPES) {
            if (Pattern.compile("(?<![\\w.])(?:ai\\.kumbuka\\.dispatch\\.(?:domain|repository)\\.)?"
                    + type + "\\b").matcher(code).find()) {
                found.add(name + " -> " + type);
            }
        }
        return found;
    }

    /**
     * The source with comments, string and character literals and text blocks
     * blanked out.
     *
     * <p>A scan rather than a regular expression: an alternation repeated per
     * character recurses per character in {@code java.util.regex}, and on the
     * CI runner's thread stack a long literal overflowed it.
     */
    static String codeOnly(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            int end = skippedFrom(source, i);
            if (end > i) {
                out.append(' ');
                i = end;
            } else {
                out.append(source.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    /** Where a comment or literal starting at {@code i} ends, or {@code i} when none starts. */
    private static int skippedFrom(String s, int i) {
        if (s.startsWith("/*", i)) {
            int close = s.indexOf("*/", i + 2);
            return close < 0 ? s.length() : close + 2;
        }
        if (s.startsWith("//", i)) {
            int close = s.indexOf('\n', i);
            return close < 0 ? s.length() : close;
        }
        if (s.startsWith("\"\"\"", i)) {
            int close = s.indexOf("\"\"\"", i + 3);
            return close < 0 ? s.length() : close + 3;
        }
        char c = s.charAt(i);
        if (c == '"' || c == '\'') {
            int j = i + 1;
            while (j < s.length() && s.charAt(j) != c) {
                j += s.charAt(j) == '\\' ? 2 : 1;
            }
            return Math.min(j + 1, s.length());
        }
        return i;
    }

    private static Path sourceRoot() {
        Path direct = Paths.get("src", "main", "java");
        return Files.isDirectory(direct) ? direct : Paths.get("backend", "src", "main", "java");
    }
}
