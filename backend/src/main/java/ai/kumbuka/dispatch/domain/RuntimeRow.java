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
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * The columns every row a call writes into the runtime of an instance
 * carries: its surrogate, drawn by the store at the insert, its tenant, set by
 * the persistence provider from the bound tenant, and its scope. The head of
 * the entities the acts on an instance write -- its return, its references,
 * its field values and their projection, and its content.
 *
 * <p>The tenant and the scope are written once, at the insert; neither is
 * ever changed, and the mapping says so. Every query of the repository names
 * the scope beside the tenant the discriminator adds.
 */
@MappedSuperclass
public abstract class RuntimeRow {

    /** Absent until the row is written: the store draws it at the insert. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false, insertable = false)
    private @Nullable Long id;

    /** Absent until the row is written: the persistence provider sets the bound tenant. */
    @TenantId
    @Convert(converter = StringUuidConverter.class)
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private @Nullable String tenantId;

    @Column(name = "scope_id", nullable = false, updatable = false)
    private UUID scopeId;

    /** For the persistence provider only. */
    protected RuntimeRow() {
        // The provider fills every column from the row it reads.
    }

    /** A new row of the scope given, under the bound tenant. */
    protected RuntimeRow(UUID scopeId) {
        this.scopeId = scopeId;
    }

    /** The row's surrogate, drawn by the store at the insert. */
    public Long id() {
        return Objects.requireNonNull(id, "a row has an id once it is written");
    }

    public UUID scopeId() {
        return scopeId;
    }
}
