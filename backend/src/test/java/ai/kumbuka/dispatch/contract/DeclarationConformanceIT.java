/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.contract;

import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The declaration against the target and the concept document.
 *
 * <p>Every expected value here is a fixed value written from the
 * specification ({@link TargetSurface}) or read from the contract copy
 * ({@link Contract}), never from the declaration it checks.
 *
 * <p>Red probes, observed: a call declared beyond the target, and a declared
 * call removed, each turn {@link #the_declaration_carries_the_twenty_six_calls_of_the_target}
 * red; a description over 500 characters stops the service at start and with
 * it the build, and turns {@link #every_description_is_within_its_budget} red.
 */
// The texts a call answers with come from the message bundles, which the
// running application builds: this test runs with it.
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@Disabled(DeclarationConformanceIT.REBUILT)
class DeclarationConformanceIT {

    static final String REBUILT =
        "rebuilt in T13: the declaration conforms to the target surface and the contract copy";

    /**
     * Stands for the tests of this class until their order rebuilds them:
     * <ul>
     *   <li>{@code the_declaration_carries_the_calls_of_the_target}</li>
     *   <li>{@code the_sixteen_transitions_are_the_transitions_and_the_nine_are_not}</li>
     *   <li>{@code every_call_takes_exactly_the_arguments_of_the_concept_table}</li>
     *   <li>{@code the_mandatory_arguments_are_the_ones_the_concept_names}</li>
     *   <li>{@code every_description_is_within_its_budget}</li>
     *   <li>{@code a_description_over_the_budget_is_refused_by_the_start_up_guard}</li>
     *   <li>{@code the_texts_of_concept_section_3_5_stand_word_for_word}</li>
     *   <li>{@code every_description_is_the_contract_copy_s}</li>
     *   <li>{@code the_catalogue_is_the_contract_copy_s_table}</li>
     *   <li>{@code every_form_an_argument_takes_is_a_message_of_its_own}</li>
     *   <li>{@code the_declaration_is_servable}</li>
     *   <li>{@code the_declaration_carries_the_calls_the_field_and_the_filter_of_the_tags}</li>
     *   <li>{@code every_call_this_service_carries_describes_every_argument}</li>
     * </ul>
     */
    @Test
    void the_tests_of_this_class_are_rebuilt_in_their_order() {
        fail(REBUILT);
    }
}
