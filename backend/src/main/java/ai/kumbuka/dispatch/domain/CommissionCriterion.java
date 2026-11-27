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

import java.util.UUID;

/**
 * A criterion of a commission by its identifier and place: a row of {@code
 * dispatch.commission_criterion} of the store line, copied at the send from a
 * criterion of the draft with the identifier the draft gave it. Its text is
 * content of the kind criterion. Insert-only.
 */
@Entity
@Immutable
@Table(name = "commission_criterion", schema = "dispatch")
public class CommissionCriterion extends RuntimeRow {

    @Column(name = "commission_id", nullable = false, updatable = false)
    private Long commissionId;

    @Column(name = "identifier", nullable = false, updatable = false)
    private String identifier;

    @Column(name = "ordinal", nullable = false, updatable = false)
    private int ordinal;

    /** For the persistence provider only. */
    protected CommissionCriterion() {
        // The provider fills every column from the row it reads.
    }

    CommissionCriterion(UUID scopeId, long commissionId, DraftCriterion drafted) {
        super(scopeId);
        this.commissionId = commissionId;
        this.identifier = drafted.identifier();
        this.ordinal = drafted.ordinal();
    }
}
