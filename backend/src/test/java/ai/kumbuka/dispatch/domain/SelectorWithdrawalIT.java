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
 * Withdrawing a selector, in the kernel and under the service role: a status
 * and never a deletion, only while nothing is numbered under the name, and
 * afterwards nothing new is.
 *
 * <p>No call of either surface withdraws a selector; this is the kernel's
 * method and the database's grant, measured together.
 *
 * <p>The two overlapping cases meet a withdrawal and a root's creation under
 * one selector ({@link Overlap}). Red probes, observed before the withdrawal
 * took the selector's lock: a withdrawal that overlapped a creation answered
 * success and left a withdrawn selector with a task under it; a creation that
 * overlapped a withdrawal numbered a root under the withdrawn selector.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@Disabled(SelectorWithdrawalIT.REBUILT)
class SelectorWithdrawalIT {

    static final String REBUILT = "rebuilt in T3: a selector is withdrawn only while nothing is "
        + "numbered under it and nothing new is created under it afterwards";

    /**
     * Stands for the tests of this class until their order rebuilds them:
     * <ul>
     *   <li>{@code a_selector_without_a_task_is_withdrawn_as_a_status}</li>
     *   <li>{@code a_selector_with_a_task_under_it_is_not_withdrawn}</li>
     *   <li>{@code nothing_is_created_under_a_withdrawn_selector}</li>
     *   <li>{@code a_selector_a_root_is_being_created_under_is_not_withdrawn}</li>
     *   <li>{@code no_root_is_created_under_a_selector_being_withdrawn}</li>
     * </ul>
     */
    @Test
    void the_tests_of_this_class_are_rebuilt_in_their_order() {
        fail(REBUILT);
    }
}
