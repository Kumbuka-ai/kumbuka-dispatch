/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

/**
 * The forms an argument takes, as an {@code ARGUMENT_INVALID} refusal states
 * the one a value missed.
 *
 * <p>A closed set rather than a sentence handed in by the place that refuses:
 * each form is one declared message of {@link RefusalMessages}, so a refusal
 * can say only what is declared, and a value the caller sent has no way into
 * it (DEC-0043 R18).
 */
public enum Form {

    FIELDS_OBJECT(RefusalCode.Message.INVALID_FIELDS_OBJECT),
    STRING(RefusalCode.Message.INVALID_STRING),
    ONE_OF(RefusalCode.Message.INVALID_ONE_OF),
    LIST_OF_STRINGS(RefusalCode.Message.INVALID_LIST_OF_STRINGS),
    OBJECT(RefusalCode.Message.INVALID_OBJECT),
    BOOLEAN(RefusalCode.Message.INVALID_BOOLEAN),
    WHOLE_NUMBER(RefusalCode.Message.INVALID_WHOLE_NUMBER),
    PARENT_ELSEWHERE(RefusalCode.Message.INVALID_PARENT_ELSEWHERE),
    PARENT_NOT_A_ROOT(RefusalCode.Message.INVALID_PARENT_NOT_A_ROOT),
    APPARATUS_PATTERN(RefusalCode.Message.INVALID_APPARATUS_PATTERN),
    APPARATUS_UNBOUNDED(RefusalCode.Message.INVALID_APPARATUS_UNBOUNDED),
    STATE_FILTER(RefusalCode.Message.INVALID_STATE_FILTER),
    BRACKET_FILTER(RefusalCode.Message.INVALID_BRACKET_FILTER),
    ADDRESS_FILTER(RefusalCode.Message.INVALID_ADDRESS_FILTER),
    ADDRESS_FILTER_ELSEWHERE(RefusalCode.Message.INVALID_ADDRESS_FILTER_ELSEWHERE),
    VALUE_FILTER(RefusalCode.Message.INVALID_VALUE_FILTER),
    LEASE_END(RefusalCode.Message.INVALID_LEASE_END),
    INSTANT(RefusalCode.Message.INVALID_INSTANT),
    INSTANT_RANGE(RefusalCode.Message.INVALID_INSTANT_RANGE),
    ANSWER_ONE_OF_TWO(RefusalCode.Message.INVALID_ANSWER_ONE_OF_TWO),
    ADDRESS(RefusalCode.Message.INVALID_ADDRESS),
    SCOPE(RefusalCode.Message.INVALID_SCOPE),
    SELECTOR(RefusalCode.Message.INVALID_SELECTOR),
    METADATA(RefusalCode.Message.INVALID_METADATA),
    CURATION_TARGET(RefusalCode.Message.INVALID_CURATION_TARGET),
    ADDENDUM_TARGET(RefusalCode.Message.INVALID_ADDENDUM_TARGET),
    ADDENDA_EXHAUSTED(RefusalCode.Message.INVALID_ADDENDA_EXHAUSTED),
    ONCE(RefusalCode.Message.INVALID_ONCE),
    BODY(RefusalCode.Message.INVALID_BODY);

    private final RefusalCode.Message message;

    Form(RefusalCode.Message message) {
        this.message = message;
    }

    /** The message that states this form. */
    public RefusalCode.Message message() {
        return message;
    }
}
