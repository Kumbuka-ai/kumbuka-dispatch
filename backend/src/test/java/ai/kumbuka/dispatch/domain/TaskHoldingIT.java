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
 * Holding (TAR-0004 section 5, concept section 2.1) against a running database
 * under the service role: the lease, the lapse that writes nothing, the count
 * of lapses and the parking after the third, the receipt on every write of an
 * executor, the deferral, and the lock on acceptance.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@Disabled(TaskHoldingIT.REBUILT)
class TaskHoldingIT {

    static final String REBUILT = "rebuilt in T5: a holding runs under a lease fenced by its receipt, "
        + "lapses without a write, parks after the third lapse and waits out a deferral";

    @Test
    void the_third_lapse_parks_the_task_and_only_withdraw_takes_it_out() {
        fail(REBUILT);
    }

    @Test
    void the_second_lapse_still_opens_the_task_and_the_claim_after_it_counts_it() {
        fail(REBUILT);
    }

    @Test
    void a_lapse_writes_nothing_and_the_former_holder_is_refused_lease_lapsed() {
        fail(REBUILT);
    }

    @Test
    void a_claim_without_a_duration_holds_thirty_minutes_and_a_stated_one_holds_as_stated() {
        fail(REBUILT);
    }

    @Test
    void renew_extends_and_an_answer_restarts_thirty_minutes_whatever_the_claim_said() {
        fail(REBUILT);
    }

    @Test
    void a_write_of_the_holder_without_its_receipt_is_refused_and_writes_nothing() {
        fail(REBUILT);
    }

    @Test
    void a_deferred_task_is_not_claimed_before_its_instant_and_is_after_it() {
        fail(REBUILT);
    }

    @Test
    void the_identity_that_delivered_accepts_holding_both_capacities_and_the_row_is_marked() {
        fail(REBUILT);
    }

    @Test
    void an_acceptance_by_another_commissioner_leaves_the_row_unmarked() {
        fail(REBUILT);
    }

    @Test
    void rework_sends_the_answer_back_and_the_second_delivery_is_the_valid_one() {
        fail(REBUILT);
    }
}
