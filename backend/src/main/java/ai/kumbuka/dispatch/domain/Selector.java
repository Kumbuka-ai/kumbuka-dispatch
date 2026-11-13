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

import java.time.Instant;
import java.util.UUID;

/**
 * A bracket name, declared before use.
 *
 * <p>Never created by first use and never by a verb. A typo must not silently
 * open a namespace, and a selector later carries things a string cannot — an
 * aspect, a script, configuration that steers an agent. It can never be
 * renamed either, because every address ever issued depends on it; withdrawal
 * is a status, and only a never-used selector may be withdrawn.
 *
 * <p><strong>The identifier is internal.</strong> The row's {@code id} is a
 * BIGINT allocated by the database; it never leaves the service. The name is
 * what the address grammar carries, and the name is what other tables in this
 * schema resolve against by joining on the {@code id}.
 */
@Entity
@Table(name = "selector", schema = "dispatch")
public class Selector {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false, insertable = false)
    public Long id;

    @TenantId
    @Convert(converter = StringUuidConverter.class)
    @Column(name = "tenant_id", nullable = false)
    public String tenantId;

    @Column(name = "scope_id", nullable = false)
    public UUID scopeId;

    @Column(name = "name", nullable = false, updatable = false)
    public String name;

    @Column(name = "withdrawn", nullable = false)
    public Boolean withdrawn = Boolean.FALSE;

    /**
     * The next unused bracket number for this selector.
     *
     * <p>Advanced under the row lock the numbering path acquires on this
     * entity, so two concurrent creations serialise rather than collide, and
     * a rolled-back creation gives its number back. The column carries a
     * NOT NULL default at the table so an inserted selector adopts a legal
     * starting value without the caller stating one.
     */
    @Column(name = "next_number", nullable = false)
    public Integer nextNumber;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    public Instant createdAt;
}
