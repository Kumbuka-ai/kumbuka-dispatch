/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

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
@Disabled(TaskReissueRefusalModuleTest.REBUILT)
class TaskReissueRefusalModuleTest {

    static final String REBUILT = "rebuilt in T4: a repeat under a key whose first result is gone "
        + "is refused as reused and writes nothing";

    @Test
    void a_key_whose_task_is_gone_is_refused_as_reused_without_a_null_address() {
        fail(REBUILT);
    }

    /**
     * A repeated create whose key outlived the draft it created (the draft
     * was deleted, and the ledger keeps the key with no task) is refused and
     * creates nothing.
     */
    @Test
    void a_create_key_whose_draft_was_deleted_is_refused_as_reused() {
        fail(REBUILT);
    }
}
