/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.repository.ActRepository;
import ai.kumbuka.dispatch.repository.DefinitionRepository;
import ai.kumbuka.dispatch.repository.InstanceRepository;
import ai.kumbuka.dispatch.repository.TaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A repeated create whose key names a task the scope no longer holds, or a
 * draft since deleted.
 *
 * <p>The kernel refuses it as C10.4 has every repeat refused whose first result
 * is gone: with {@code IDEMPOTENCY_KEY_REUSED}, writes nothing, and names no
 * address it does not have (REA-0010 D30). The ledger keeps a key after its
 * draft is deleted with an empty task, which the store sets itself.
 */
class TaskReissueRefusalModuleTest {

    private static final UUID SCOPE = UUID.fromString("00000000-0000-0000-0000-0000000000dd");
    private static final Instant NOW = Instant.parse("2026-11-24T08:00:00.000001Z");
    private static final Actor CONSOLE = new Actor("ada", DispatchPermission.TASK_COMMISSION);
    private static final Provenance PROVENANCE = new Provenance("ada", null,
        DispatchPermission.TASK_COMMISSION, Provenance.Path.INTERFACE, null);

    private TaskRepository tasks;
    private InstanceRepository instances;
    private TaskService service;

    @BeforeEach
    void wire() {
        tasks = Mockito.mock(TaskRepository.class);
        instances = Mockito.mock(InstanceRepository.class);
        service = new TaskService(tasks, instances, Mockito.mock(DefinitionRepository.class),
            Mockito.mock(ActRepository.class), Mockito.mock(ActService.class),
            Mockito.mock(InstanceService.class), Clock.fixed(NOW, ZoneOffset.UTC));
        Instance instance = Instance.started(SCOPE, 1L, 4L, 21L, "a title");
        instance.id = 77L;
        when(instances.lockInstance(SCOPE, "sprint", 4L)).thenReturn(Optional.of(instance));
    }

    @Test
    void a_key_whose_task_is_gone_is_refused_as_reused_without_a_null_address() {
        TaskService.Create create = create();
        IdempotencyRecord spent = Mockito.mock(IdempotencyRecord.class);
        when(spent.sameArguments(TaskService.digest("probe", "sprint", create))).thenReturn(true);
        when(spent.taskId()).thenReturn(Optional.of(900L));
        when(tasks.spentKey(eq(SCOPE), eq("ada"), eq("again"), any()))
            .thenReturn(Optional.of(spent));
        when(tasks.task(SCOPE, 900L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(CONSOLE, "probe", SCOPE, "sprint", create,
                PROVENANCE))
            .isInstanceOfSatisfying(DispatchException.class, refused -> {
                assertThat(refused.reason())
                    .isEqualTo(DispatchException.Reason.IDEMPOTENCY_KEY_REUSED);
                assertThat(refused.task()).as("and names no task it does not have").isEmpty();
                assertThat(refused.offenders()).isEmpty();
            });
        verify(tasks, never()).insert(any());
    }

    /**
     * A repeated create whose key outlived the draft it created (the draft
     * was deleted, and the ledger keeps the key with no task) is refused and
     * creates nothing.
     */
    @Test
    void a_create_key_whose_draft_was_deleted_is_refused_as_reused() {
        TaskService.Create create = create();
        IdempotencyRecord spent = Mockito.mock(IdempotencyRecord.class);
        when(spent.sameArguments(anyString())).thenReturn(true);
        when(spent.taskId()).thenReturn(Optional.empty());
        when(tasks.spentKey(eq(SCOPE), eq("ada"), eq("again"), any()))
            .thenReturn(Optional.of(spent));

        assertThatThrownBy(() -> service.create(CONSOLE, "probe", SCOPE, "sprint", create,
                PROVENANCE))
            .isInstanceOfSatisfying(DispatchException.class, refused -> assertThat(
                refused.reason()).isEqualTo(DispatchException.Reason.IDEMPOTENCY_KEY_REUSED));
        verify(tasks, never()).insert(any());
        verify(tasks, never()).task(eq(SCOPE), anyLong());
        verify(tasks).spentKey(SCOPE, "ada", "again", NOW.minus(TaskService.KEY_LIFE));
    }

    @Test
    @Disabled("rebuilt in T5: a repeated claim whose key names a task the scope no longer holds "
        + "is refused as reused")
    void a_claim_key_whose_task_is_gone_is_refused_as_reused() {
        fail("rebuilt in T5");
    }

    private static TaskService.Create create() {
        return new TaskService.Create(new InstanceAddress("probe", "sprint", 4),
            new TaskService.Drafting("a title", null, null, null, List.of(), null, null), null,
            List.of(), IdempotencyKey.of("again"));
    }
}
