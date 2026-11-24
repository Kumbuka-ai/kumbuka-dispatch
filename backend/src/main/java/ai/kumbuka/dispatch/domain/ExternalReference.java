/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.UUID;

/**
 * A reference of an instance to something outside this service: a row of
 * {@code dispatch.external_reference} of the store line, a value stored and
 * never resolved (D4, D12).
 *
 * <p>Set by an act and, where it ends, ended by an act: the act that ended it
 * stands in its column, which is set only while it is empty and never
 * cleared, so an ended reference stays with its act (D22). The trigger of an
 * instance is a reference of its own kind, set by the start and never ended.
 */
@Entity
@DynamicUpdate
@Table(name = "external_reference", schema = "dispatch")
public class ExternalReference extends RuntimeRow {

    /** The kinds of reference an instance carries, as the store names them. */
    public enum Kind {
        /** What started the instance (D4): set by the start, never ended. */
        TRIGGER,
        /** A worklist item the instance serves (D12). */
        ITEM,
        /** Any other object outside the service the instance serves (D12). */
        REFERENCE;

        /** The kind as the store writes it. */
        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    @Column(name = "instance_id", nullable = false, updatable = false)
    private Long instanceId;

    @Column(name = "kind", nullable = false, updatable = false)
    private String kind;

    @Column(name = "value", nullable = false, updatable = false)
    private String value;

    @Column(name = "set_by_act_id", nullable = false, updatable = false)
    private Long setByActId;

    /** Absent while the reference holds. */
    @Column(name = "ended_by_act_id")
    private @Nullable Long endedByActId;

    /** For the persistence provider only. */
    protected ExternalReference() {
        // The provider fills every column from the row it reads.
    }

    ExternalReference(UUID scopeId, long instanceId, Kind kind, String value, long setByActId) {
        super(scopeId);
        this.instanceId = instanceId;
        this.kind = kind.wireName();
        this.value = value;
        this.setByActId = setByActId;
    }

    /** Ends the reference by the act given; the act stays in its column. */
    void endBy(long actId) {
        this.endedByActId = actId;
    }
}
