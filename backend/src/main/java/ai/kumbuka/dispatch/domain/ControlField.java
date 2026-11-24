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
import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

/**
 * A control field the schema of a definition version declares, with its name
 * and its type: a row of {@code dispatch.control_field} of the store line
 * (DEC-0056/C2, C3).
 *
 * <p>Mapped read-only. A field of the type choice names the enumeration of its
 * version it takes its values from; every other type names none, which the
 * store holds by a check.
 */
@Entity
@Immutable
@Table(name = "control_field", schema = "dispatch")
public class ControlField extends DefinitionRow {

    @Column(name = "definition_version_id", nullable = false)
    private Long definitionVersionId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "type", nullable = false)
    private String type;

    /** Absent on every type but choice. */
    @Column(name = "enumeration_id")
    private @Nullable Long enumerationId;

    public String name() {
        return name;
    }

    /** The type of the platform's catalogue, as the store names it. */
    public String type() {
        return type;
    }

    /** The enumeration a choice field takes its values from; empty for every other type. */
    public Optional<Long> enumerationId() {
        return Optional.ofNullable(enumerationId);
    }
}
