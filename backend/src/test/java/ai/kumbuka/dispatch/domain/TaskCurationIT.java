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
 * The rendering of {@code curated_in} in a head: the target's complete
 * address, and only where the caller may see the target's scope.
 *
 * <p>The rule of the running service, carried unchanged: the slug comes from
 * the subject-filtered directory view. The verb surface binds the caller's
 * subject in the transaction that calls the kernel; this test does the same,
 * on the tenant and subject the substrate publishes a directory for.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@Disabled(TaskCurationIT.REBUILT)
class TaskCurationIT {

    static final String REBUILT = "rebuilt in T11: a curation target is named by its complete "
        + "address only to a caller who sees its scope";

    @Test
    void a_curation_target_is_named_to_a_caller_who_sees_its_scope_and_to_no_other() {
        fail(REBUILT);
    }

    /**
     * A listing names the curation targets of a page as a read names each:
     * read for the page in one statement, under the same subject filter.
     */
    @Test
    void a_listing_names_curation_targets_as_a_read_does_and_to_no_other_caller() {
        fail(REBUILT);
    }
}
