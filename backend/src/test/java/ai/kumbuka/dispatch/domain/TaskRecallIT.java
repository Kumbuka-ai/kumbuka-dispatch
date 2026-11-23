/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The recall of a sent task up to its first take-up, the instant that take-up
 * leaves, and the addendum that is added only while the work runs (TAR-0004
 * section 3, DEC-0014, REQ-0035), against a running database under the
 * service role.
 *
 * <p>Every refusal is held to its reason and to the words that carry state,
 * cause and way out; every task is staged through the kernel, except a lapse
 * and the traces of a take-up by an image that did not record its instant,
 * which no call of this kernel writes and which are written as the
 * administrator.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@Disabled(TaskRecallIT.REBUILT)
class TaskRecallIT {

    static final String REBUILT = "rebuilt in T12: a sent task is recalled to draft only up to its "
        + "first take-up and an addendum is added only while the work runs";

    /**
     * Stands for the tests of this class until their order rebuilds them:
     * <ul>
     *   <li>{@code an_open_task_never_taken_up_is_recalled_changed_and_sent_again}</li>
     *   <li>{@code recall_after_claim_and_release_is_refused}</li>
     *   <li>{@code recall_after_claim_and_defer_is_refused_before_and_after_its_instant}</li>
     *   <li>{@code recall_after_a_lapsed_lease_is_refused}</li>
     *   <li>{@code recall_of_a_root_with_a_task_under_it_is_refused_whatever_the_task_s_state}</li>
     *   <li>{@code a_child_never_taken_up_is_recalled_while_its_root_stays_open}</li>
     *   <li>{@code recall_of_a_task_that_is_not_open_is_refused_for_its_state}</li>
     *   <li>{@code recall_by_an_identity_without_the_commissioning_capacity_is_refused}</li>
     *   <li>{@code recall_under_a_stale_token_is_refused}</li>
     *   <li>{@code the_first_take_up_is_stored_at_the_first_claim_and_never_changed}</li>
     *   <li>{@code a_stock_task_without_the_instant_is_taken_up_where_its_traces_say_so}</li>
     *   <li>{@code annotate_is_refused_in_open_delivered_and_closed_for_every_part}</li>
     *   <li>{@code the_refusal_names_recall_on_an_open_task_and_rework_on_a_delivered_one}</li>
     *   <li>{@code annotate_is_accepted_while_the_work_runs_active_and_on_hold}</li>
     * </ul>
     */
    @Test
    void the_tests_of_this_class_are_rebuilt_in_their_order() {
        fail(REBUILT);
    }
}
