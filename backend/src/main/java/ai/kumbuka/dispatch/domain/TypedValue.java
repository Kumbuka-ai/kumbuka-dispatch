/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import org.jspecify.annotations.Nullable;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The value of a control field in its one typed column (D8): a boolean, a
 * number as a decimal, a date as a calendar day, a text of at most 4,096
 * characters, or a choice as a value of the field's enumeration. Exactly one
 * of the five is set; the store holds that by a check.
 *
 * <p>Embedded in the field value an act writes and in the projection of the
 * value in force, which repeats it so that a listing can filter by it (D18).
 *
 * @param booleanValue the value of a boolean field; absent for every other type
 * @param numberValue  the value of a number field; absent for every other type
 * @param dateValue    the value of a date field; absent for every other type
 * @param textValue    the value of a text field; absent for every other type
 * @param choiceId     the enumeration value a choice field names; absent for
 *                     every other type
 */
@Embeddable
public record TypedValue(
    @Column(name = "boolean_value", updatable = false) @Nullable Boolean booleanValue,
    @Column(name = "number_value", updatable = false) @Nullable BigDecimal numberValue,
    @Column(name = "date_value", updatable = false) @Nullable LocalDate dateValue,
    @Column(name = "text_value", updatable = false) @Nullable String textValue,
    @Column(name = "choice_value_id", updatable = false) @Nullable Long choiceId) {

    /** The longest text a text field takes (D8). */
    public static final int TEXT_BOUND = 4096;

    static TypedValue ofBoolean(boolean value) {
        return new TypedValue(value, null, null, null, null);
    }

    static TypedValue ofNumber(BigDecimal value) {
        return new TypedValue(null, value, null, null, null);
    }

    static TypedValue ofDate(LocalDate value) {
        return new TypedValue(null, null, value, null, null);
    }

    static TypedValue ofText(String value) {
        return new TypedValue(null, null, null, value, null);
    }

    static TypedValue ofChoice(long enumerationValueId) {
        return new TypedValue(null, null, null, null, enumerationValueId);
    }
}
