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

/**
 * A named list of values a definition version declares for its choice fields:
 * a row of {@code dispatch.enumeration} of the store line (DEC-0056/C4).
 *
 * <p>Mapped read-only; its values are {@link EnumerationValue} rows.
 */
@Entity
@Immutable
@Table(name = "enumeration", schema = "dispatch")
public class Enumeration extends DefinitionRow {

    @Column(name = "definition_version_id", nullable = false)
    private Long definitionVersionId;

    @Column(name = "name", nullable = false)
    private String name;

    public String name() {
        return name;
    }
}
