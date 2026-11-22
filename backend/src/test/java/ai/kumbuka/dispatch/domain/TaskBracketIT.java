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
 * Closing a bracket root with unfinished children (TAR-0004 section 3):
 * refused first with every unfinished child named and a confirmation handed
 * out; the same call with it withdraws the children and closes the root in one
 * transaction; a confirmation is void once the set of unfinished children
 * changed.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@Disabled(TaskBracketIT.REBUILT)
class TaskBracketIT {

    static final String REBUILT = "rebuilt in T3: withdrawing a root with unfinished children takes "
        + "a confirmation and then withdraws them in one transaction";

    @Test
    void closing_a_root_with_unfinished_children_needs_a_confirmation_and_then_withdraws_them() {
        fail(REBUILT);
    }

    @Test
    void a_confirmation_is_void_once_the_set_of_unfinished_children_changed() {
        fail(REBUILT);
    }

    @Test
    void a_confirmation_is_bound_to_the_verb_it_was_handed_out_for() {
        fail(REBUILT);
    }

    @Test
    void a_root_whose_children_are_all_closed_closes_without_a_confirmation() {
        fail(REBUILT);
    }

    @Test
    void a_closed_root_takes_no_new_child() {
        fail(REBUILT);
    }
}
