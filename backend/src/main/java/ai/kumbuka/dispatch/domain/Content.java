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

import java.util.Locale;
import java.util.UUID;

/**
 * A text an act writes: a row of {@code dispatch.content} of the store line,
 * hung on its act. Insert-only, and never evaluated (DEC-0056/C15).
 *
 * <p>Mapped with the kinds the acts on an instance write -- the input text of
 * the start act (D19), and the return text of a closing act with the
 * translation basis of its outcome label -- and the two a send writes: the
 * text of the commission, and the text of each of its criteria, hung on the
 * criterion by its column. Each is plain text; supplements are the kind of
 * another act and are mapped by the change that writes them.
 */
@Entity
@Immutable
@Table(name = "content", schema = "dispatch")
public class Content extends RuntimeRow {

    /** The kinds of content the acts of this service write, as the store names them. */
    public enum Kind {
        INSTANCE_INPUT, INSTANCE_RETURN, INSTANCE_TRANSLATION_BASIS, COMMISSION, CRITERION;

        /** The kind as the store writes it. */
        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The media type of every text a call hands in. */
    static final String PLAIN = "text/plain";

    @Column(name = "act_id", nullable = false, updatable = false)
    private Long actId;

    @Column(name = "kind", nullable = false, updatable = false)
    private String kind;

    /** Absent but on the text of a criterion of a commission. */
    @Column(name = "commission_criterion_id", updatable = false)
    private @Nullable Long commissionCriterionId;

    @Column(name = "media_type", nullable = false, updatable = false)
    private String mediaType;

    @Column(name = "body", nullable = false, updatable = false)
    private String body;

    /** For the persistence provider only. */
    protected Content() {
        // The provider fills every column from the row it reads.
    }

    Content(UUID scopeId, long actId, Kind kind, String body) {
        super(scopeId);
        this.actId = actId;
        this.kind = kind.wireName();
        this.mediaType = PLAIN;
        this.body = body;
    }

    /** The text of a criterion of a commission, written by the act that sends it. */
    static Content criterion(UUID scopeId, long actId, long commissionCriterionId, String body) {
        Content text = new Content(scopeId, actId, Kind.CRITERION, body);
        text.commissionCriterionId = commissionCriterionId;
        return text;
    }
}
