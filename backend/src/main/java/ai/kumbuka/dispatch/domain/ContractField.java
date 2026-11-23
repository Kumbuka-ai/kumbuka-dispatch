/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.tenancy.StringUuidConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;

/**
 * A control field a contract names: a row of {@code dispatch.contract_field}
 * of the store line (DEC-0056/C7). With an activity, a field of that
 * activity's result contract; without one, a field of the start contract of
 * the version.
 *
 * <p>Mapped read-only.
 */
@Entity
@Immutable
@Table(name = "contract_field", schema = "dispatch")
public class ContractField {

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    /** Read by the persistence provider alone, which filters every statement on it. */
    @TenantId
    @Convert(converter = StringUuidConverter.class)
    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "scope_id", nullable = false)
    private UUID scopeId;

    @Column(name = "definition_version_id", nullable = false)
    private Long definitionVersionId;

    /** Absent on a field of the start contract. */
    @Column(name = "activity_id")
    private @Nullable Long activityId;

    @Column(name = "control_field_id", nullable = false)
    private Long controlFieldId;

    @Column(name = "required", nullable = false)
    private boolean required;

    /** For the persistence provider only. */
    protected ContractField() {
        // The provider fills every column from the row it reads.
    }

    /** The activity whose result contract names the field; empty for the start contract. */
    public Optional<Long> activityId() {
        return Optional.ofNullable(activityId);
    }

    /** The control field the contract names. */
    public Long controlFieldId() {
        return controlFieldId;
    }

    /** Whether a delivery or a start must carry the field. */
    public boolean required() {
        return required;
    }
}
