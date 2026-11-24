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

import java.util.Locale;
import java.util.UUID;

/**
 * A text an act writes: a row of {@code dispatch.content} of the store line,
 * hung on its act. Insert-only, and never evaluated (DEC-0056/C15).
 *
 * <p>Mapped with the kinds the acts on an instance write: the input text of
 * the start act (D19), and the return text of a closing act with the
 * translation basis of its outcome label. Each is plain text; supplements and
 * criteria are the kinds of other acts and are mapped by the change that
 * writes them.
 */
@Entity
@Immutable
@Table(name = "content", schema = "dispatch")
public class Content extends RuntimeRow {

    /** The kinds of content the acts on an instance write, as the store names them. */
    public enum Kind {
        INSTANCE_INPUT, INSTANCE_RETURN, INSTANCE_TRANSLATION_BASIS;

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
}
