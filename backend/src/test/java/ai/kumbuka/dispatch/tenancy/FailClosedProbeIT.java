/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.tenancy;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * Probe 3 — the lock closes, and the data is still there.
 *
 * <p>Both halves belong to the probe. A read with no tenant bound returns
 * nothing, which is the guarantee; and the same read with the tenant bound
 * returns the rows, which is what distinguishes a lock from an empty table.
 * Asserting only the first half would pass against a database where the
 * insert never landed, the schema is wrong, or the rows were deleted — every
 * one of which looks exactly like perfect isolation from the outside.
 *
 * <p>The second pair of tests takes the two enforcement layers apart. Both
 * are on at once in normal operation, so an ordinary assertion cannot say
 * which one did the work — and a layer that is quietly doing nothing is a
 * layer that will not be there when the other one is removed. Each is
 * therefore observed alone, by switching the other off for the length of one
 * assertion.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@Disabled(FailClosedProbeIT.REBUILT)
class FailClosedProbeIT {

    static final String REBUILT =
        "rebuilt in T4: a task read fails closed without a bound tenant and each layer isolates alone";

    /**
     * Stands for the tests of this class until their order rebuilds them:
     * <ul>
     *   <li>{@code an_unbound_read_returns_nothing_and_the_rows_are_still_there}</li>
     *   <li>{@code the_orm_filter_isolates_on_its_own_when_the_policy_is_off}</li>
     *   <li>{@code the_policy_isolates_raw_sql_that_the_orm_never_touched}</li>
     * </ul>
     */
    @Test
    void the_tests_of_this_class_are_rebuilt_in_their_order() {
        fail(REBUILT);
    }
}
