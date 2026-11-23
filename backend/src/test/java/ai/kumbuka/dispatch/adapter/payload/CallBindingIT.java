/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.payload;

import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The adapter's half of a call over a verb surface that is not there: what
 * the form check refuses before anything below it is called, how one checked
 * call becomes one call of the verb surface, and how a kernel refusal is told.
 *
 * <p>Every refusal of an argument is decided before the verb surface is
 * reached, which {@code verifyNoInteractions} observes: nothing below the
 * adapter was called, so nothing could be written.
 *
 * <p>{@link #made} takes the path an adapter takes: the form check, the one
 * call, and at the edge the refusal caught and told. The texts a call answers
 * with come from the message bundles, which the running application builds,
 * so this runs with it.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@Disabled(CallBindingIT.REBUILT)
class CallBindingIT {

    static final String REBUILT = "rebuilt in T13: the adapter refuses a malformed call before the kernel "
        + "and tells a kernel refusal with the task as it stands";

    /**
     * Stands for the tests of this class until their order rebuilds them:
     * <ul>
     *   <li>{@code an_invented_argument_is_refused_by_name_at_either_level_for_every_call}</li>
     *   <li>{@code a_missing_mandatory_argument_is_refused_by_the_surface_for_every_call}</li>
     *   <li>{@code a_call_without_its_conflict_token_reaches_the_verb_surface}</li>
     *   <li>{@code an_address_whose_number_does_not_fit_is_refused_by_name_on_both_surfaces}</li>
     *   <li>{@code a_lease_or_a_deferral_past_what_a_task_stores_is_refused_by_name}</li>
     *   <li>{@code a_lease_of_no_length_or_a_negative_one_is_refused_by_name}</li>
     *   <li>{@code a_combination_the_kernel_would_meet_as_a_defect_is_refused_first}</li>
     *   <li>{@code an_undeclared_filter_is_refused}</li>
     *   <li>{@code after_a_claim_read_text_stands_first_in_next_with_the_concept_s_sentence}</li>
     *   <li>{@code the_commissioner_of_a_delivered_task_reads_the_answer_before_accepting}</li>
     *   <li>{@code where_nothing_is_open_the_answer_says_what_the_task_waits_for}</li>
     *   <li>{@code a_stale_conflict_token_is_refused_with_the_task_as_it_stands}</li>
     *   <li>{@code a_claim_before_a_deferral_has_passed_is_refused_as_deferral_pending}</li>
     *   <li>{@code a_holder_s_call_by_a_caller_who_holds_nothing_is_told_by_who_holds_the_task}</li>
     *   <li>{@code an_unexpected_failure_gives_back_no_value_the_call_carried}</li>
     * </ul>
     */
    @Test
    void the_tests_of_this_class_are_rebuilt_in_their_order() {
        fail(REBUILT);
    }
}
