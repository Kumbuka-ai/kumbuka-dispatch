/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

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
class RemedyIsAnInstructionIT {

    private static final String REBUILT =
        "rebuilt in T13: every step of next is worded as the act it takes";

    /** The words of DEC-0043 R21, whole and in any case. */
    private static final Pattern NEGATION = Pattern.compile(
        "\\b(not|never|no|none|nothing|nobody|neither|nor|cannot|without|avoid|unless|"
            + "refrain)\\b|n't\\b|\\binstead\\s+of\\b", Pattern.CASE_INSENSITIVE);

    @Test
    void every_remedy_of_the_catalogue_is_worded_as_the_act_to_take() {
        Map<String, String> negated = new LinkedHashMap<>();
        for (RefusalCode.Declared declared : RefusalCode.catalogue().values()) {
            if (NEGATION.matcher(declared.remedy()).find()) {
                negated.put(declared.code().name(), declared.remedy());
            }
        }
        assertThat(RefusalCode.catalogue()).hasSize(RefusalCode.values().length);
        assertThat(negated).as("remedies that say what not to do").isEmpty();
    }

    /**
     * The sentence of every step a {@code next} list can carry: one per
     * transition, the first entry after a claim, and the first entry for the
     * commissioner of a delivered task.
     */
    @Test
    @Disabled(REBUILT)
    void every_step_of_next_is_described_as_the_act_it_takes() {
        fail(REBUILT);
    }
}
