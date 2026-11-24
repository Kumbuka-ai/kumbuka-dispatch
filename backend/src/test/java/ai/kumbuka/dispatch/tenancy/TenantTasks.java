/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.tenancy;

import ai.kumbuka.dispatch.domain.Actor;
import ai.kumbuka.dispatch.domain.DefinitionFixture;
import ai.kumbuka.dispatch.domain.DispatchPermission;
import ai.kumbuka.dispatch.domain.IdempotencyKey;
import ai.kumbuka.dispatch.domain.Provenance;
import ai.kumbuka.dispatch.domain.TaskService;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the tenancy probes of the calls on a task share: a selector planted
 * under a tenant and a scope of a probe's own, with one activity and a start
 * contract that requires nothing, and the arguments of a create.
 *
 * <p>Planted by SQL as the container's superuser, so the rows land under the
 * tenant named; every row a probe then counts is written through the kernel,
 * under the tenant the probe binds.
 */
final class TenantTasks {

    /** The selector every probe plants. */
    static final String SELECTOR = "sprint";

    /** A caller of the kernel: the probes are about tenancy, not about permissions. */
    static final Actor ACTOR = new Actor("tenancy-probe", DispatchPermission.TASK_COMMISSION,
        DispatchPermission.INSTANCE_START);

    /** Where the calls of a probe come from. */
    static final Provenance PROVENANCE = new Provenance("tenancy-probe", null,
        DispatchPermission.TASK_COMMISSION, Provenance.Path.INTERFACE, null);

    private TenantTasks() {
    }

    /** A selector with an active seeded version of one activity, under a tenant in a scope. */
    static void plant(UUID tenant, UUID scope) {
        DefinitionFixture plant = new DefinitionFixture(tenant, scope);
        long version = plant.version(plant.selector(SELECTOR), 1, "active", true, false, false);
        plant.activity(version, "review", false, false, false, "text", false);
    }

    /**
     * A create of the short form, with every part of a draft: a criterion, free
     * fields, a text, and a key where one is given.
     */
    static TaskService.Create create(String title, @Nullable String key) {
        return new TaskService.Create(null, new TaskService.Drafting(title, "code", null, null,
            List.of("a criterion"), Map.of("k", "v"), "a text"), "a trigger", List.of(),
            IdempotencyKey.of(key));
    }
}
