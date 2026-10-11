/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.platform.PlatformFixture;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import ai.kumbuka.dispatch.tenancy.TenantContext;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static ai.kumbuka.dispatch.domain.TaskStage.C;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The state filter of a listing and the kernel's effective state give the
 * same answer for every row shape, against a running database.
 *
 * <p>The listing states the effective state as a predicate in its statement,
 * so the keyset bound and the page size cut what the filter admits; the
 * kernel computes it in {@link TaskSituation#of}. Two statements of one table
 * drift apart silently unless something compares them, and this is that
 * comparison: every situation the transition matrix stages, and the lapse
 * counts on either side of the one that parks, are filtered by each of the
 * six states, and each task must be listed under exactly the state a read of
 * it reports.
 *
 * <p>The expectation is the kernel's read, never the listing: a read answers
 * the effective state through {@code TaskSituation}, which is the definition
 * the predicate copies.
 *
 * <p>Red probes, observed 2026-10-11: with the lapsed lease left out of the
 * predicate for {@code open}, the lapsed tasks are listed under no state; with
 * the parking bound moved by one, a parked task and one lapsed after two
 * recorded lapses are listed as {@code open}; with the predicate removed,
 * every task is listed under {@code draft}.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class EffectiveStateFilterIT {

    static final UUID SCOPE = UUID.fromString("00000000-0000-0000-0000-000000000010");
    static final String SELECTOR = "sprint";

    @Inject TaskService tasks;
    @Inject TenantContext tenantContext;

    private AutoCloseable binding;
    private TaskStage stage;

    @BeforeEach
    void freshTenant() {
        PlatformFixture.grantDirectoryAccess();
        UUID tenant = UUID.randomUUID();
        DomainFixture.declareSelector(tenant, SCOPE, SELECTOR);
        binding = tenantContext.bind(tenant);
        stage = new TaskStage(tasks, SCOPE);
    }

    @AfterEach
    void unbind() throws Exception {
        binding.close();
    }

    @Test
    void every_task_is_listed_under_exactly_the_state_a_read_reports() {
        Map<String, TaskStage.Staged> shapes = new LinkedHashMap<>();
        for (String situation : TaskStage.SITUATIONS) {
            shapes.put(situation, stage.stage(situation, SELECTOR));
        }
        // A lapsed lease on either side of the lapse that parks, and an active
        // row with no lease end at all, which the kernel reads as lapsed.
        for (int recorded = 0; recorded <= 3; recorded++) {
            TaskStage.Staged lapsed = stage.lapse(stage.active(SELECTOR));
            forceLapses(lapsed, recorded);
            shapes.put("lapsed after " + recorded + " recorded", lapsed);
        }
        TaskStage.Staged leaseless = stage.active(SELECTOR);
        PlatformFixture.run("UPDATE dispatch.task SET lease_expires_at = NULL "
            + "WHERE uuid = '" + leaseless.identity() + "'");
        shapes.put("active without a lease end", leaseless);

        Map<TaskState, List<String>> byRead = new LinkedHashMap<>();
        for (TaskState state : TaskState.values()) {
            byRead.put(state, new ArrayList<>());
        }
        shapes.values().forEach(s -> byRead.get(tasks.read(SCOPE, s.address(), C).state())
            .add(s.address().toString()));
        assertThat(byRead.values().stream().filter(l -> !l.isEmpty()))
            .as("the shapes cover every effective state: %s", byRead)
            .hasSize(TaskState.values().length);

        for (TaskState state : TaskState.values()) {
            List<String> listed = tasks.query(SCOPE, SELECTOR,
                    TaskFilter.of(Map.of("state", state.wireName())), TaskStage.page(200), C)
                .tasks().stream().map(t -> t.address().toString()).toList();
            assertThat(listed).as("listed as %s, against the kernel's read: %s", state, shapes)
                .containsExactlyInAnyOrderElementsOf(byRead.get(state));
        }
    }

    /** Sets the lapses a row has recorded, as the administrator; writes nothing else. */
    private static void forceLapses(TaskStage.Staged s, int recorded) {
        PlatformFixture.run("UPDATE dispatch.task SET lapse_count = " + recorded
            + " WHERE uuid = '" + s.identity() + "'");
    }
}
