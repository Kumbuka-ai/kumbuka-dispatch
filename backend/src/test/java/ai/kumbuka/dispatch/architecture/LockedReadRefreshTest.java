package ai.kumbuka.dispatch.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every locking read of the repository answers the row as it stands under the
 * lock.
 *
 * <p>A locking query takes the lock on the current row, but where the
 * transaction already holds the entity the persistence context answers the
 * object read earlier and not what the query found. Measured on this service:
 * a claim took over a task another executor held, the closing of a root
 * overwrote an accepted child, a draw lost a counted lapse, and a second root
 * created at once took the number of the first. {@code TaskRepository.underLock}
 * refreshes; this guard holds that no locking read gets past it.
 *
 * <h2>What counts</h2>
 *
 * A method of a class in {@code repository} locks when its code names a
 * pessimistic lock mode, calls {@code setLockMode}, or carries a row-locking
 * clause of SQL ({@code FOR UPDATE}, {@code FOR NO KEY UPDATE}, {@code FOR
 * SHARE}, {@code FOR KEY SHARE}). It refreshes when its code calls {@code
 * underLock} or {@code refresh}, or calls a method of the same class that
 * refreshes: {@code lock} hands its lock mode to {@code at}, and {@code at}
 * refreshes. A locking method that does not refresh is reported.
 *
 * <p>Read from code, comments removed, so a javadoc naming {@code underLock}
 * refreshes nothing. What a text reading cannot see: whether the refreshed
 * object is the one the method answers, and whether a refresh under a
 * condition covers every locking mode. Methods are matched by name, so
 * overloads share a verdict. The guard catches the omission it exists for --
 * a new locking method that answers what the context holds -- and not every
 * wrong refresh.
 *
 * <p>Runs as a plain unit test: it reads sources and needs no database.
 */
class LockedReadRefreshTest {

    /** The package whose locking reads are held to the rule. */
    private static final String REPOSITORY = "repository";

    private static final Pattern LOCKS = Pattern.compile(
        "PESSIMISTIC_|setLockMode\\s*+\\(|\\bFOR\\s++(?:NO\\s++KEY\\s++UPDATE|UPDATE|KEY\\s++SHARE|SHARE)\\b",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern REFRESHES = Pattern.compile("\\bunderLock\\b|\\.refresh\\s*+\\(");

    private static final Pattern ANNOTATION = Pattern.compile("@\\w++(?:\\s*+\\([^)]*+\\))?");

    /** Anchored at a word start, so a search does not restart inside every identifier. */
    private static final Pattern CALLED_NAME = Pattern.compile("\\b(\\w++)\\s*+\\(");

    @Test
    void every_locking_read_of_the_repository_answers_the_row_under_the_lock() {
        Path root = SourceTree.root("main");
        List<String> locking = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        for (Path file : SourceTree.files(root)) {
            if (!SourceTree.layerOf(root, file).equals(REPOSITORY)) {
                continue;
            }
            Verdict verdict = verdictOn(SourceTree.code(file));
            String type = SourceTree.fqcn(root, file);
            verdict.locking().forEach(m -> locking.add(type + "#" + m));
            verdict.offenders().forEach(m -> offenders.add(type + "#" + m));
        }

        assertThat(locking)
            .as("the repository takes row locks; a guard that finds none walks the wrong tree")
            .isNotEmpty();
        assertThat(offenders)
            .as("these methods lock a row and answer what the persistence context holds, "
                + "which may be an object read before the lock and changed since. Answer "
                + "through underLock")
            .isEmpty();
    }

    /**
     * The red state, observed on every build. {@code UnrefreshedLockFixture}
     * carries two locking reads without a refresh and one that refreshes
     * through a helper; the detection is required to report exactly the two.
     */
    @Test
    void the_guard_reports_a_locking_read_that_does_not_refresh_and_only_that() {
        Path root = SourceTree.root("test");
        Path fixture = root.resolve(Path.of("ai", "kumbuka", "dispatch", "fixture",
            "UnrefreshedLockFixture.java"));

        assertThat(verdictOn(SourceTree.code(fixture)).offenders())
            .as("RED STATE, observed: the fixture's two unrefreshed locking reads are "
                + "reported, and the one that refreshes through at is not")
            .containsExactlyInAnyOrder("lockedJpql", "lockedNative");
    }

    // -----------------------------------------------------------------------

    /** The locking methods of one class, and those among them that do not refresh. */
    private record Verdict(List<String> locking, List<String> offenders) {
    }

    private record Method(String name, String body) {
    }

    /**
     * Decides on one class's code. Parameterised on the text so the same
     * detection serves the assertion and its red state.
     */
    private static Verdict verdictOn(String code) {
        List<Method> methods = methods(code);

        Set<String> refreshing = new HashSet<>();
        for (Method m : methods) {
            if (REFRESHES.matcher(m.body()).find()) {
                refreshing.add(m.name());
            }
        }
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Method m : methods) {
                if (!refreshing.contains(m.name()) && calls(m.body(), refreshing)) {
                    refreshing.add(m.name());
                    grew = true;
                }
            }
        }

        List<String> locking = new ArrayList<>();
        List<String> offenders = new ArrayList<>();
        for (Method m : methods) {
            if (LOCKS.matcher(m.body()).find()) {
                locking.add(m.name());
                if (!refreshing.contains(m.name())) {
                    offenders.add(m.name());
                }
            }
        }
        return new Verdict(locking, offenders);
    }

    private static boolean calls(String body, Set<String> names) {
        Matcher called = CALLED_NAME.matcher(body);
        while (called.find()) {
            if (names.contains(called.group(1))) {
                return true;
            }
        }
        return false;
    }

    /**
     * The methods declared directly in the top-level class, each with its
     * body. Braces are counted on a copy with the literals blanked, so a brace
     * inside a string or a text block does not end a method; the body handed
     * out keeps its literals, where the SQL is.
     */
    private static List<Method> methods(String code) {
        String shape = blankLiterals(code);
        List<Method> methods = new ArrayList<>();
        int depth = 0;
        int headerStart = 0;
        int bodyStart = -1;
        String name = null;
        for (int i = 0; i < shape.length(); i++) {
            char c = shape.charAt(i);
            if (c == '{') {
                if (depth == 1) {
                    name = methodName(shape.substring(headerStart, i));
                    bodyStart = i;
                }
                depth++;
                if (depth == 1) {
                    headerStart = i + 1;
                }
            } else if (c == '}') {
                depth--;
                if (depth == 1) {
                    if (name != null) {
                        methods.add(new Method(name, code.substring(bodyStart, i + 1)));
                    }
                    name = null;
                    headerStart = i + 1;
                }
            } else if (c == ';' && depth == 1) {
                headerStart = i + 1;
            }
        }
        return methods;
    }

    /** The name a member header declares, or null where it declares no method. */
    private static String methodName(String header) {
        Matcher m = CALLED_NAME.matcher(ANNOTATION.matcher(header).replaceAll(" "));
        return m.find() ? m.group(1) : null;
    }

    /** The code with the content of every string, text block and char literal blanked. */
    private static String blankLiterals(String code) {
        StringBuilder out = new StringBuilder(code.length());
        int i = 0;
        while (i < code.length()) {
            if (code.startsWith("\"\"\"", i)) {
                int end = code.indexOf("\"\"\"", i + 3);
                end = end < 0 ? code.length() : end + 3;
                out.append(blank(code, i, end));
                i = end;
            } else if (code.charAt(i) == '"' || code.charAt(i) == '\'') {
                int end = closing(code, i);
                out.append(blank(code, i, end));
                i = end;
            } else {
                out.append(code.charAt(i));
                i++;
            }
        }
        return out.toString();
    }

    /** The index after the quote that closes the literal opened at {@code start}. */
    private static int closing(String code, int start) {
        char quote = code.charAt(start);
        int i = start + 1;
        while (i < code.length() && code.charAt(i) != quote) {
            i += code.charAt(i) == '\\' ? 2 : 1;
        }
        return Math.min(i + 1, code.length());
    }

    private static String blank(String code, int from, int to) {
        StringBuilder spaces = new StringBuilder(to - from);
        for (int i = from; i < to; i++) {
            spaces.append(code.charAt(i) == '\n' ? '\n' : ' ');
        }
        return spaces.toString();
    }
}
