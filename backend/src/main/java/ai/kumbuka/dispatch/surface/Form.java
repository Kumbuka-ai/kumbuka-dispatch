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

    FIELDS_OBJECT(ReasonCatalogue.Message.INVALID_FIELDS_OBJECT),
    STRING(ReasonCatalogue.Message.INVALID_STRING),
    ONE_OF(ReasonCatalogue.Message.INVALID_ONE_OF),
    LIST_OF_STRINGS(ReasonCatalogue.Message.INVALID_LIST_OF_STRINGS),
    OBJECT(ReasonCatalogue.Message.INVALID_OBJECT),
    BOOLEAN(ReasonCatalogue.Message.INVALID_BOOLEAN),
    WHOLE_NUMBER(ReasonCatalogue.Message.INVALID_WHOLE_NUMBER),
    PARENT_ELSEWHERE(ReasonCatalogue.Message.INVALID_PARENT_ELSEWHERE),
    PARENT_NOT_A_ROOT(ReasonCatalogue.Message.INVALID_PARENT_NOT_A_ROOT),
    APPARATUS_PATTERN(ReasonCatalogue.Message.INVALID_APPARATUS_PATTERN),
    APPARATUS_UNBOUNDED(ReasonCatalogue.Message.INVALID_APPARATUS_UNBOUNDED),
    STATE_FILTER(ReasonCatalogue.Message.INVALID_STATE_FILTER),
    BRACKET_FILTER(ReasonCatalogue.Message.INVALID_BRACKET_FILTER),
    ADDRESS_FILTER(ReasonCatalogue.Message.INVALID_ADDRESS_FILTER),
    ADDRESS_FILTER_ELSEWHERE(ReasonCatalogue.Message.INVALID_ADDRESS_FILTER_ELSEWHERE),
    VALUE_FILTER(ReasonCatalogue.Message.INVALID_VALUE_FILTER),
    LEASE_END(ReasonCatalogue.Message.INVALID_LEASE_END),
    INSTANT(ReasonCatalogue.Message.INVALID_INSTANT),
    INSTANT_RANGE(ReasonCatalogue.Message.INVALID_INSTANT_RANGE),
    ANSWER_ONE_OF_TWO(ReasonCatalogue.Message.INVALID_ANSWER_ONE_OF_TWO),
    ADDRESS(ReasonCatalogue.Message.INVALID_ADDRESS),
    SCOPE(ReasonCatalogue.Message.INVALID_SCOPE),
    SELECTOR(ReasonCatalogue.Message.INVALID_SELECTOR),
    METADATA(ReasonCatalogue.Message.INVALID_METADATA),
    CURATION_TARGET(ReasonCatalogue.Message.INVALID_CURATION_TARGET),
    ADDENDUM_TARGET(ReasonCatalogue.Message.INVALID_ADDENDUM_TARGET),
    ADDENDA_EXHAUSTED(ReasonCatalogue.Message.INVALID_ADDENDA_EXHAUSTED),
    ANSWER_OPTION(ReasonCatalogue.Message.INVALID_ANSWER_OPTION),
    ANSWER_FREE_TEXT(ReasonCatalogue.Message.INVALID_ANSWER_FREE_TEXT),
    ONCE(ReasonCatalogue.Message.INVALID_ONCE),
    BODY(ReasonCatalogue.Message.INVALID_BODY),
    SURFACE_HEADER(ReasonCatalogue.Message.INVALID_SURFACE_HEADER);

    private final ReasonCatalogue.Message message;

    Form(ReasonCatalogue.Message message) {
        this.message = message;
    }

    /** The message that states this form. */
    public ReasonCatalogue.Message message() {
        return message;
    }
}
