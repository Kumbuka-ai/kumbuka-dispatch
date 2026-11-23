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
 * Acceptance criterion 7: under the service role and a bound tenant, the task
 * kernel neither sees nor writes a task of another tenant.
 *
 * <p>Runs against the substrate database, whose runtime connection is the
 * service role with its enumerated grants and no BYPASSRLS. The two tenants
 * share the scope id and the selector name, so every address of one tenant
 * also exists in the other: nothing but the tenant keeps them apart.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@Disabled(TaskTenancyIT.REBUILT)
class TaskTenancyIT {

    static final String REBUILT = "rebuilt in T13: under a bound tenant the kernel neither sees "
        + "nor writes a task of another tenant at the same address";

    @Test
    void a_bound_tenant_neither_sees_nor_writes_another_tenants_task() {
        fail(REBUILT);
    }
}
