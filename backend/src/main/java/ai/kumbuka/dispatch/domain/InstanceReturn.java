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

import java.util.UUID;

/**
 * The return a closing act gives for a whole instance: a row of {@code
 * dispatch.instance_return} of the store line, hung on the {@code conclude}
 * or {@code withdraw} act that wrote it, with its verdict and its outcome
 * label where given. Its text and the translation basis of its label are
 * content of that act. Insert-only.
 */
@Entity
@Immutable
@Table(name = "instance_return", schema = "dispatch")
public class InstanceReturn extends RuntimeRow {

    @Column(name = "act_id", nullable = false, updatable = false)
    private Long actId;

    /** Absent where the return gives none. */
    @Column(name = "verdict", updatable = false)
    private @Nullable String verdict;

    /** Absent where the return gives none. */
    @Column(name = "outcome_label_id", updatable = false)
    private @Nullable Long outcomeLabelId;

    /** For the persistence provider only. */
    protected InstanceReturn() {
        // The provider fills every column from the row it reads.
    }

    InstanceReturn(UUID scopeId, long actId, @Nullable String verdict,
                   @Nullable Long outcomeLabelId) {
        super(scopeId);
        this.actId = actId;
        this.verdict = verdict;
        this.outcomeLabelId = outcomeLabelId;
    }
}
