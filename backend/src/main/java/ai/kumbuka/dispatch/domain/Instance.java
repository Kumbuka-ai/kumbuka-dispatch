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
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.TenantId;

import java.util.UUID;

/**
 * One run of a declared process: a row of {@code dispatch.instance} of the
 * store line.
 *
 * <p>Mapped with what the writing of an act needs and nothing more: the row
 * is the one an act is written to, and the row its writer locks so that the
 * sequence of the instance's acts is handed out one at a time. Its number,
 * its selector, its version and the projection of its state are the store
 * line's, mapped where a call reads or writes them.
 */
@Entity
@Table(name = "instance", schema = "dispatch")
public class Instance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false, insertable = false)
    public Long id;

    @TenantId
    @Convert(converter = StringUuidConverter.class)
    @Column(name = "tenant_id", nullable = false)
    public String tenantId;

    @Column(name = "scope_id", nullable = false, updatable = false)
    public UUID scopeId;
}
