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
 * there only where it applies.
 */
public enum AnswerShape {

    /** The lean answer of a writing call. */
    TASK_LEAN(Members.LEAN),
    /** The lean answer at 201 with Location: create. */
    TASK_LEAN_CREATED(Members.LEAN),
    /** The lean answer with the claim's receipt. */
    CLAIM_RECEIPT(Members.with(Members.LEAN, "receipt")),
    /** The full head of a task; never its text. */
    TASK_HEAD(Members.LEAN),
    /** One page of heads, and the cursor of the next page where one follows. */
    TASK_LISTING(List.of("objects", "[next_cursor]")),
    /** One part of a task's text: the only answer that carries one. */
    TASK_TEXT(List.of(Members.ADDRESS, "part", "texts", "addenda")),
    /** The address a deleted draft had, and nothing else. */
    TASK_DELETED(List.of(Members.ADDRESS));

    /** The members several shapes share. */
    private static final class Members {

        static final String ADDRESS = "address";

        /** The lean answer: the task's address, its fields, its token, its next steps. */
        static final List<String> LEAN = List.of(ADDRESS, "fields", "conflict_token", "next",
            "[waiting_for]");

        static List<String> with(List<String> members, String more) {
            List<String> all = new java.util.ArrayList<>(members);
            all.add(more);
            return List.copyOf(all);
        }

        private Members() {
        }
    }

    private final List<String> members;

    AnswerShape(List<String> members) {
        this.members = List.copyOf(members);
    }

    /** The members of the answer at its top level. */
    public List<String> members() {
        return members;
    }
}
