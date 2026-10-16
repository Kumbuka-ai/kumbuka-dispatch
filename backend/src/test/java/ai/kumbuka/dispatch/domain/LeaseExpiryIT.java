/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import ai.kumbuka.dispatch.tenancy.TenantContext;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static ai.kumbuka.dispatch.domain.TaskStage.C;
import static ai.kumbuka.dispatch.domain.TaskStage.H;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A lease expires lazily, on read; nothing writes an expiry back (C11.2).
 *
 * <p>A lease is let run out as the clock would, with a write of its end into
 * the past. Every read after that answers the task as open again, and the row
 * stays exactly as the claim left it: still {@code active}, still holding its
 * holder, its lease end and its conflict token. The lapse becomes a write only
 * when a call that takes the task up decides on it.
 *
 * <p>RED STATE, observed with a read planted to write the lapse back: the row
 * no longer held its holder after the read, and its token had moved.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class LeaseExpiryIT {

    static final UUID SCOPE = UUID.fromString("00000000-0000-0000-0000-000000000010");
    static final String SELECTOR = "sprint";

    @Inject TaskService tasks;
    @Inject TenantContext tenantContext;

    private AutoCloseable binding;
    private TaskStage stage;

    @BeforeEach
    void freshTenant() {
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
    void a_lapsed_lease_is_read_as_open_and_nothing_is_written_back() {
        TaskStage.Staged lapsed = stage.lapse(stage.active(SELECTOR));
        TaskStage.Row before = TaskStage.row(lapsed.identity());
        String tokenBefore = stage.token(lapsed);

        TaskView read = tasks.read(SCOPE, lapsed.address(), C);
        Page listed = tasks.query(SCOPE, SELECTOR, TaskFilter.of(java.util.Map.of()),
            TaskStage.page(10), C);

        assertThat(read.state()).as("read lazily as open").isEqualTo(TaskState.OPEN);
        assertThat(listed.tasks()).filteredOn(v -> v.address().equals(lapsed.address()))
            .singleElement().extracting(TaskView::state).isEqualTo(TaskState.OPEN);
        assertThat(TaskStage.row(lapsed.identity()))
            .as("the row is as the claim left it: no expiry was written back")
            .isEqualTo(before);
        assertThat(before.state()).isEqualTo("active");
        assertThat(before.holder()).isEqualTo(H.subject());
        assertThat(stage.token(lapsed)).as("nothing was written, so the token stands")
            .isEqualTo(tokenBefore);
    }

    @Test
    void the_lapse_becomes_a_write_only_when_a_take_up_decides_on_it() {
        TaskStage.Staged lapsed = stage.lapse(stage.active(SELECTOR));

        TaskClaim drawn = tasks.claimNext(SCOPE, SELECTOR, List.of("code"),
            TaskCall.by(TaskStage.K));

        assertThat(drawn.task().address()).isEqualTo(lapsed.address());
        TaskStage.Row row = TaskStage.row(lapsed.identity());
        assertThat(row.holder()).isEqualTo(TaskStage.K.subject());
        assertThat(row.lapseCount()).as("the lapse is counted where it is decided on")
            .isEqualTo(1);
        assertThat(Set.of(row.state())).containsExactly("active");
    }
}
