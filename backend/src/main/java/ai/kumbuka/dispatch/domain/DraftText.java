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
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * The text of the commission of a draft, or of one of its criteria: a row of
 * {@code dispatch.draft_text} of the store line.
 *
 * <p>An update that changes a text deletes the row and writes the new one; a
 * row is never changed. Every text a call hands in is plain text.
 */
@Entity
@Table(name = "draft_text", schema = "dispatch")
public class DraftText extends RuntimeRow {

    /** The kinds of text a draft carries, as the store names them. */
    public enum Kind {
        COMMISSION, CRITERION;

        /** The kind as the store writes it. */
        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    @Column(name = "draft_id", nullable = false, updatable = false)
    private Long draftId;

    @Column(name = "kind", nullable = false, updatable = false)
    private String kind;

    /** Absent but on the text of a criterion. */
    @Column(name = "draft_criterion_id", updatable = false)
    private @Nullable Long draftCriterionId;

    @Column(name = "media_type", nullable = false, updatable = false)
    private String mediaType;

    @Column(name = "body", nullable = false, updatable = false)
    private String body;

    /** For the persistence provider only. */
    protected DraftText() {
        // The provider fills every column from the row it reads.
    }

    private DraftText(UUID scopeId, long draftId, Kind kind, @Nullable Long draftCriterionId,
                      String body) {
        super(scopeId);
        this.draftId = draftId;
        this.kind = kind.wireName();
        this.draftCriterionId = draftCriterionId;
        this.mediaType = Content.PLAIN;
        this.body = body;
    }

    /** The text of the commission of a draft. */
    static DraftText commission(UUID scopeId, long draftId, String body) {
        return new DraftText(scopeId, draftId, Kind.COMMISSION, null, body);
    }

    /** The text of a criterion of a draft. */
    static DraftText criterion(UUID scopeId, long draftId, long draftCriterionId, String body) {
        return new DraftText(scopeId, draftId, Kind.CRITERION, draftCriterionId, body);
    }

    /** Whether this is the text of the commission rather than of a criterion. */
    public boolean ofTheCommission() {
        return Kind.COMMISSION.wireName().equals(kind);
    }

    /** The criterion whose text this is; empty on the text of the commission. */
    public Optional<Long> draftCriterionId() {
        return Optional.ofNullable(draftCriterionId);
    }

    public String body() {
        return body;
    }
}
