/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.domain.TaskVerb;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every remedy and every step of {@code next} the running service serves is
 * worded as the act the caller takes (DEC-0042, DEC-0043 R21).
 *
 * <p>The complement of {@code PositivePhrasingLintTest}, which reads the
 * bundle files: this reads what the bundles render, from the running
 * application, so a text that reached a caller by some other way than its
 * bundle file would be read here too. Same words, same rule, no exceptions.
 *
 * <p>Red probe, observed: against the catalogue before the amendment of
 * 2026-10-13 this turned red on {@code CURSOR_MALFORMED} ("repeat the query
 * without after") and {@code CURSOR_FOREIGN} ("... or without after"), and on
 * the sentence of {@code claim_next} ("... without naming one"); against the
 * branch before the bundles of 2026-10-15 it turned red on the first step
 * after a claim ("the claim did not return it").
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class RemedyIsAnInstructionTest {

    /** The words of DEC-0043 R21, whole and in any case. */
    private static final Pattern NEGATION = Pattern.compile(
        "\\b(not|never|no|none|nothing|nobody|neither|nor|cannot|without|avoid|unless|"
            + "refrain)\\b|n't\\b|\\binstead\\s+of\\b", Pattern.CASE_INSENSITIVE);

    @Test
    void every_remedy_of_the_catalogue_is_worded_as_the_act_to_take() {
        Map<String, String> negated = new LinkedHashMap<>();
        for (ReasonCatalogue.Reason reason : ReasonCatalogue.declared()) {
            if (NEGATION.matcher(reason.remedy()).find()) {
                negated.put(reason.code().name(), reason.remedy());
            }
        }
        assertThat(ReasonCatalogue.declared()).hasSize(RefusalCode.values().length);
        assertThat(negated).as("remedies that say what not to do").isEmpty();
    }

    /**
     * The sentence of every step a {@code next} list can carry: one per
     * transition, the first entry after a claim, the first entry for the
     * commissioner of a delivered task, and the step of a draw that found
     * nothing.
     */
    @Test
    void every_step_of_next_is_described_as_the_act_it_takes() {
        Map<String, String> sentences = new LinkedHashMap<>();
        for (TaskVerb verb : TaskVerb.values()) {
            sentences.put(verb.wireName(), NextList.does(verb));
        }
        sentences.put("read_text after a claim", Texts.next().readTheCommission());
        sentences.put("read_text for the commissioner", Texts.next().readTheAnswer());
        @SuppressWarnings("unchecked")
        List<Map<String, String>> drawn = (List<Map<String, String>>) Refused.nothingToTake(
            VerbName.of(ProcessVerb.CLAIM_NEXT, Surface.MCP),
            VerbName.of(ProcessVerb.QUERY, Surface.MCP))
            .data().get(Refused.NEXT);
        drawn.forEach(step -> sentences.put(step.get("call") + " after an empty draw",
            step.get("does")));

        Map<String, String> negated = new LinkedHashMap<>();
        sentences.forEach((step, sentence) -> {
            if (NEGATION.matcher(sentence).find()) {
                negated.put(step, sentence);
            }
        });
        assertThat(sentences).hasSize(TaskVerb.values().length + 3);
        assertThat(negated).as("steps of next described by what they do not do").isEmpty();
    }
}
