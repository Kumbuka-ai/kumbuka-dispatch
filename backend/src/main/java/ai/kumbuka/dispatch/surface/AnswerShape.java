/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import java.util.List;

/**
 * The shape of a call's answer, by the name the router gives it and with the
 * members the answer carries at its top level.
 *
 * <p>The names are the router's for this scheme ({@code VerbCatalog}, enum
 * {@code ResponseShape}, read 2026-10-15). The members are this service's,
 * as the records of {@code adapter.payload} write them; a member in brackets is
 * there only where it applies. The fields are what the member {@code fields}
 * of a task carries, as {@code TaskView.fields} writes them, in the same
 * bracket notation; a shape that carries no task has none.
 */
public enum AnswerShape {

    /** The lean answer of a writing call. */
    TASK_LEAN(Members.LEAN, Fields.LEAN),
    /** The lean answer at 201 with Location: create. */
    TASK_LEAN_CREATED(Members.LEAN, Fields.LEAN),
    /** The lean answer with the claim's receipt. */
    CLAIM_RECEIPT(Members.with(Members.LEAN, "receipt"), Fields.LEAN),
    /** The full head of a task; never its text. */
    TASK_HEAD(Members.LEAN, Fields.HEAD),
    /** One page of heads, and the cursor of the next page where one follows. */
    TASK_LISTING(List.of("objects", "[next_cursor]"), Fields.HEAD),
    /** One part of a task's text: the only answer that carries one. */
    TASK_TEXT(List.of(Members.ADDRESS, "part", "texts", "addenda"), List.of()),
    /** The address a deleted draft had, and nothing else. */
    TASK_DELETED(List.of(Members.ADDRESS), List.of());

    /** The fields of a task an answer carries. */
    static final class Fields {

        /** The tokens of the tags a task carries. */
        static final String TAGS = "tags";

        /** The state and its attributes, and for the holder the end of the lease. */
        static final List<String> LEAN = List.of("state", "[hold_reason]", "[outcome]",
            "[self_accepted]", "[not_before]", "[lease_expires_at]");

        /** The full head: the lean fields and what a read adds, the tags last. */
        static final List<String> HEAD = Members.with(Members.with(LEAN, List.of("title",
            "apparatus", "identity", "holder", "lapse_count", "[question]",
            "[dispatch_metadata]", "[return_metadata]", "[curated_in]", "texts")), List.of(TAGS));

        private Fields() {
        }
    }

    /** The members several shapes share. */
    private static final class Members {

        static final String ADDRESS = "address";

        /** The lean answer: the task's address, its fields, its token, its next steps. */
        static final List<String> LEAN = List.of(ADDRESS, "fields", "conflict_token", "next",
            "[waiting_for]");

        static List<String> with(List<String> members, String more) {
            return with(members, List.of(more));
        }

        static List<String> with(List<String> members, List<String> more) {
            List<String> all = new java.util.ArrayList<>(members);
            all.addAll(more);
            return List.copyOf(all);
        }

        private Members() {
        }
    }

    private final List<String> members;
    private final List<String> fields;

    AnswerShape(List<String> members, List<String> fields) {
        this.members = List.copyOf(members);
        this.fields = List.copyOf(fields);
    }

    /** The members of the answer at its top level. */
    public List<String> members() {
        return members;
    }

    /** The fields of the task the answer carries under {@code fields}; empty where none. */
    public List<String> fields() {
        return fields;
    }

    /** The lean fields, with the tags a writing call on the tags answers besides. */
    static List<String> leanWithTags() {
        return Members.with(Fields.LEAN, Fields.TAGS);
    }
}
