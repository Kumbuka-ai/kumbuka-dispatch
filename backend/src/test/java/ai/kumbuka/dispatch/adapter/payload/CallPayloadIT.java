/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.payload;

import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import ai.kumbuka.dispatch.domain.DispatchException;
import ai.kumbuka.dispatch.domain.PageRequest;
import ai.kumbuka.dispatch.domain.SortKey;
import ai.kumbuka.dispatch.surface.Argument;
import ai.kumbuka.dispatch.surface.ArgumentPath;
import ai.kumbuka.dispatch.surface.ProcessVerb;
import ai.kumbuka.dispatch.surface.Refusal;
import ai.kumbuka.dispatch.surface.RefusalCode;
import ai.kumbuka.dispatch.surface.Surface;
import ai.kumbuka.dispatch.surface.VerbName;
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
// The texts a call answers with come from the message bundles, which the
// running application builds: this test runs with it.
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class CallPayloadIT {

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
        assertThat(CallPayload.wholeOf(given))
            .as("read as a whole number before the page's bounds are applied")
            .contains(read);
    }

    @ParameterizedTest
    @MethodSource("notWhole")
    void a_value_that_is_no_whole_number_is_refused_as_a_page_size(Object given) {
        Refusal refused = Wire.refused(() -> query(given));
        assertThat(refused.code()).isEqualTo(RefusalCode.PAGE_SIZE_REJECTED);
        assertThat(refused.message())
            .isEqualTo("page_size is not valid for dispatch_query: it is a whole number from 1 "
                + "to 200.");
        assertThat(Wire.data(refused)).containsEntry("attempted", "dispatch_query")
            .containsEntry("offenders", List.of("page_size"));
    }

    @ParameterizedTest
    @MethodSource("outsideThePage")
    void a_whole_number_outside_the_page_is_refused_as_a_page_size(Object given) {
        Refusal refused = Wire.refused(() -> query(given));
        assertThat(refused.code()).isEqualTo(RefusalCode.PAGE_SIZE_REJECTED);
        assertThat(Wire.data(refused)).containsEntry("offenders", List.of("page_size"));
    }

    /**
     * The edges of the page, as the form check takes them: the exact bounds
     * and the shapes of a whole number at them are taken, one past either
     * bound and a number with a fraction are refused.
     */
    @Test
    void the_page_takes_its_exact_bounds_and_refuses_one_past_them() {
        assertThat(query(1).page().pageSize()).as("the smallest page").isEqualTo(1);
        assertThat(query(200).page().pageSize()).as("the largest page").isEqualTo(200);
        assertThat(query(1.0d).page().pageSize()).as("1.0 is the whole number 1").isEqualTo(1);
        for (Object past : List.of(0, 201, 1.5d)) {
            Refusal refused = Wire.refused(() -> query(past));
            assertThat(refused.code()).as("%s", past).isEqualTo(RefusalCode.PAGE_SIZE_REJECTED);
            assertThat(Wire.data(refused)).as("%s", past)
                .containsEntry("offenders", List.of("page_size"));
        }
    }

    /** The kernel holds the same bounds, a second net under the form check. */
    @Test
    void the_kernel_refuses_a_page_outside_its_bounds_as_well() {
        DispatchException refused = catchThrowableOfType(DispatchException.class,
            () -> PageRequest.of(201L, null, SortKey.Direction.ASC, null));
        assertThat(refused.reason()).isEqualTo(DispatchException.Reason.PAGE_SIZE_REJECTED);
    }

    // ======================================================================
    // offenders: the arguments a refusal concerns, by name and never by value;
    // an undeclared name is answered with the declared ones (DEC-0043 R19)
    // ======================================================================

    @Test
    void unknown_arguments_are_answered_with_the_arguments_the_call_accepts() {
        Map<String, Object> arguments = given(10);
        arguments.put("limit", "5");
        arguments.put("cursor", "secret-value");

        Refusal refused = Wire.refused(() -> new CallPayload(ProcessVerb.QUERY, Surface.MCP, arguments));

        assertThat(refused.code()).isEqualTo(RefusalCode.ARGUMENT_UNKNOWN);
        assertThat(Wire.data(refused)).containsEntry("attempted", "dispatch_query")
            .containsEntry("accepts", List.of("scope", "selector", "state", "apparatus",
                "bracket", "address", "page_size", "after", "sort", "direction"))
            .doesNotContainKey("offenders");
        assertThat(refused.message() + Wire.data(refused))
            .as("neither the unknown names nor their values come back")
            .doesNotContain("limit").doesNotContain("cursor").doesNotContain("secret-value");
    }

    @Test
    void an_unknown_argument_under_fields_is_answered_with_the_fields_the_call_accepts() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("address", "dispatch://probe-scope/sprint/1.0");
        arguments.put("conflict_token", "t");
        arguments.put("fields", Map.of("draft", "a text"));

        Refusal refused = Wire.refused(() -> new CallPayload(ProcessVerb.UPDATE, Surface.MCP, arguments));

        assertThat(refused.code()).isEqualTo(RefusalCode.ARGUMENT_UNKNOWN);
        assertThat(Wire.data(refused)).containsEntry("accepts", List.of("fields.title",
            "fields.apparatus", "fields.text", "fields.metadata"));
        assertThat(refused.message()).isEqualTo("dispatch_update takes these arguments "
            + "under fields: fields.title, fields.apparatus, fields.text, fields.metadata.");
        assertThat(refused.message() + Wire.data(refused)).doesNotContain("draft");
    }

    @Test
    void a_direction_outside_its_values_is_invalid_and_names_direction() {
        Map<String, Object> arguments = given(10);
        arguments.put("direction", "sideways");

        Refusal refused = Wire.refused(() -> new CallPayload(ProcessVerb.QUERY, Surface.MCP, arguments));

        assertThat(refused.code()).isEqualTo(RefusalCode.ARGUMENT_INVALID);
        assertThat(Wire.data(refused)).containsEntry("offenders", List.of("direction"));
        assertThat(refused.message() + Wire.data(refused)).doesNotContain("sideways");
    }

    @Test
    void a_sort_outside_its_values_is_undeclared_and_names_sort() {
        Map<String, Object> arguments = given(10);
        arguments.put("sort", "title");

        Refusal refused = Wire.refused(() -> new CallPayload(ProcessVerb.QUERY, Surface.MCP, arguments));

        assertThat(refused.code()).isEqualTo(RefusalCode.SORT_UNDECLARED);
        assertThat(Wire.data(refused)).containsEntry("offenders", List.of("sort"));
        assertThat(refused.message() + Wire.data(refused)).doesNotContain("title");
    }

    @Test
    void a_value_of_a_list_that_is_no_string_is_named_by_its_position() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("address", "dispatch://probe-scope/sprint/1.0");
        arguments.put("receipt", "r");
        arguments.put("fields", Map.of("question", "q", "options",
            java.util.Arrays.asList("yes", 7, "no")));

        Refusal refused = Wire.refused(() -> new CallPayload(ProcessVerb.ASK, Surface.MCP, arguments));

        assertThat(refused.code()).isEqualTo(RefusalCode.ARGUMENT_INVALID);
        assertThat(Wire.data(refused)).containsEntry("offenders", List.of("fields.options[1]"));
    }

    @Test
    void a_missing_argument_is_named_in_offenders() {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("scope", "probe-scope");

        Refusal refused = Wire.refused(() -> new CallPayload(ProcessVerb.QUERY, Surface.MCP, arguments));

        assertThat(refused.code()).isEqualTo(RefusalCode.ARGUMENT_MISSING);
        assertThat(Wire.data(refused)).containsEntry("offenders", List.of("selector"));
    }

    @Test
    void the_refusals_of_the_page_name_their_one_argument() {
        VerbName q = VerbName.of(ProcessVerb.QUERY, Surface.MCP);
        assertThat(Wire.data(Refusal.pageSizeRejected(q, List.of(path("page_size")))))
            .containsEntry("offenders", List.of("page_size"));
        assertThat(Wire.data(Refusal.cursorMalformed(q, q, List.of(path("after")))))
            .containsEntry("offenders", List.of("after"));
        assertThat(Wire.data(Refusal.cursorForeign(q, List.of(), path("after"))))
            .containsEntry("offenders", List.of("after"));
        assertThat(Wire.data(Refusal.sortUndeclared(q, List.of(path("sort")))))
            .containsEntry("offenders", List.of("sort"));
    }

    @Test
    void an_unknown_call_is_answered_with_the_calls_there_are() {
        Refusal refused = Refusal.callUnknown(VerbName.all(Surface.MCP));

        assertThat(refused.code()).isEqualTo(RefusalCode.VERB_NOT_CARRIED);
        assertThat(Wire.data(refused)).containsOnlyKeys("accepts");
        @SuppressWarnings("unchecked")
        List<String> accepts = (List<String>) Wire.data(refused).get("accepts");
        assertThat(accepts).hasSize(25).contains("dispatch_claim", "dispatch_query");
    }

    // ======================================================================
    // null is an argument not sent (DEC-0043 R17)
    // ======================================================================

    @Test
    void an_optional_argument_sent_as_null_takes_its_fallback() {
        Map<String, Object> arguments = given(null);
        arguments.put("sort", null);
        arguments.put("after", null);

        CallPayload read = new CallPayload(ProcessVerb.QUERY, Surface.MCP, arguments);

        assertThat(read.wholeNumber("page_size")).isEmpty();
        assertThat(read.top("sort")).isEmpty();
        assertThat(read.top("after")).isEmpty();
    }

    @Test
    void a_required_argument_sent_as_null_is_missing() {
        Map<String, Object> arguments = given(10);
        arguments.put("selector", null);

        Refusal refused = Wire.refused(() -> new CallPayload(ProcessVerb.QUERY, Surface.MCP, arguments));

        assertThat(refused.code()).isEqualTo(RefusalCode.ARGUMENT_MISSING);
        assertThat(Wire.data(refused)).containsEntry("offenders", List.of("selector"));
    }

    @Test
    void an_undeclared_name_sent_as_null_is_no_argument() {
        Map<String, Object> arguments = given(10);
        arguments.put("limit", null);

        CallPayload read = new CallPayload(ProcessVerb.QUERY, Surface.MCP, arguments);

        assertThat(read.wholeNumber("page_size")).contains(10L);
    }

    @Test
    void fields_sent_as_null_are_no_fields_and_a_null_under_fields_is_absent() {
        Map<String, Object> create = new LinkedHashMap<>();
        create.put("scope", "probe-scope");
        create.put("selector", "sprint");
        create.put("fields", null);
        Refusal withoutFields = Wire.refused(() -> new CallPayload(ProcessVerb.CREATE, Surface.MCP, create));
        assertThat(withoutFields.code()).isEqualTo(RefusalCode.ARGUMENT_MISSING);
        assertThat(Wire.data(withoutFields)).containsEntry("offenders", List.of("fields.title"));

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("title", "a title");
        fields.put("apparatus", "code");
        fields.put("text", null);
        fields.put("undeclared", null);
        create.put("fields", fields);
        CallPayload read = new CallPayload(ProcessVerb.CREATE, Surface.MCP, create);
        assertThat(read.field("text")).isEmpty();
    }

    private static ArgumentPath path(String name) {
        return ArgumentPath.of(ProcessVerb.QUERY.declared(name, Argument.Placement.TOP));
    }

    private static CallPayload query(Object pageSize) {
        return new CallPayload(ProcessVerb.QUERY, Surface.MCP, given(pageSize));
    }

    private static Map<String, Object> given(Object pageSize) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("scope", "probe-scope");
        arguments.put("selector", "sprint");
        arguments.put("page_size", pageSize);
        return arguments;
    }
}
