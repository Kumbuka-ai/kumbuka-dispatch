/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.domain.TaskVerb;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every remedy of the catalogue, and the sentence of every step in {@code
 * next}, names the act the caller takes, worded positively (DEC-0042): a
 * model carries out an instruction of what to do more reliably than a
 * prohibition.
 *
 * <p>A test case and not yet a start-up guard: whether the guard of the
 * catalogue takes this check over is part of the decision on a central store
 * of messages per service, which is open.
 *
 * <p>The check is a list of negating words. It cannot tell an instruction from
 * a description, and it is not meant to: it catches the remedy that says what
 * not to do, which is the form the decision retires.
 *
 * <p>Red probe, observed: against the catalogue before the amendment of
 * 2026-10-13 this turns red on {@code CURSOR_MALFORMED} ("repeat the query
 * without after") and {@code CURSOR_FOREIGN} ("... or without after"), and on
 * the sentence of {@code claim_next} ("... without naming one").
 */
class RemedyIsAnInstructionTest {

    /** The negating words, as whole words, in any case. */
    private static final Pattern NEGATION = Pattern.compile(
        "\\b(not|no|never|without|don't|cannot)\\b", Pattern.CASE_INSENSITIVE);

    @Test
    void every_remedy_of_the_catalogue_is_worded_as_the_act_to_take() {
        Map<String, String> negated = new LinkedHashMap<>();
        for (ReasonCatalogue.Reason reason : ReasonCatalogue.declared()) {
            if (NEGATION.matcher(reason.remedy()).find()) {
                negated.put(reason.code().name(), reason.remedy());
            }
        }
        assertThat(negated).as("remedies that say what not to do").isEmpty();
    }

    /**
     * The sentence of every step a {@code next} list can carry: one per
     * transition, the first entry for the commissioner of a delivered task,
     * and the step of a draw that found nothing.
     *
     * <p>The first entry after a claim is left out, and only it: it is
     * concept section 3.5 word for word ("Do this first: the claim did not
     * return it."), which this service does not reword. Its instruction stands
     * first and is positive; the clause after it is the reason.
     */
    @Test
    void every_step_of_next_is_described_as_the_act_it_takes() {
        Map<String, String> sentences = new LinkedHashMap<>();
        for (TaskVerb verb : TaskVerb.values()) {
            sentences.put(verb.wireName(), verb.nextSentence());
        }
        sentences.put("read_text for the commissioner", NextList.READ_THE_ANSWER);
        @SuppressWarnings("unchecked")
        List<NextList.Step> drawn = (List<NextList.Step>) Refused.nothingToTake(
            "dispatch_claim_next", "dispatch://probe/sprint", "dispatch_query")
            .data().get(Refused.NEXT);
        drawn.forEach(step -> sentences.put(step.call() + " after an empty draw", step.does()));

        Map<String, String> negated = new LinkedHashMap<>();
        sentences.forEach((step, sentence) -> {
            if (NEGATION.matcher(sentence).find()) {
                negated.put(step, sentence);
            }
        });
        assertThat(negated).as("steps of next described by what they do not do").isEmpty();
    }
}
