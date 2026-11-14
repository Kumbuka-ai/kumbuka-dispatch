/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.architecture;

import ai.kumbuka.dispatch.surface.Argument;
import ai.kumbuka.dispatch.surface.ProcessVerb;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every fixed value the declaration declares is vocabulary in its form (C7.6):
 * lowercase words joined by a hyphen, {@code [a-z0-9]+(-[a-z0-9]+)*}, never by
 * an underscore.
 *
 * <p><strong>The one exception is by argument, not by value.</strong> A value
 * that names a JSON member -- a sort key, a filter field -- is written as the
 * member is, and JSON members are snake_case (C7.6). So the values of the
 * arguments in {@link #NAMING_MEMBERS} are held to the member form, {@code
 * [a-z0-9]+(_[a-z0-9]+)*}, instead; {@code created_at} as a value of {@code
 * sort} is conformant because it names the member {@code created_at}. The list
 * names arguments, so a new value of such an argument is checked against the
 * member form without anyone adding it here, and a new argument whose values
 * name members is a decision written into this list.
 *
 * <p>The expectation is the two patterns and the list, written here from the
 * contract; the values come from the declaration under test.
 */
class VocabularyFormTest {

    /** A vocabulary value (C7.6). */
    private static final Pattern VOCABULARY = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");

    /** A JSON member, as a value that names one is written (C7.6). */
    private static final Pattern MEMBER = Pattern.compile("[a-z0-9]+(_[a-z0-9]+)*");

    /** The arguments whose values name JSON members (C7.6): the sort key of a listing. */
    static final List<String> NAMING_MEMBERS = List.of("sort");

    /** One declared argument with a fixed set of values, as the check reads it. */
    record Declared(String call, String argument, List<String> values) {
    }

    /** Every value outside its form, as {@code call argument: 'value'}; empty where all conform. */
    static List<String> offences(List<Declared> declared) {
        List<String> found = new ArrayList<>();
        for (Declared argument : declared) {
            Pattern form = NAMING_MEMBERS.contains(argument.argument()) ? MEMBER : VOCABULARY;
            for (String value : argument.values()) {
                if (!form.matcher(value).matches()) {
                    found.add(argument.call() + " " + argument.argument() + ": '" + value + "'");
                }
            }
        }
        return found;
    }

    @Test
    void every_declared_fixed_value_has_its_form() {
        List<Declared> declared = new ArrayList<>();
        for (ProcessVerb verb : ProcessVerb.values()) {
            for (Argument argument : verb.arguments()) {
                if (!argument.valueNames().isEmpty()) {
                    declared.add(new Declared(verb.call(), argument.name(),
                        argument.valueNames()));
                }
            }
        }
        assertThat(declared).as("the declaration must have been read").hasSizeGreaterThan(3);

        assertThat(offences(declared))
            .as("a fixed value is lowercase words joined by a hyphen; a value naming a JSON "
                + "member is written as the member (C7.6)")
            .isEmpty();
    }

    /** The red state, observed on every build: each kind of offence is reported. */
    @Test
    void the_checks_report_each_offence_they_exist_for() {
        assertThat(offences(List.of(
                new Declared("thing_list", "kind",
                    List.of("open-question", "open_question", "Open", "-dash", "open")),
                new Declared("thing_list", "sort", List.of("created_at", "created-at", "id")))))
            .as("RED STATE, observed: an underscore, a capital and a leading hyphen in a "
                + "vocabulary value, and a hyphen in a value naming a member")
            .containsExactly("thing_list kind: 'open_question'", "thing_list kind: 'Open'",
                "thing_list kind: '-dash'", "thing_list sort: 'created-at'");
    }
}
