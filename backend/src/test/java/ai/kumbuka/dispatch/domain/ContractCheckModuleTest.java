/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.repository.DefinitionRepository;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * The checks of a start against the start contract and the schema of its
 * version, and of a closing's return against the version, on a version built
 * of mocked rows: each rule refuses by its reason, names the field by its path
 * and never the value (DEC-0056/C7, C21), and each refusal has its red state
 * beside it, the same call with the value corrected, which is taken.
 *
 * <p>The version planted here: a start contract of a required number {@code
 * amount}, an optional choice {@code priority} of the enumeration low, high,
 * an optional boolean {@code urgent}, an optional date {@code due} and an
 * optional text {@code summary}; a field {@code internal} in the schema that
 * the start contract does not name; and the outcome labels {@code approved}
 * of the version and {@code reviewed} of an activity.
 */
class ContractCheckModuleTest {

    /** A value the caller sends that no refusal may give back. */
    private static final String MARKED = "zz-marked-7319";

    private static final String AMOUNT = "amount";
    private static final String PRIORITY = "priority";
    private static final String URGENT = "urgent";
    private static final String SUMMARY = "summary";
    private static final String APPROVED = "approved";
    private static final String BASIS = "basis";
    private static final String GREEN = "green";
    private static final String OUTCOME_PATH = "fields.outcome";
    private static final String AMOUNT_PATH = "fields.values.amount";
    private static final String PRIORITY_PATH = "fields.values.priority";
    private static final String URGENT_PATH = "fields.values.urgent";
    private static final String SUMMARY_PATH = "fields.values.summary";

    /** The paths of every field the start contract names, in its order. */
    private static final List<String> CONTRACT = List.of(AMOUNT_PATH, PRIORITY_PATH,
        URGENT_PATH, "fields.values.due", SUMMARY_PATH);

    // -----------------------------------------------------------------------
    // The start contract
    // -----------------------------------------------------------------------

    @Test
    void every_field_of_every_type_is_taken_in_its_typed_column() {
        List<ContractCheck.Checked> checked = ContractCheck.startValues(version(false, false),
            Map.of(AMOUNT, 12, PRIORITY, "high", URGENT, true, "due", "2026-12-01",
                SUMMARY, "a text"));

        assertThat(checked).extracting(c -> c.field().name())
            .as("in the order of the start contract")
            .containsExactly(AMOUNT, PRIORITY, URGENT, "due", SUMMARY);
        assertThat(checked).extracting(ContractCheck.Checked::value).containsExactly(
            new TypedValue(null, BigDecimal.valueOf(12), null, null, null),
            new TypedValue(null, null, null, null, 32L),
            new TypedValue(true, null, null, null, null),
            new TypedValue(null, null, LocalDate.of(2026, 12, 1), null, null),
            new TypedValue(null, null, null, "a text", null));
    }

    @Test
    void a_required_field_that_did_not_arrive_is_refused_by_its_path() {
        Map<String, Object> without = new HashMap<>();
        without.put(AMOUNT, null);
        without.put(SUMMARY, MARKED);
        assertRefused(() -> ContractCheck.startValues(version(false, false), without),
            DispatchException.Reason.CONTRACT_FIELD_MISSING, List.of(AMOUNT_PATH));

        assertThat(ContractCheck.startValues(version(false, false),
                Map.of(AMOUNT, 1, SUMMARY, MARKED)))
            .as("RED STATE, observed: the same start with the field is taken")
            .hasSize(2);
    }

    @Test
    void a_field_the_start_contract_does_not_name_is_refused_with_the_fields_it_names() {
        assertRefused(() -> ContractCheck.startValues(version(false, false),
                Map.of(AMOUNT, 1, "internal", MARKED)),
            DispatchException.Reason.CONTRACT_FIELD_UNDECLARED, CONTRACT);
        assertRefused(() -> ContractCheck.startValues(version(false, false),
                Map.of(AMOUNT, 1, MARKED, MARKED)),
            DispatchException.Reason.CONTRACT_FIELD_UNDECLARED, CONTRACT);

        assertThat(ContractCheck.startValues(version(false, false), Map.of(AMOUNT, 1)))
            .as("RED STATE, observed: the same start without the field is taken").hasSize(1);
    }

    @Test
    void a_value_of_another_type_than_its_field_is_refused_by_its_path() {
        for (Map.Entry<String, Object> wrong : Map.<String, Object>of(AMOUNT, MARKED,
                URGENT, MARKED, "due", MARKED, SUMMARY, 7, PRIORITY, 3).entrySet()) {
            Map<String, Object> given = new HashMap<>(Map.of(AMOUNT, 1));
            given.put(wrong.getKey(), wrong.getValue());
            assertRefused(() -> ContractCheck.startValues(version(false, false), given),
                DispatchException.Reason.CONTRACT_FIELD_MISTYPED,
                List.of("fields.values." + wrong.getKey()));
        }
        assertRefused(() -> ContractCheck.startValues(version(false, false),
                Map.of(AMOUNT, 1, SUMMARY, "x".repeat(TypedValue.TEXT_BOUND + 1))),
            DispatchException.Reason.CONTRACT_FIELD_MISTYPED, List.of(SUMMARY_PATH));

        assertThat(ContractCheck.startValues(version(false, false),
                Map.of(AMOUNT, 1.5, URGENT, false, "due", "2026-01-31",
                    SUMMARY, "x".repeat(TypedValue.TEXT_BOUND))))
            .as("RED STATE, observed: the same fields with values of their types are taken")
            .hasSize(4);
    }

    @Test
    void a_choice_outside_its_enumeration_is_refused_by_its_path() {
        assertRefused(() -> ContractCheck.startValues(version(false, false),
                Map.of(AMOUNT, 1, PRIORITY, MARKED)),
            DispatchException.Reason.CONTRACT_VALUE_UNENUMERATED,
            List.of(PRIORITY_PATH));

        assertThat(ContractCheck.startValues(version(false, false),
                Map.of(AMOUNT, 1, PRIORITY, "low")))
            .as("RED STATE, observed: the same start with a value of the enumeration is taken")
            .extracting(ContractCheck.Checked::value)
            .contains(new TypedValue(null, null, null, null, 31L));
    }

    // -----------------------------------------------------------------------
    // The return of a closing
    // -----------------------------------------------------------------------

    @Test
    void a_verdict_and_a_label_the_version_requires_are_refused_by_their_part_where_missing() {
        assertRefused(() -> ContractCheck.closing(version(true, false), null, null, null),
            DispatchException.Reason.RETURN_PART_MISSING, List.of("fields.verdict"));
        assertRefused(() -> ContractCheck.closing(version(false, true), GREEN, null, null),
            DispatchException.Reason.RETURN_PART_MISSING, List.of(OUTCOME_PATH));

        assertThat(ContractCheck.closing(version(true, true), GREEN, APPROVED, BASIS))
            .as("RED STATE, observed: the same return with both is taken, the label by its row")
            .isEqualTo(new ContractCheck.Return(GREEN, 41L));
        assertThat(ContractCheck.closing(version(false, false), null, null, null))
            .as("and a version that requires neither takes a return with neither")
            .isEqualTo(new ContractCheck.Return(null, null));
    }

    @Test
    void a_label_is_one_of_the_versions_labels_and_comes_with_its_translation_basis() {
        assertRefused(() -> ContractCheck.closing(version(false, false), null, MARKED, BASIS),
            DispatchException.Reason.RETURN_OUTCOME_UNDECLARED, List.of(OUTCOME_PATH));
        assertRefused(() -> ContractCheck.closing(version(false, false), null, "reviewed",
                BASIS),
            DispatchException.Reason.RETURN_OUTCOME_UNDECLARED, List.of(OUTCOME_PATH));
        assertRefused(() -> ContractCheck.closing(version(false, false), null, APPROVED, null),
            DispatchException.Reason.RETURN_PART_MISSING, List.of("fields.translation_basis"));

        assertThat(ContractCheck.closing(version(false, false), null, APPROVED, MARKED))
            .as("RED STATE, observed: a label of the version with its basis is taken")
            .isEqualTo(new ContractCheck.Return(null, 41L));
    }

    // -----------------------------------------------------------------------

    private static void assertRefused(Runnable call, DispatchException.Reason reason,
                                      List<String> paths) {
        assertThatThrownBy(call::run)
            .isInstanceOfSatisfying(DispatchException.class, refused -> {
                assertThat(refused.reason()).as("refused by its rule").isEqualTo(reason);
                assertThat(refused.offenders()).as("naming the field by its path")
                    .containsExactlyElementsOf(paths);
                assertThat(refused.getMessage() + refused.offenders())
                    .as("and never the value sent").doesNotContain(MARKED);
            });
    }

    /** The planted version, with what its return requires. */
    private static DefinitionRepository.Declared version(boolean verdictRequired,
                                                         boolean outcomeRequired) {
        DefinitionVersion version = Mockito.mock(DefinitionVersion.class);
        when(version.verdictRequired()).thenReturn(verdictRequired);
        when(version.outcomeRequired()).thenReturn(outcomeRequired);

        Enumeration levels = Mockito.mock(Enumeration.class);
        when(levels.id()).thenReturn(30L);
        List<ControlField> fields = List.of(
            field(1, AMOUNT, "number", null), field(2, PRIORITY, "choice", 30L),
            field(3, URGENT, "boolean", null), field(4, "due", "date", null),
            field(5, SUMMARY, "text", null), field(6, "internal", "text", null));
        List<ContractField> contract = List.of(start(1, true), start(2, false), start(3, false),
            start(4, false), start(5, false));
        List<OutcomeLabel> labels = List.of(label(41L, null, APPROVED),
            label(42L, 9L, "reviewed"));
        return new DefinitionRepository.Declared(version, List.of(), labels, List.of(levels),
            List.of(value(31L, "low"), value(32L, "high")), fields, contract);
    }

    private static ControlField field(long id, String name, String type,
                                      @Nullable Long enumeration) {
        ControlField field = Mockito.mock(ControlField.class);
        when(field.id()).thenReturn(id);
        when(field.name()).thenReturn(name);
        when(field.type()).thenReturn(type);
        when(field.enumerationId()).thenReturn(Optional.ofNullable(enumeration));
        return field;
    }

    private static ContractField start(long field, boolean required) {
        ContractField row = Mockito.mock(ContractField.class);
        when(row.activityId()).thenReturn(Optional.empty());
        when(row.controlFieldId()).thenReturn(field);
        when(row.required()).thenReturn(required);
        return row;
    }

    private static EnumerationValue value(long id, String text) {
        EnumerationValue value = Mockito.mock(EnumerationValue.class);
        when(value.id()).thenReturn(id);
        when(value.enumerationId()).thenReturn(30L);
        when(value.value()).thenReturn(text);
        return value;
    }

    private static OutcomeLabel label(long id, @Nullable Long activity, String text) {
        OutcomeLabel label = Mockito.mock(OutcomeLabel.class);
        when(label.id()).thenReturn(id);
        when(label.activityId()).thenReturn(Optional.ofNullable(activity));
        when(label.label()).thenReturn(text);
        return label;
    }
}
