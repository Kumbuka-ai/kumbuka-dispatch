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
 * Acceptance criterion 2, against a running database under the service role:
 * over every reachable situation, every call the kernel lists as open to a
 * caller succeeds, and every call it does not list is refused for the state
 * or for the caller's entitlement.
 *
 * <p>Each attempt runs on a fresh task staged into the situation through the
 * kernel, so one attempt never decides on what another wrote. Each presents
 * what a well-formed request presents -- H's receipt, the current conflict
 * token, a valid payload -- so a refusal that is about the request rather
 * than the situation shows up as a failure here.
 *
 * <p>One unlisted call succeeds: closing a task with the outcome it already
 * closed with, which writes nothing (the running service's rule, carried).
 * It is admitted only where it wrote nothing -- the conflict token did not
 * move -- and only on the closed task.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@Disabled(TaskLifecycleIT.REBUILT)
class TaskLifecycleIT {

    static final String REBUILT = "rebuilt in T13: every call the next list names succeeds and "
        + "every other is refused for the state or the caller's entitlement";

    @Test
    void every_listed_call_succeeds_and_every_other_is_refused_for_state_or_entitlement() {
        fail(REBUILT);
    }
}
