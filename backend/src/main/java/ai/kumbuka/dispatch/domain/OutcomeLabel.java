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
 * A label an outcome may carry: a row of {@code dispatch.outcome_label} of the
 * store line, owned either by one activity or by a whole definition version.
 *
 * <p>Mapped read-only. Exactly one of the two owners is set, which the store
 * holds by a check.
 */
@Entity
@Immutable
@Table(name = "outcome_label", schema = "dispatch")
public class OutcomeLabel extends DefinitionRow {

    /** Absent on a label of a definition version. */
    @Column(name = "activity_id")
    private @Nullable Long activityId;

    /** Absent on a label of an activity. */
    @Column(name = "definition_version_id")
    private @Nullable Long definitionVersionId;

    @Column(name = "label", nullable = false)
    private String label;

    @Column(name = "ordinal", nullable = false)
    private int ordinal;

    /** The activity that owns the label; empty for a label of the version. */
    public Optional<Long> activityId() {
        return Optional.ofNullable(activityId);
    }

    public String label() {
        return label;
    }
}
