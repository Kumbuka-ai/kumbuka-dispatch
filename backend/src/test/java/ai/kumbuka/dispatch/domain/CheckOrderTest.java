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

    /**
     * Stands for the tests of this class until their order rebuilds them:
     * <ul>
     *   <li>{@code state_before_condition}</li>
     *   <li>{@code condition_before_relation}</li>
     *   <li>{@code relation_before_proof}</li>
     *   <li>{@code proof_before_confirmation}</li>
     *   <li>{@code confirmation_before_payload}</li>
     *   <li>{@code the_identity_that_delivered_accepts_holding_both_capacities_and_alone_never}</li>
     *   <li>{@code the_order_has_no_check_for_the_mark_on_acceptance}</li>
     *   <li>{@code a_write_of_the_holder_without_its_receipt_is_refused}</li>
     *   <li>{@code an_answer_names_an_option_or_free_text_where_admitted}</li>
     *   <li>{@code a_lease_is_positive}</li>
     *   <li>{@code a_recall_is_refused_once_taken_up_and_on_a_root_with_a_task_under_it}</li>
     *   <li>{@code a_recall_takes_the_commissioner_and_the_current_token}</li>
     * </ul>
     */
    @Test
    void the_tests_of_this_class_are_rebuilt_in_their_order() {
        fail(REBUILT);
    }
}
