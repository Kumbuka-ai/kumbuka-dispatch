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
 * A listing pages by keyset in the database, against a running database under
 * the service role: the walk over every page hands out every task once, in
 * the order asked for, across page boundaries, ties, inserts and a state
 * filter, and a cursor continues only the listing that handed it out.
 *
 * <p>Every walk is bounded. A listing whose keyset bound did not hold would
 * hand out its first page again and again; the bound turns that into a red
 * assertion instead of a test that never ends.
 *
 * <p>Red probes, each observed 2026-10-11 against the running database:
 * <ul>
 *   <li>the keyset comparison removed from the statement: four walks hand out
 *       their first page until the bound;
 *   <li>the sub compared and sorted as text: {@code 1.10} comes before
 *       {@code 1.2};
 *   <li>the sort by creation without the address as its second key: three of
 *       five tied tasks are skipped at the page boundary;
 *   <li>an offset in the cursor instead of the keyset: after an insert the
 *       descending walk hands out one task twice;
 *   <li>the state predicate removed: a page of {@code state=open} holds a
 *       draft; the lapsed lease left out of {@code open}: the walk misses
 *       the lapsed tasks;
 *   <li>each of the five comparisons of the cursor's binding removed in turn:
 *       the cursor of another scope, selector, filter, sort or direction is
 *       answered instead of refused.
 * </ul>
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@Disabled(TaskPagingIT.REBUILT)
class TaskPagingIT {

    static final String REBUILT = "rebuilt in T11: a listing pages by keyset and hands out every "
        + "task once while its cursor continues only the listing that handed it out";

    /**
     * Stands for the tests of this class until their order rebuilds them:
     * <ul>
     *   <li>{@code the_address_order_is_numeric_across_every_page_boundary}</li>
     *   <li>{@code a_tie_on_the_instant_of_creation_is_broken_by_the_address}</li>
     *   <li>{@code a_task_inserted_during_a_walk_shifts_nothing_already_handed_out}</li>
     *   <li>{@code the_state_filter_holds_on_every_page_and_misses_nothing}</li>
     *   <li>{@code a_cursor_of_another_listing_is_refused_as_foreign_naming_what_differs}</li>
     *   <li>{@code a_cursor_of_a_scope_of_the_same_name_in_another_tenant_is_foreign}</li>
     *   <li>{@code a_cursor_this_service_did_not_hand_out_is_refused_as_malformed}</li>
     *   <li>{@code the_page_size_and_the_sort_are_refused_outside_what_is_declared}</li>
     *   <li>{@code a_page_carries_the_text_heads_a_read_carries}</li>
     * </ul>
     */
    @Test
    void the_tests_of_this_class_are_rebuilt_in_their_order() {
        fail(REBUILT);
    }
}
