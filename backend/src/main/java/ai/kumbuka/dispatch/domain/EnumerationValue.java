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

import java.util.UUID;

/**
 * One value of an {@link Enumeration}, by its text and its place in the list:
 * a row of {@code dispatch.enumeration_value} of the store line.
 *
 * <p>Mapped read-only.
 */
@Entity
@Immutable
@Table(name = "enumeration_value", schema = "dispatch")
public class EnumerationValue {

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

    @Column(name = "enumeration_id", nullable = false)
    private Long enumerationId;

    @Column(name = "value", nullable = false)
    private String value;

    @Column(name = "ordinal", nullable = false)
    private int ordinal;

    /** For the persistence provider only. */
    protected EnumerationValue() {
        // The provider fills every column from the row it reads.
    }

    /** The enumeration the value belongs to. */
    public Long enumerationId() {
        return enumerationId;
    }

    public String value() {
        return value;
    }
}
