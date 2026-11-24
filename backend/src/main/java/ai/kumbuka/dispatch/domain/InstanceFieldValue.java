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

import java.util.UUID;

/**
 * The value of a control field that holds for an instance: a row of {@code
 * dispatch.instance_field_value} of the store line, one per instance and field
 * (D18).
 *
 * <p>Written by {@code start} in its transaction, with the field value the
 * start act wrote and a copy of its typed value; a value written by the start
 * is in force without an acceptance, so the accepting act stays empty. The
 * acceptance of a delivery that replaces the row is a later order's; in this
 * change the row is only inserted.
 */
@Entity
@Immutable
@Table(name = "instance_field_value", schema = "dispatch")
public class InstanceFieldValue extends RuntimeRow {

    @Column(name = "instance_id", nullable = false, updatable = false)
    private Long instanceId;

    @Column(name = "control_field_id", nullable = false, updatable = false)
    private Long controlFieldId;

    @Column(name = "field_value_id", nullable = false, updatable = false)
    private Long fieldValueId;

    @Embedded
    private TypedValue value;

    /** For the persistence provider only. */
    protected InstanceFieldValue() {
        // The provider fills every column from the row it reads.
    }

    /** The value a start wrote, in force for its instance. */
    InstanceFieldValue(UUID scopeId, long instanceId, FieldValue written) {
        super(scopeId);
        this.instanceId = instanceId;
        this.controlFieldId = written.controlFieldId();
        this.fieldValueId = written.id();
        this.value = written.value();
    }
}
