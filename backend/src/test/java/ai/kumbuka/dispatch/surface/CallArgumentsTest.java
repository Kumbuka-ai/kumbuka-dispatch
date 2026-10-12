/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.domain.DispatchException;
import ai.kumbuka.dispatch.domain.PageRequest;
import ai.kumbuka.dispatch.domain.SortKey;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * How a whole number is read, over every shape a parser can hand in: the
 * surfaces carry a JSON number or text, and which Java type a JSON number
 * becomes is the parser's choice, not the caller's.
 *
 * <p>The argument is {@code page_size}, the one whole number the declaration
 * carries. Reading it and bounding it are two steps: the declaration's check
 * asks only whether it is a whole number, and the page refuses one outside its
 * bounds, so a page size of two billion and one of three hundred are refused
 * alike. Both steps refuse with {@code PAGE_SIZE_REJECTED} (ADR-0049), so one
 * that is no whole number is refused as a page size too, and names the
 * argument and not its value in {@code offenders}.
 */
class CallArgumentsTest {

    static Stream<Arguments> taken() {
        return Stream.of(
            Arguments.of(10, 10L),
            Arguments.of(10L, 10L),
            Arguments.of((short) 3, 3L),
            Arguments.of((byte) 4, 4L),
            Arguments.of(10.0d, 10L),
            Arguments.of(2147483647.0d, (long) Integer.MAX_VALUE),
            Arguments.of(-2147483648L, (long) Integer.MIN_VALUE),
            Arguments.of(new BigDecimal("1E+1"), 10L),
            Arguments.of(new BigDecimal("0.000"), 0L),
            Arguments.of(BigInteger.valueOf(7), 7L),
            Arguments.of(" 12 ", 12L),
            Arguments.of("+5", 5L),
            Arguments.of("-0", 0L),
            Arguments.of("2147483647", (long) Integer.MAX_VALUE),
            Arguments.of("-2147483648", (long) Integer.MIN_VALUE),
            Arguments.of("0000000000000000000000000000001", 1L),
            Arguments.of(new BigDecimal("1E+400"), Long.MAX_VALUE),
            Arguments.of(new BigDecimal("-1E+400"), Long.MIN_VALUE),
            Arguments.of("99999999999999999999999999999999999999", Long.MAX_VALUE));
    }

    static Stream<Object> notWhole() {
        return Stream.of(1.5d, 7.5d, Double.NaN, Double.POSITIVE_INFINITY,
            new BigDecimal("1.5"), "1.0", "7.5", "abc", "", "1e3", "١٢", Boolean.TRUE);
    }

    static Stream<Object> outsideThePage() {
        return Stream.of(0, -1, 201, 4294967297L, 2147483648L, -2147483649L, 4294967297.0d,
            new BigDecimal("1E+400"), new BigDecimal("-1E+400"),
            new BigInteger("123456789012345678901234567890"),
            "4294967297", "-2147483649",
            "99999999999999999999999999999999999999", "-99999999999999999999999999999999");
    }

    @ParameterizedTest
    @MethodSource("taken")
    void a_whole_number_is_taken_whatever_its_shape(Object given, long read) {
        assertThat(query(given).wholeNumber("page_size")).isEqualTo(read);
    }

    @ParameterizedTest
    @MethodSource("notWhole")
    void a_value_that_is_no_whole_number_is_refused_as_a_page_size(Object given) {
        Refused refused = catchThrowableOfType(Refused.class, () -> query(given));
        assertThat(refused.code()).isEqualTo(RefusalCode.PAGE_SIZE_REJECTED);
        assertThat(refused.getMessage()).startsWith("page_size = ")
            .endsWith("it is a whole number from 1 to 200.");
        assertThat(refused.data()).containsEntry("attempted", "dispatch_query")
            .containsEntry("offenders", List.of("page_size"));
    }

    @ParameterizedTest
    @MethodSource("outsideThePage")
    void a_whole_number_outside_the_page_is_refused_as_a_page_size(Object given) {
        Long read = query(given).wholeNumber("page_size");
        DispatchException refused = catchThrowableOfType(DispatchException.class,
            () -> PageRequest.of(read, null, SortKey.Direction.ASC, null));
        assertThat(refused.reason()).isEqualTo(DispatchException.Reason.PAGE_SIZE_REJECTED);
    }

    // ======================================================================
    // offenders: the arguments a refusal concerns, by name and never by value
    // ======================================================================

    @Test
    void unknown_arguments_are_named_together_in_offenders() {
        Map<String, Object> arguments = given(10);
        arguments.put("limit", "5");
        arguments.put("cursor", "secret-value");

        Refused refused = catchThrowableOfType(Refused.class,
            () -> new CallArguments(ProcessVerb.QUERY, "dispatch_query", arguments));

        assertThat(refused.code()).isEqualTo(RefusalCode.ARGUMENT_UNKNOWN);
        assertThat(refused.data()).containsEntry("attempted", "dispatch_query")
            .containsEntry("offenders", List.of("limit", "cursor"));
        assertThat(String.valueOf(refused.data())).doesNotContain("secret-value");
    }

    @Test
    void an_unknown_argument_under_fields_is_named_with_its_path() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("address", "dispatch://probe-scope/sprint/1.0");
        arguments.put("conflict_token", "t");
        arguments.put("fields", Map.of("draft", "a text"));

        Refused refused = catchThrowableOfType(Refused.class,
            () -> new CallArguments(ProcessVerb.UPDATE, "dispatch_update", arguments));

        assertThat(refused.code()).isEqualTo(RefusalCode.ARGUMENT_UNKNOWN);
        assertThat(refused.data()).containsEntry("offenders", List.of("fields.draft"));
        assertThat(refused.getMessage()).contains("argument under fields named draft");
    }

    @Test
    void a_direction_outside_its_values_is_invalid_and_names_direction() {
        Map<String, Object> arguments = given(10);
        arguments.put("direction", "sideways");

        Refused refused = catchThrowableOfType(Refused.class,
            () -> new CallArguments(ProcessVerb.QUERY, "dispatch_query", arguments));

        assertThat(refused.code()).isEqualTo(RefusalCode.ARGUMENT_INVALID);
        assertThat(refused.data()).containsEntry("offenders", List.of("direction"));
        assertThat(refused.data().values()).doesNotContain("sideways");
    }

    @Test
    void a_missing_argument_is_named_in_offenders() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("scope", "probe-scope");

        Refused refused = catchThrowableOfType(Refused.class,
            () -> new CallArguments(ProcessVerb.QUERY, "dispatch_query", arguments));

        assertThat(refused.code()).isEqualTo(RefusalCode.ARGUMENT_MISSING);
        assertThat(refused.data()).containsEntry("offenders", List.of("selector"));
    }

    @Test
    void the_refusals_of_the_page_name_their_one_argument() {
        assertThat(Refused.pageSizeRejected("q", "abc", 200).data())
            .containsEntry("offenders", List.of("page_size"));
        assertThat(Refused.cursorMalformed("q", "x", "dispatch_query").data())
            .containsEntry("offenders", List.of("after"));
        assertThat(Refused.cursorForeign("q", List.of("scope")).data())
            .containsEntry("offenders", List.of("after"));
        assertThat(Refused.sortUndeclared("q", "title", List.of("id", "created_at")).data())
            .containsEntry("offenders", List.of("sort"));
    }

    @Test
    void an_unknown_call_is_no_argument_and_names_no_offenders() {
        Refused refused = Refused.callUnknown("dispatch_list", "call", "dispatch_list",
            List.of("dispatch_query"));

        assertThat(refused.code()).isEqualTo(RefusalCode.ARGUMENT_UNKNOWN);
        assertThat(refused.data()).containsOnlyKeys("attempted");
    }

    private static CallArguments query(Object pageSize) {
        return new CallArguments(ProcessVerb.QUERY, "dispatch_query", given(pageSize));
    }

    private static Map<String, Object> given(Object pageSize) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("scope", "probe-scope");
        arguments.put("selector", "sprint");
        arguments.put("page_size", pageSize);
        return arguments;
    }
}
