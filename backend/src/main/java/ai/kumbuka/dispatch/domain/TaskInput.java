package ai.kumbuka.dispatch.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What a transition carries besides its address and its proof.
 *
 * <p>One record per shape, and each row of {@link TaskVerb} names the shape it
 * takes. A mandatory part that is absent is refused in the compact constructor
 * as a defect, not as a caller refusal, as {@link ExchangeService.ClaimTerms}
 * does for the apparatus patterns. No caller reaches this kernel yet; refusing
 * a missing argument by name before a payload is built is the verb surface's
 * work in step 4 of REA-0009, and until it exists nothing does it.
 * What only the task can judge -- whether an answer names one of the
 * question's options -- is check 7 of {@link Decision}.
 */
public sealed interface TaskInput {

    /** The lease a claim, a renewal and a resumption start without a stated duration. */
    Duration DEFAULT_LEASE = Duration.ofMinutes(30);

    /** Nothing: the call carries no payload. */
    record Nothing() implements TaskInput {
    }

    /** The one instance of {@link Nothing}. */
    TaskInput NONE = new Nothing();

    /** {@code send}: dispatch metadata frozen at the gate, or null to keep the draft's. */
    record Sending(Map<String, Object> metadata) implements TaskInput {
    }

    /** {@code claim}, {@code claim_next}, {@code renew}, {@code resume}: the lease's length. */
    record Lease(Duration duration) implements TaskInput {

        public Lease {
            Objects.requireNonNull(duration, "duration");
        }

        /** The lease a call without a stated duration gets. */
        public static Lease standard() {
            return new Lease(DEFAULT_LEASE);
        }
    }

    /** {@code release}, {@code withdraw}: a remark the caller may give, or null. */
    record Remark(String text) implements TaskInput {
    }

    /** {@code reject}, {@code fail}, {@code rework}: the remark is mandatory. */
    record RequiredRemark(String text) implements TaskInput {

        public RequiredRemark {
            requireText(text, "remark");
        }
    }

    /** {@code defer}: the instant before which nobody draws the task, and a remark or null. */
    record Deferral(Instant notBefore, String remark) implements TaskInput {

        public Deferral {
            Objects.requireNonNull(notBefore, "notBefore");
        }
    }

    /** {@code ask}: the question, its options, and whether free text is admitted. */
    record Question(String text, List<String> options, boolean freeText) implements TaskInput {

        public Question {
            requireText(text, "question");
            options = List.copyOf(options);
            if (options.isEmpty() && !freeText) {
                throw new IllegalArgumentException(
                    "a question with no option and no free text cannot be answered");
            }
        }

        /** The stored form: {@code question_options} of the task. */
        Map<String, Object> asOptions() {
            return Map.of("options", options, "free_text", freeText);
        }
    }

    /** {@code answer}: one option of the question, or free text; exactly one of the two. */
    record Reply(String option, String text) implements TaskInput {

        public Reply {
            if ((option == null) == (text == null)) {
                throw new IllegalArgumentException(
                    "an answer names an option or carries text, and exactly one of the two");
            }
        }

        /** What the answer row records. */
        String recorded() {
            return option != null ? option : text;
        }
    }

    /** {@code hold}: why the holder pauses -- a dependency or something external. */
    record Pause(HoldReason reason, String remark) implements TaskInput {

        public Pause {
            Objects.requireNonNull(reason, "reason");
            if (reason == HoldReason.QUESTION) {
                throw new IllegalArgumentException(
                    "a question pauses through ask, which carries it; hold takes dependency or "
                        + "external");
            }
        }
    }

    /** {@code deliver}: the answer text and the return metadata, in one act. */
    record Delivery(String text, Map<String, Object> metadata) implements TaskInput {

        public Delivery {
            requireText(text, "answer text");
        }
    }

    private static void requireText(String text, String what) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("the " + what + " is mandatory");
        }
    }
}
