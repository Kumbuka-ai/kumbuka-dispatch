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

import java.util.UUID;

/**
 * A criterion of a draft by its identifier and place: a row of {@code
 * dispatch.draft_criterion} of the store line. Its text is a {@link
 * DraftText} of the kind criterion.
 *
 * <p>The service gives the identifiers, {@code c1}, {@code c2} and so on in the
 * order the call hands the criteria in. An update that changes the criteria
 * deletes those of the draft and writes the new ones; a row is never changed.
 */
@Entity
@Table(name = "draft_criterion", schema = "dispatch")
public class DraftCriterion extends RuntimeRow {

    @Column(name = "draft_id", nullable = false, updatable = false)
    private Long draftId;

    @Column(name = "identifier", nullable = false, updatable = false)
    private String identifier;

    @Column(name = "ordinal", nullable = false, updatable = false)
    private int ordinal;

    /** For the persistence provider only. */
    protected DraftCriterion() {
        // The provider fills every column from the row it reads.
    }

    DraftCriterion(UUID scopeId, long draftId, int ordinal) {
        super(scopeId);
        this.draftId = draftId;
        this.identifier = identifierAt(ordinal);
        this.ordinal = ordinal;
    }

    /** The identifier the service gives the criterion at a place, from 1. */
    static String identifierAt(int ordinal) {
        return "c" + ordinal;
    }

    public String identifier() {
        return identifier;
    }

    public int ordinal() {
        return ordinal;
    }
}
