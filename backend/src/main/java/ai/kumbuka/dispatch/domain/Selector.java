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
 * A selector of a scope: a row of {@code dispatch.selector} of the store line,
 * read so that a definition is found by the name a caller gives.
 *
 * <p>Mapped read-only and with the columns the read of a definition joins on:
 * no call writes a selector, a migration of the installation declares it. The
 * columns are read by the queries of the repository and by nothing else.
 */
@Entity
@Immutable
@Table(name = "selector", schema = "dispatch")
public class Selector {

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

    @Column(name = "name", nullable = false)
    private String name;

    /** For the persistence provider only. */
    protected Selector() {
        // The provider fills every column from the row it reads.
    }
}
