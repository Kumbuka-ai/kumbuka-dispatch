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
 * A selector of a scope: a row of {@code dispatch.selector} of the store line,
 * read so that a definition is found by the name a caller gives.
 *
 * <p>Mapped read-only and with the columns the read of a definition joins on:
 * no call writes a selector, a migration of the installation declares it. The
 * name is read by the queries of the repository and by nothing else.
 */
@Entity
@Immutable
@Table(name = "selector", schema = "dispatch")
public class Selector extends DefinitionRow {

    @Column(name = "name", nullable = false)
    private String name;
}
