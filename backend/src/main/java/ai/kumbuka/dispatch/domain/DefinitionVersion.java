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
public class DefinitionVersion extends DefinitionRow {

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
