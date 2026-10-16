/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import org.jspecify.annotations.Nullable;

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
 * as a defect, not as a caller refusal: refusing a missing argument by name
 * before a payload is built is the verb surface's work, and no call of the
 * surface reaches these guards.
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

    /**
     * {@code release}, {@code withdraw}: a remark the caller may give.
     *
     * @param text the remark; absent where the caller gave none
     */
    record Remark(@Nullable String text) implements TaskInput {
    }

    /** {@code reject}, {@code fail}, {@code rework}: the remark is mandatory. */
    record RequiredRemark(String text) implements TaskInput {

        public RequiredRemark {
            requireText(text, "remark");
        }
    }

    /**
     * {@code defer}: the instant before which nobody draws the task, and a remark.
     *
     * @param remark the remark; absent where the caller gave none
     */
    record Deferral(Instant notBefore, @Nullable String remark) implements TaskInput {

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

    /**
     * {@code answer}: one option of the question, or free text; exactly one of the two.
     *
     * @param option the option chosen; absent where the answer is free text
     * @param text   the free text; absent where the answer is an option
     */
    record Reply(@Nullable String option, @Nullable String text) implements TaskInput {

        public Reply {
            if ((option == null) == (text == null)) {
                throw new IllegalArgumentException(
                    "an answer names an option or carries text, and exactly one of the two");
            }
        }

        /** What the answer row records. */
        String recorded() {
            return Objects.requireNonNull(option != null ? option : text,
                "an answer carries an option or text");
        }
    }

    /**
     * {@code hold}: why the holder pauses -- a dependency or something external.
     *
     * @param remark the remark; absent where the caller gave none
     */
    record Pause(HoldReason reason, @Nullable String remark) implements TaskInput {

        public Pause {
            Objects.requireNonNull(reason, "reason");
            if (reason == HoldReason.QUESTION) {
                throw new IllegalArgumentException(
                    "a question pauses through ask, which carries it; hold takes dependency or "
                        + "external");
            }
        }
    }

    /**
     * {@code deliver}: the answer text and the return metadata, in one act.
     *
     * @param metadata the return metadata; absent where the holder delivered none
     */
    record Delivery(String text, @Nullable Map<String, Object> metadata) implements TaskInput {

        public Delivery {
            requireText(text, "answer text");
        }
    }

    private static void requireText(@Nullable String text, String what) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("the " + what + " is mandatory");
        }
    }
}
