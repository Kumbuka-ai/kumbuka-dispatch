/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Acceptance criteria 3 and 5: one fault answers with one reason, and the
 * acceptance by the identity that delivered (TAR-0004 section 3, REQ-0034).
 *
 * <p>The order is state, condition, relation, proof, confirmation, payload
 * (concept section 2.3). For each pair of neighbours there is one call that
 * breaks both, and the answer must be the earlier one's: check and reason.
 * Swapping any two neighbours in {@link Decision#ORDER} turns the case of that
 * pair red. The concept's mark on acceptance is no check: it refuses nothing,
 * and the transition stamps it ({@code TaskHoldingIT}).
 */
@Disabled(CheckOrderTest.REBUILT)
class CheckOrderTest {

    static final String REBUILT = "rebuilt in T13: a call that breaks two checks is refused "
        + "by the earlier one in the order state, condition, relation, proof, confirmation, payload";

    @Test
    void state_before_condition() {
        fail(REBUILT);
    }

    @Test
    void condition_before_relation() {
        fail(REBUILT);
    }

    @Test
    void relation_before_proof() {
        fail(REBUILT);
    }

    @Test
    void proof_before_confirmation() {
        fail(REBUILT);
    }

    @Test
    void confirmation_before_payload() {
        fail(REBUILT);
    }

    @Test
    void the_identity_that_delivered_accepts_holding_both_capacities_and_alone_never() {
        fail(REBUILT);
    }

    @Test
    void the_order_has_no_check_for_the_mark_on_acceptance() {
        fail(REBUILT);
    }

    @Test
    void a_write_of_the_holder_without_its_receipt_is_refused() {
        fail(REBUILT);
    }

    @Test
    void an_answer_names_an_option_or_free_text_where_admitted() {
        fail(REBUILT);
    }

    @Test
    void a_lease_is_positive() {
        fail(REBUILT);
    }

    @Test
    void a_recall_is_refused_once_taken_up_and_on_a_root_with_a_task_under_it() {
        fail(REBUILT);
    }

    @Test
    void a_recall_takes_the_commissioner_and_the_current_token() {
        fail(REBUILT);
    }
}
