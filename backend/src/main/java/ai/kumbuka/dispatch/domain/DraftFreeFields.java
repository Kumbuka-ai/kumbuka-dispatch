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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The document of free fields a draft will carry: a row of {@code
 * dispatch.draft_free_fields} of the store line, at most one per draft.
 *
 * <p>Stored and returned, and never evaluated: the keys and values are the
 * caller's (DEC-0056/C15). An update that changes the free fields deletes the
 * row and writes the new one; a row is never changed.
 */
@Entity
@Table(name = "draft_free_fields", schema = "dispatch")
public class DraftFreeFields extends RuntimeRow {

    @Column(name = "draft_id", nullable = false, updatable = false)
    private Long draftId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "document", nullable = false, updatable = false)
    private Map<String, Object> document;

    /** For the persistence provider only. */
    protected DraftFreeFields() {
        // The provider fills every column from the row it reads.
    }

    DraftFreeFields(UUID scopeId, long draftId, Map<String, Object> document) {
        super(scopeId);
        this.draftId = draftId;
        this.document = new LinkedHashMap<>(document);
    }

    /** The document as the store holds it. */
    public Map<String, Object> document() {
        return Collections.unmodifiableMap(document);
    }
}
