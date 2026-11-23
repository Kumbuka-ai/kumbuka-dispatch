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
 * One numbered version of the process a selector declares: a row of {@code
 * dispatch.definition_version} of the store line.
 *
 * <p>Mapped read-only: a version enters the store as a seed, by the copy of
 * the cut-over or by a migration of the installation, and no call writes one.
 * The seed mark is mapped because only a seeded version is read.
 */
@Entity
@Immutable
@Table(name = "definition_version", schema = "dispatch")
public class DefinitionVersion {

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

    @Column(name = "selector_id", nullable = false)
    private Long selectorId;

    @Column(name = "version", nullable = false)
    private int version;

    @Column(name = "verdict_required", nullable = false)
    private boolean verdictRequired;

    @Column(name = "outcome_required", nullable = false)
    private boolean outcomeRequired;

    @Column(name = "state", nullable = false)
    private String state;

    @Column(name = "seed", nullable = false)
    private boolean seed;

    /** For the persistence provider only. */
    protected DefinitionVersion() {
        // The provider fills every column from the row it reads.
    }

    public Long id() {
        return id;
    }

    /** The number of the version within its selector, from 1. */
    public int version() {
        return version;
    }

    /** Whether a return of an instance of this version needs a verdict. */
    public boolean verdictRequired() {
        return verdictRequired;
    }

    /** Whether a return of an instance of this version needs an outcome label. */
    public boolean outcomeRequired() {
        return outcomeRequired;
    }

    /** The state as the store names it: draft, active or retired. */
    public String state() {
        return state;
    }
}
