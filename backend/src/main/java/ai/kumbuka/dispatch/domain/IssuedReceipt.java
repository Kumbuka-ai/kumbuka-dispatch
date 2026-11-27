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
 * A receipt handed out to the holder of a holding: a row of {@code
 * dispatch.receipt} of the store line, insert-only. It holds the SHA-256 of
 * the receipt, hex-encoded, as {@link Receipt#hash} makes it and the copy of
 * the old stock carries it, and never the receipt itself (C10.5).
 *
 * <p>{@code claim} writes the first; a repeated {@code claim} under its key
 * writes another on the same holding, and the youngest is the one a holder's
 * call must present (data model A.5 "Holding", TAR-0004 2.8).
 */
@Entity
@Immutable
@Table(name = "receipt", schema = "dispatch")
public class IssuedReceipt extends RuntimeRow {

    @Column(name = "holding_id", nullable = false, updatable = false)
    private Long holdingId;

    @Column(name = "receipt_hash", nullable = false, updatable = false)
    private String receiptHash;

    @Column(name = "issued_at", nullable = false, updatable = false)
    private Instant issuedAt;

    /** For the persistence provider only. */
    protected IssuedReceipt() {
        // The provider fills every column from the row it reads.
    }

    /** The stored form of a receipt handed out now; the receipt itself is not kept. */
    IssuedReceipt(UUID scopeId, long holdingId, String receipt, Instant issuedAt) {
        super(scopeId);
        this.holdingId = holdingId;
        this.receiptHash = Receipt.hash(receipt);
        this.issuedAt = issuedAt;
    }

    public long holdingId() {
        return holdingId;
    }

    /** Whether a presented receipt is the one this row was issued for. */
    public boolean matches(String presented) {
        return Receipt.matches(presented, receiptHash);
    }
}
