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

import java.time.Instant;
import java.util.UUID;

/**
 * A lease an act grants on a holding: a row of {@code dispatch.lease} of the
 * store line, insert-only. {@code claim} writes the first, {@code renew} each
 * further one, each ending at the time of its act plus the duration the call
 * named; the youngest lease of a holding is the one that holds (data model A.5
 * "Holding").
 */
@Entity
@Immutable
@Table(name = "lease", schema = "dispatch")
public class Lease extends RuntimeRow {

    @Column(name = "holding_id", nullable = false, updatable = false)
    private Long holdingId;

    @Column(name = "act_id", nullable = false, updatable = false)
    private Long actId;

    @Column(name = "expires_at", nullable = false, updatable = false)
    private Instant expiresAt;

    /** For the persistence provider only. */
    protected Lease() {
        // The provider fills every column from the row it reads.
    }

    Lease(UUID scopeId, long holdingId, long actId, Instant expiresAt) {
        super(scopeId);
        this.holdingId = holdingId;
        this.actId = actId;
        this.expiresAt = expiresAt;
    }

    public long holdingId() {
        return holdingId;
    }

    /** When the lease ends: at that instant it has lapsed. */
    public Instant expiresAt() {
        return expiresAt;
    }
}
