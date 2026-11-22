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
 * The planner walks the index of a listing's order and sorts nothing: asked
 * about the statement the service issues for a page, as the service role,
 * with the tenant bound, on a selector of several thousand tasks among others
 * of another selector and another tenant.
 *
 * <p>The statement is the one Hibernate prepares for the page, recorded by
 * {@link RecordedStatements} and handed to {@code EXPLAIN ANALYZE} unchanged,
 * so the plan belongs to the statement that runs and not to one written to
 * resemble it. The values it is explained with are not all the page's: the
 * tenant, the scope, the selector and the page size are, but the keyset values
 * of a continued page are constants written here, and the instants of creation
 * are seeded at random. That is deliberate and enough, because the finding is
 * about which index the planner walks and whether it sorts: that turns on the
 * shape of the statement and on the indexes, and a keyset walk over this table
 * plans alike wherever in the keyset it starts.
 *
 * <p>Red probes, each observed 2026-10-12 against the running database:
 * <ul>
 *   <li>the selector named through a join on its name, as the page did before:
 *       with either index in place the planner sorts all 5000 tasks of the
 *       selector for a page of fifty;
 *   <li>V19's index, which ends on {@code id}, in place of V20's: the planner
 *       walks it up to {@code created_at} and sorts again past it, an
 *       incremental sort over the tasks of each instant. Kept as a test of its
 *       own below.
 * </ul>
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@Disabled(TaskListingPlanIT.REBUILT)
class TaskListingPlanIT {

    static final String REBUILT = "rebuilt in T11: the page statement of a listing walks the index "
        + "of its order and sorts nothing";

    @Test
    void the_creation_order_walks_its_index_and_sorts_nothing() {
        fail(REBUILT);
    }

    @Test
    void a_continued_descending_creation_order_walks_its_index_backward() {
        fail(REBUILT);
    }

    @Test
    void the_address_order_walks_the_address_key_and_sorts_nothing() {
        fail(REBUILT);
    }

    @Test
    void with_the_index_that_ends_on_id_the_creation_order_is_sorted() {
        fail(REBUILT);
    }
}
