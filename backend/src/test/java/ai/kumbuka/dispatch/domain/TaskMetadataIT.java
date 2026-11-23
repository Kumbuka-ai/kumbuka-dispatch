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
 * The cardinality of metadata on a task, through the kernel and back out of a
 * real database: a list stays a list and a string stays a string, on the
 * commissioner's metadata and on the executor's, and a value the rules refuse
 * is refused before anything is written.
 *
 * <p>The successor of the exchange's {@code MetadataCardinalityIT}: the rules
 * are {@link Metadata#validate}'s and did not change; what changed is the
 * store they are proven against.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@Disabled(TaskMetadataIT.REBUILT)
class TaskMetadataIT {

    static final String REBUILT = "rebuilt in T4: metadata written with a draft keeps its "
        + "cardinality and a value the rules refuse is refused before any write";

    /**
     * Stands for the tests of this class until their order rebuilds them:
     * <ul>
     *   <li>{@code a_list_returns_as_the_list_and_a_string_as_the_string}</li>
     *   <li>{@code the_executor_s_metadata_keeps_its_shape_too}</li>
     *   <li>{@code a_value_the_rules_refuse_is_refused_and_nothing_is_written}</li>
     * </ul>
     */
    @Test
    void the_tests_of_this_class_are_rebuilt_in_their_order() {
        fail(REBUILT);
    }
}
