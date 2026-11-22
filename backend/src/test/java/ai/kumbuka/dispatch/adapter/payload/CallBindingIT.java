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

    @Test
    void an_invented_argument_is_refused_by_name_at_either_level_for_every_call() {
        fail(REBUILT);
    }

    @Test
    void a_missing_mandatory_argument_is_refused_by_the_surface_for_every_call() {
        fail(REBUILT);
    }

    /**
     * The conflict token is the verb surface's to handle (C1.1): a call
     * without one reaches it, after the scope has resolved, and the kernel
     * refuses it as {@code CONFLICT_TOKEN_MISSING} where the caller may see the
     * task. Here the verb surface is a mock, so what is observed is that the
     * call reached it with no token.
     */
    @Test
    void a_call_without_its_conflict_token_reaches_the_verb_surface() {
        fail(REBUILT);
    }

    @Test
    void an_address_whose_number_does_not_fit_is_refused_by_name_on_both_surfaces() {
        fail(REBUILT);
    }

    @Test
    void a_lease_or_a_deferral_past_what_a_task_stores_is_refused_by_name() {
        fail(REBUILT);
    }

    /**
     * A lease of no length or of a negative one is a form fault the adapter
     * refuses before the scope is resolved, by the argument that carried it.
     */
    @Test
    void a_lease_of_no_length_or_a_negative_one_is_refused_by_name() {
        fail(REBUILT);
    }

    @Test
    void a_combination_the_kernel_would_meet_as_a_defect_is_refused_first() {
        fail(REBUILT);
    }

    @Test
    void an_undeclared_filter_is_refused() {
        fail(REBUILT);
    }

    @Test
    void after_a_claim_read_text_stands_first_in_next_with_the_concept_s_sentence() {
        fail(REBUILT);
    }

    @Test
    void the_commissioner_of_a_delivered_task_reads_the_answer_before_accepting() {
        fail(REBUILT);
    }

    @Test
    void where_nothing_is_open_the_answer_says_what_the_task_waits_for() {
        fail(REBUILT);
    }

    /**
     * A stale token is told with the task as it stands (DEC-0041, C8.7): its
     * address, its fields, its current token and the service that holds it.
     */
    @Test
    void a_stale_conflict_token_is_refused_with_the_task_as_it_stands() {
        fail(REBUILT);
    }

    @Test
    void a_claim_before_a_deferral_has_passed_is_refused_as_deferral_pending() {
        fail(REBUILT);
    }

    /**
     * The holder's call by a caller who holds nothing is told by who holds the
     * task: another executor ({@code CLAIM_HELD}), or nobody, because nobody
     * does or the caller's own lease ended ({@code CLAIM_ABSENT}); each 409
     * and each with the attempted call, the state and the calls open, and
     * nothing the task waits for (C8.6, C8.7).
     */
    @Test
    void a_holder_s_call_by_a_caller_who_holds_nothing_is_told_by_who_holds_the_task() {
        fail(REBUILT);
    }

    /**
     * The one code no caller can raise on the wire, held to DEC-0042 here: a
     * kernel that fails with the caller's value in its own message, under a
     * call that carries it, answers with neither. The wire half is {@code
     * UnexpectedFailureIT}.
     */
    @Test
    void an_unexpected_failure_gives_back_no_value_the_call_carried() {
        fail(REBUILT);
    }
}
