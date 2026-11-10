/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.repository.TaskRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A repeated claim whose key names a task the scope no longer holds.
 *
 * <p>No path of the database reaches this for a claim: only a draft is
 * deleted, and a draft is never claimed. The kernel still refuses it as C10.4
 * has every repeat refused whose first result is gone: with {@code
 * IDEMPOTENCY_KEY_REUSED}, and without naming an address it does not have.
 * Rewritten from {@code NOT_FOUND} with the contract round of 2026-10-29,
 * which settled the code for this case.
 */
class TaskReissueRefusalModuleTest {

    private static final UUID SCOPE = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final Instant NOW = Instant.parse("2026-10-07T09:00:00Z");
    private static final ExchangeAddress ADDRESS = new ExchangeAddress("sprint", 7, 0);

    @Test
    void a_key_whose_task_is_gone_is_refused_as_reused_without_a_null_address() {
        TaskService kernel = new TaskService(Clock.fixed(NOW, ZoneOffset.UTC));
        kernel.tasks = mock(TaskRepository.class);
        SpentTaskKey spent = new SpentTaskKey();
        spent.taskId = 42L;
        spent.callName = TaskService.CLAIM;
        spent.argumentDigest = IdempotencyService.digestOf(
            Arrays.asList(ADDRESS.toString(), "default"));
        spent.firstSeenAt = NOW.minusSeconds(60);
        when(kernel.tasks.lockKey(any(), anyString(), anyString())).thenReturn(Optional.of(spent));
        when(kernel.tasks.lockById(any(), anyLong())).thenReturn(Optional.empty());

        DispatchException refused = catchThrowableOfType(DispatchException.class, () ->
            kernel.claim(SCOPE, ADDRESS, TaskCall.by(TaskStage.H), IdempotencyKey.of("gone")));

        assertThat(refused.reason())
            .as("C10.4: the object the key produced no longer exists")
            .isEqualTo(DispatchException.Reason.IDEMPOTENCY_KEY_REUSED);
        assertThat(refused.getMessage()).doesNotContain("null");
    }

    /**
     * A repeated create whose key outlived the draft it created (the draft
     * was deleted, and the ledger keeps the key with no task) is refused and
     * creates nothing.
     */
    @Test
    void a_create_key_whose_draft_was_deleted_is_refused_as_reused() {
        TaskService kernel = new TaskService(Clock.fixed(NOW, ZoneOffset.UTC));
        kernel.tasks = mock(TaskRepository.class);
        kernel.selectors = mock(SelectorRegistry.class);
        TaskService.Draft draft = new TaskService.Draft("t", "code", null, null);
        SpentTaskKey spent = new SpentTaskKey();
        spent.taskId = null;
        spent.callName = TaskService.CREATE;
        spent.argumentDigest = IdempotencyService.digestOf(Arrays.asList("sprint", null,
            draft.title(), draft.apparatus(), draft.text(), String.valueOf(draft.metadata())));
        spent.firstSeenAt = NOW.minusSeconds(60);
        when(kernel.tasks.lockKey(any(), anyString(), anyString())).thenReturn(Optional.of(spent));

        DispatchException refused = catchThrowableOfType(DispatchException.class, () ->
            kernel.create(SCOPE, "sprint", null, draft, TaskStage.C, IdempotencyKey.of("gone")));

        assertThat(refused)
            .as("RED STATE, observed: the ledger lost the key with the draft, and the repeat "
                + "created a second draft")
            .isNotNull();
        assertThat(refused.reason()).isEqualTo(DispatchException.Reason.IDEMPOTENCY_KEY_REUSED);
        org.mockito.Mockito.verify(kernel.tasks, org.mockito.Mockito.never())
            .insert(any(Task.class));
    }
}
