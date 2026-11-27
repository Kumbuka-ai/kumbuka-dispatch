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
 * The shape of a call's answer, by its name in the declaration, with the
 * members the answer carries at its top level and what its member {@code
 * fields} carries.
 *
 * <p>The members are this service's, as the records of {@code adapter.payload}
 * write them. A name a router does not know yet is the router's to take up
 * when it binds the declaration (ADR-0039).
 */
public enum AnswerShape {

    /**
     * A seeded definition version. Its {@code fields} carry the head of the
     * version, its schema, its enumerations, its activities, its start
     * contract and the enumerations of the platform's system fields. It
     * carries no address and no conflict token: a version is no object of the
     * address form, and no call writes one.
     */
    DEFINITION(List.of(Member.FIELDS), List.of(Member.SELECTOR, Member.VERSION, Member.STATE,
        "verdict_required", "outcome_required", "outcome_labels", "schema", "enumerations",
        "activities", "start_contract", "system_enumerations")),

    /**
     * An instance, as every act on it answers it (C6.1): its complete address,
     * its head under {@code fields}, its conflict token, and the calls open to
     * the caller under {@code next}. A closed instance has none open and is
     * finished, so it waits for nothing. The resolution stands only on a closed
     * instance, the title only where its start gave one.
     */
    INSTANCE(List.of(Member.ADDRESS, Member.FIELDS, Member.CONFLICT_TOKEN, Member.NEXT),
        List.of(Member.SELECTOR, Member.NUMBER, Member.VERSION, "title", Member.STATE,
            Member.RESOLUTION)),

    /**
     * A task, as {@code create}, {@code update} and {@code send} answer it
     * (C6.1): its complete address, its place and its state under {@code
     * fields}, its conflict token, the calls open to the caller under {@code
     * next}, and what it waits for under {@code waiting_for} where none is open
     * and it is unfinished. The resolution stands only on a closed task. No
     * writing call answers with a text.
     */
    TASK(List.of(Member.ADDRESS, Member.FIELDS, Member.CONFLICT_TOKEN, Member.NEXT,
        "waiting_for"),
        List.of(Member.SELECTOR, Member.NUMBER, "sub", Member.STATE, Member.RESOLUTION)),

    /** The address a deleted draft had, and nothing else. */
    ADDRESS(List.of(Member.ADDRESS), List.of());

    /** The names of the members several answers carry, each written once. */
    private static final class Member {

        static final String ADDRESS = "address";
        static final String FIELDS = "fields";
        static final String CONFLICT_TOKEN = "conflict_token";
        static final String NEXT = "next";
        static final String SELECTOR = "selector";
        static final String NUMBER = "number";
        static final String VERSION = "version";
        static final String STATE = "state";
        static final String RESOLUTION = "resolution";

        private Member() {
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

    /** What the member {@code fields} of the answer carries. */
    public List<String> fields() {
        return fields;
    }
}
