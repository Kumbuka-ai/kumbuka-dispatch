package ai.kumbuka.dispatch.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No production path mints an {@code UNEXPECTED_FAILURE} reference without
 * logging it.
 *
 * <p>The guarantee is section 4.4's: the reference a caller is told to report
 * "is written to the service's log with the failure, on every path that raises
 * it". {@code UnexpectedFailures.refuse} is the one place that keeps the two
 * together, and {@code UnexpectedFailureReferenceTest} watches it do so.
 *
 * <p>This is what keeps them together as the tree grows. {@code
 * Refused.unexpected} stays public — the probes need to build the envelope
 * without raising a failure — so a seventh throw site could call it directly,
 * and that is exactly the kind of thing added in a hurry beside six that do it
 * right. Measured in review on 2026-09-19: six sites, one of which logged, and
 * nothing anywhere said the other five were wrong.
 */
class UnmintedReferenceTest {

    /** The one file that may call the bare factory, because it is the mint. */
    private static final String THE_MINT = "UnexpectedFailures.java";

    @Test
    void every_unexpected_failure_is_minted_where_it_is_logged() {
        Path root = SourceTree.root("main");
        List<String> offenders = new ArrayList<>();

        for (Path source : SourceTree.files(root)) {
            if (source.getFileName().toString().equals(THE_MINT)) {
                continue;
            }
            if (SourceTree.code(source).contains("Refused.unexpected(")) {
                offenders.add(source.getFileName().toString());
            }
        }

        assertThat(offenders)
            .as("a reference minted outside %s is a reference the log does not carry, "
                + "while the caller is told to report it anyway. Call "
                + "UnexpectedFailures.refuse instead", THE_MINT)
            .isEmpty();

        // The scanner, pointed at the one file that IS allowed to make the
        // call. Without this the empty result above is equally consistent with
        // a scanner that walks the wrong tree or matches a string no file
        // contains — and reporting nothing is exactly what a clean tree looks
        // like.
        assertThat(theMintItself(root))
            .as("RED STATE, observed: the scanner finds the bare factory in %s, the one "
                + "file exempted from the rule. So the empty result above is the tree "
                + "being clean and not the check walking nothing", THE_MINT)
            .isTrue();
    }

    /** Whether the exempted file makes the call the rule is about. */
    private static boolean theMintItself(Path root) {
        return SourceTree.files(root).stream()
            .filter(f -> f.getFileName().toString().equals(THE_MINT))
            .anyMatch(f -> SourceTree.code(f).contains("Refused.unexpected("));
    }
}
