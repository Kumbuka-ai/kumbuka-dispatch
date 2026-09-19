package ai.kumbuka.dispatch.surface;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The reference an {@code UNEXPECTED_FAILURE} tells the caller to report is in
 * the log.
 *
 * <p>Section 4.4: the reference "is written to the service's log with the
 * failure, on every path that raises it". Measured in review on 2026-09-19 it was
 * not: six throw sites minted a fresh UUID each and one of them logged it, so
 * five references in five different refusals identified nothing at all. A
 * caller that did what the message asked would have been asking about a number
 * nobody could find — which is worse than no reference, because it looks like
 * a working support path.
 *
 * <p>What keeps the mint and the log together as the tree grows is a second
 * guard, {@code UnmintedReferenceTest} in the architecture package: it reads
 * the source tree and refuses any production call of the bare factory. It
 * lives there rather than here because that is where the probes that read the
 * tree live.
 */
class UnexpectedFailureReferenceTest {

    private final List<LogRecord> recorded = new ArrayList<>();
    private Handler handler;
    private Logger watched;

    @BeforeEach
    void listen() {
        watched = Logger.getLogger(UnexpectedFailures.class.getName());
        handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                recorded.add(record);
            }

            @Override
            public void flush() {
                // Nothing is buffered; the list IS the sink.
            }

            @Override
            public void close() {
                // Nothing to release.
            }
        };
        handler.setLevel(Level.ALL);
        watched.addHandler(handler);
        watched.setLevel(Level.ALL);
    }

    @AfterEach
    void stopListening() {
        watched.removeHandler(handler);
    }

    /**
     * The reference in the caller's message is the reference in the log
     * record, with the cause beside it.
     *
     * <p>Both halves matter: a log line without the reference cannot be found
     * from what the caller reports, and a reference without the cause is a
     * number somebody can look up and learn nothing from.
     */
    @Test
    void the_reference_the_caller_is_told_to_report_is_in_the_log() {
        RuntimeException cause = new IllegalStateException("a defect, not a rule");

        Refused refused = UnexpectedFailures.refuse(Surface.MCP, "dispatch_commission",
            "dispatch://kumbuka/sprint/1.0", cause);

        String reference = String.valueOf(refused.data().get("reference"));
        assertThat(refused.getMessage())
            .as("the caller is told to report a reference")
            .contains(reference);

        assertThat(recorded)
            .as("and the same reference is in the service's log, with the failure")
            .anySatisfy(record -> {
                assertThat(record.getLevel()).isEqualTo(Level.SEVERE);
                assertThat(String.valueOf(record.getMessage())).contains(reference);
                assertThat(record.getThrown())
                    .as("a reference somebody can look up and learn nothing from is a "
                        + "reference that is not doing its job")
                    .isSameAs(cause);
            });
    }

    /** A failure with no cause is still recorded, and still carries its reference. */
    @Test
    void a_failure_with_no_cause_is_still_recorded() {
        Refused refused = UnexpectedFailures.refuse(Surface.REST, "close",
            "dispatch://kumbuka/sprint/1.0", null);

        String reference = String.valueOf(refused.data().get("reference"));
        assertThat(recorded)
            .anySatisfy(record ->
                assertThat(String.valueOf(record.getMessage())).contains(reference));
    }

}
