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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The document of free fields an act attaches: a row of {@code
 * dispatch.free_fields} of the store line. The send act carries the free
 * fields of the commission, copied from the draft. Insert-only, stored and
 * returned, and never evaluated (DEC-0056/C15).
 */
@Entity
@Immutable
@Table(name = "free_fields", schema = "dispatch")
public class FreeFields extends RuntimeRow {

    @Column(name = "act_id", nullable = false, updatable = false)
    private Long actId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "document", nullable = false, updatable = false)
    private Map<String, Object> document;

    /** For the persistence provider only. */
    protected FreeFields() {
        // The provider fills every column from the row it reads.
    }

    FreeFields(UUID scopeId, long actId, Map<String, Object> document) {
        super(scopeId);
        this.actId = actId;
        this.document = new LinkedHashMap<>(document);
    }
}
