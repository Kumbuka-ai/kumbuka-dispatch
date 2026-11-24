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
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.TenantId;

import java.util.UUID;

/**
 * The columns every row of the definition carries: its surrogate, its tenant
 * and its scope. The head of the read-only entities of a definition version --
 * selector, version, activity, outcome label, enumeration, enumeration value,
 * control field and contract field -- which a read of a definition maps and no
 * call writes.
 *
 * <p>The tenant is read by the persistence provider alone, which filters every
 * statement on it; the scope by the queries of the repository, which name it
 * beside the tenant in every statement.
 */
@MappedSuperclass
public abstract class DefinitionRow {

    @Id
    @Column(name = "id", nullable = false)
    private Long id;

    @TenantId
    @Convert(converter = StringUuidConverter.class)
    @Column(name = "tenant_id", nullable = false)
    private String tenantId;

    @Column(name = "scope_id", nullable = false)
    private UUID scopeId;

    /** For the persistence provider only. */
    protected DefinitionRow() {
        // The provider fills every column from the row it reads.
    }

    /** The row's surrogate. */
    public Long id() {
        return id;
    }
}
