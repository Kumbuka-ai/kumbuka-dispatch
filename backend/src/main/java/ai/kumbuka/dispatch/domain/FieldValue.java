/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * The value an act writes for one control field: a row of {@code
 * dispatch.field_value} of the store line, anchored to its act and never
 * changed (DEC-0056/C8). The type it names and, for a choice, the enumeration
 * are the field's, which the store holds by its keys. Insert-only.
 */
@Entity
@Immutable
@Table(name = "field_value", schema = "dispatch")
public class FieldValue extends RuntimeRow {

    @Column(name = "act_id", nullable = false, updatable = false)
    private Long actId;

    @Column(name = "control_field_id", nullable = false, updatable = false)
    private Long controlFieldId;

    @Column(name = "value_type", nullable = false, updatable = false)
    private String valueType;

    /** Absent on every type but choice. */
    @Column(name = "enumeration_id", updatable = false)
    private @Nullable Long enumerationId;

    @Embedded
    private TypedValue value;

    /** For the persistence provider only. */
    protected FieldValue() {
        // The provider fills every column from the row it reads.
    }

    FieldValue(UUID scopeId, long actId, ControlField field, TypedValue value) {
        super(scopeId);
        this.actId = actId;
        this.controlFieldId = field.id();
        this.valueType = field.type();
        this.enumerationId = field.enumerationId().orElse(null);
        this.value = value;
    }

    /** The control field the value is written for. */
    public Long controlFieldId() {
        return controlFieldId;
    }

    /** The value in its typed column. */
    public TypedValue value() {
        return value;
    }
}
