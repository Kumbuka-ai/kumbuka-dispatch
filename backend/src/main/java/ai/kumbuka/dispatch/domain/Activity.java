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
 * An activity a definition version declares: a row of {@code dispatch.activity}
 * of the store line.
 *
 * <p>Mapped read-only and with the columns the read of a definition answers:
 * what a task of the activity requires at delivery, the kind of input it takes
 * and whether its executor may ask. The bound activity, the exclusion at start
 * and the handbook address are not read yet and stay unmapped.
 */
@Entity
@Immutable
@Table(name = "activity", schema = "dispatch")
public class Activity {

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

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "verdict_required", nullable = false)
    private boolean verdictRequired;

    @Column(name = "outcome_required", nullable = false)
    private boolean outcomeRequired;

    @Column(name = "criteria_required", nullable = false)
    private boolean criteriaRequired;

    @Column(name = "input_kind", nullable = false)
    private String inputKind;

    @Column(name = "may_ask", nullable = false)
    private boolean mayAsk;

    /** For the persistence provider only. */
    protected Activity() {
        // The provider fills every column from the row it reads.
    }

    public Long id() {
        return id;
    }

    public String name() {
        return name;
    }

    /** Whether a delivery of a task of this activity needs a verdict. */
    public boolean verdictRequired() {
        return verdictRequired;
    }

    /** Whether a delivery of a task of this activity needs an outcome label. */
    public boolean outcomeRequired() {
        return outcomeRequired;
    }

    /** Whether a delivery reports a status for every criterion of its commission. */
    public boolean criteriaRequired() {
        return criteriaRequired;
    }

    /** The input a task of this activity takes, as the store names it. */
    public String inputKind() {
        return inputKind;
    }

    /** Whether the executor of a task of this activity may ask. */
    public boolean mayAsk() {
        return mayAsk;
    }
}
