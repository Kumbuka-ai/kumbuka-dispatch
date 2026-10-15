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
 * a refusal can then say only what is declared here, and a value the caller
 * sent has no way into it (DEC-0043 R18).
 */
public enum Form {

    /** {@code fields} is an object. */
    FIELDS_OBJECT("it carries the values this call writes, and is an object"),
    /** A string argument received something else. */
    STRING("it is a string"),
    /** A closed set of values. */
    ONE_OF("it is one of the values the description of the argument lists"),
    /** A list of strings. */
    LIST_OF_STRINGS("it is a list of strings, even when it carries one value"),
    /** An object. */
    OBJECT("it is an object"),
    /** A yes or no. */
    BOOLEAN("it is true or false"),
    /** A whole number. */
    WHOLE_NUMBER("it is a whole number"),
    /** A create's parent in another bracket. */
    PARENT_ELSEWHERE("it names another scope or bracket kind than the call does, and a child is "
        + "created in its root's bracket"),
    /** A create's parent that is a child. */
    PARENT_NOT_A_ROOT("a parent is a bracket root, the <number>.0 of its bracket"),
    /** The character rule of an apparatus pattern. */
    APPARATUS_PATTERN("a pattern is one or more of A-Z, a-z, 0-9, '+', '-' and '*', and '*' is "
        + "the only wildcard; the underscore and the percent sign carry a meaning of their own "
        + "in the comparison and are excluded"),
    /** A draw for every apparatus. */
    APPARATUS_UNBOUNDED("a pattern of nothing but '*' matches every apparatus, which is the "
        + "blind draw the argument exists to refuse"),
    /** The state filter of a listing. */
    STATE_FILTER("it is one or more of draft, open, active, on_hold, delivered and closed, "
        + "comma-separated"),
    /** The bracket filter of a listing. */
    BRACKET_FILTER("it is one or more bracket numbers, comma-separated"),
    /** The address filter of a listing. */
    ADDRESS_FILTER("it is one or more complete task addresses, comma-separated"),
    /** An address filter that names another bracket kind or scope. */
    ADDRESS_FILTER_ELSEWHERE("a listing filters the tasks of its own scope and bracket kind"),
    /** Any other filter. */
    VALUE_FILTER("it is one or more values, comma-separated"),
    /** A lease that would end past what the store holds. */
    LEASE_END("a lease ends before the year 294276"),
    /** An instant that does not parse. */
    INSTANT("an instant is written in ISO-8601 with its offset, such as 2026-10-07T09:00:00Z"),
    /** An instant outside what the store holds. */
    INSTANT_RANGE("an instant lies from 0001-01-01T00:00:00Z up to +294276-01-01T00:00:00Z"),
    /** An answer that names an option and carries text. */
    ANSWER_ONE_OF_TWO("an answer names an option or carries text, not both"),
    /** A task's address. */
    ADDRESS("a task's address is written dispatch://<scope>/<selector>/<number>.<sub>"),
    /** A scope's name. */
    SCOPE("a scope's name is a DNS label: lower case, digits and inner hyphens"),
    /** A bracket kind's name. */
    SELECTOR("a bracket kind's name is lower case, digits and inner hyphens"),
    /** Metadata the store refuses. */
    METADATA("metadata carries your own keys, each value a string or a list of strings of at "
        + "most 512 characters, and no URL carrying credentials"),
    /** A curation that names the task itself. */
    CURATION_TARGET("it names another task than this one"),
    /** An addendum to a part the task carries no text of. */
    ADDENDUM_TARGET("it names a part the task carries a text of; the read of the task lists "
        + "them under fields.texts"),
    /** A text whose addenda are exhausted. */
    ADDENDA_EXHAUSTED("that text already carries addenda a to z, and a further letter would "
        + "reissue one that exists"),
    /** An option the question does not offer. */
    ANSWER_OPTION("it is one of the question's options, which the read of the task lists"),
    /** Free text on a question that admits none. */
    ANSWER_FREE_TEXT("the question admits no free text; name one of its options, which the "
        + "read of the task lists"),
    /** A value the kernel refused for no reason the surface can name. */
    VALUE("it is a value this call takes"),
    /** An argument a REST request carries twice. */
    ONCE("it arrived twice: in the query or the body, and in the path or a header, and a call "
        + "takes each argument once"),
    /** A REST body that is no JSON object. */
    BODY("a body is a JSON object of the call's arguments"),
    /** The surface header. */
    SURFACE_HEADER("it takes the one value assistant, once, or is left out");

    private final String text;

    Form(String text) {
        this.text = text;
    }

    /** The form as the refusal states it. */
    public String text() {
        return text;
    }
}
