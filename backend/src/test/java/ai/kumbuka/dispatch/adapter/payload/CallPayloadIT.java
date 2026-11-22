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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

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
@Disabled(CallPayloadIT.REBUILT)
class CallPayloadIT {

    static final String REBUILT = "rebuilt in T13: a call's arguments are read by the declaration "
        + "and each refused one is named in offenders";

    /**
     * Stands for the tests of this class until their order rebuilds them:
     * <ul>
     *   <li>{@code a_whole_number_is_taken_whatever_its_shape}</li>
     *   <li>{@code a_value_that_is_no_whole_number_is_refused_as_a_page_size}</li>
     *   <li>{@code a_whole_number_outside_the_page_is_refused_as_a_page_size}</li>
     *   <li>{@code the_page_takes_its_exact_bounds_and_refuses_one_past_them}</li>
     *   <li>{@code the_kernel_refuses_a_page_outside_its_bounds_as_well}</li>
     *   <li>{@code unknown_arguments_are_answered_with_the_arguments_the_call_accepts}</li>
     *   <li>{@code an_unknown_argument_under_fields_is_answered_with_the_fields_the_call_accepts}</li>
     *   <li>{@code a_direction_outside_its_values_is_invalid_and_names_direction}</li>
     *   <li>{@code a_sort_outside_its_values_is_undeclared_and_names_sort}</li>
     *   <li>{@code a_value_of_a_list_that_is_no_string_is_named_by_its_position}</li>
     *   <li>{@code a_missing_argument_is_named_in_offenders}</li>
     *   <li>{@code the_refusals_of_the_page_name_their_one_argument}</li>
     *   <li>{@code an_unknown_call_is_answered_with_the_calls_there_are}</li>
     *   <li>{@code an_optional_argument_sent_as_null_takes_its_fallback}</li>
     *   <li>{@code a_required_argument_sent_as_null_is_missing}</li>
     *   <li>{@code an_undeclared_name_sent_as_null_is_no_argument}</li>
     *   <li>{@code fields_sent_as_null_are_no_fields_and_a_null_under_fields_is_absent}</li>
     * </ul>
     */
    @Test
    void the_tests_of_this_class_are_rebuilt_in_their_order() {
        fail(REBUILT);
    }
}
