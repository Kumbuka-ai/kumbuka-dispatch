/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * The first answer to a caller's idempotency key: a row of {@code
 * dispatch.idempotency_key} of the store line (C10.4, data model "Idempotency
 * keys", REA-0010 D30).
 *
 * <p>Mapped with what a {@code create} writes: the caller, the key, the task
 * the call created, the digest of the call's canonical arguments and the
 * instant it was first spent. A row is never changed. It hangs on the task and
 * not on its draft, so a repeat after the send still finds the task; the
 * delete of a draft empties {@code task_id} by the store's own key, and an
 * empty task means the draft the first call created is gone. A row older than
 * twenty-four hours counts as absent and is deleted when its caller next
 * writes a key.
 */
@Entity
@Immutable
@Table(name = "idempotency_key", schema = "dispatch")
public class IdempotencyRecord extends RuntimeRow {

    @Column(name = "caller_subject", nullable = false, updatable = false)
    private String callerSubject;

    @Column(name = "idempotency_key", nullable = false, updatable = false)
    private String idempotencyKey;

    /** Absent once the draft the first call created was deleted. */
    @Column(name = "task_id", updatable = false)
    private @Nullable Long taskId;

    /** Absent on a row written before the digest existed; this service writes it always. */
    @Column(name = "argument_digest", updatable = false)
    private @Nullable String argumentDigest;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** For the persistence provider only. */
    protected IdempotencyRecord() {
        // The provider fills every column from the row it reads.
    }

    IdempotencyRecord(UUID scopeId, String callerSubject, String key, long taskId,
                      String argumentDigest, Instant createdAt) {
        super(scopeId);
        this.callerSubject = callerSubject;
        this.idempotencyKey = key;
        this.taskId = taskId;
        this.argumentDigest = argumentDigest;
        this.createdAt = createdAt;
    }

    /** The task the first call created; empty once that draft was deleted. */
    public Optional<Long> taskId() {
        return Optional.ofNullable(taskId);
    }

    /** Whether the arguments of a repeat are the ones of the first call. */
    public boolean sameArguments(String digest) {
        return digest.equals(argumentDigest);
    }
}
