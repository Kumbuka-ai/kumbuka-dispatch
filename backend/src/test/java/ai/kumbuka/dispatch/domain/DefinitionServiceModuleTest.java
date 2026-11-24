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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The reader of definitions against a mocked repository: how it assembles the
 * rows a version declares into the answer, and that it answers {@code
 * NOT_FOUND} where the repository finds no seeded version.
 *
 * <p>The rows are handed in the order the repository reads them; what the
 * store holds and how the read is bounded is observed in {@code
 * ReadDefinitionIT} and {@code DefinitionTenancyIT}.
 */
class DefinitionServiceModuleTest {

    private static final UUID SCOPE = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final String SELECTOR = "sprint";
    private static final String CHOICE = "choice";
    private static final String LEVEL = "level";

    private DefinitionRepository repository;
    private DefinitionService reader;

    @BeforeEach
    void wire() {
        repository = Mockito.mock(DefinitionRepository.class);
        reader = new DefinitionService(repository);
    }

    @Test
    void the_rows_of_a_version_are_assembled_into_its_answer() {
        DefinitionVersion version = version();
        Enumeration level = enumeration(31L, LEVEL);
        Enumeration empty = enumeration(32L, "empty");
        ControlField choice = field(41L, LEVEL, CHOICE, 31L);
        ControlField note = field(42L, "note", "text", null);
        Activity review = activity(51L, "review", true);
        Activity file = activity(52L, "file", false);
        DefinitionRepository.Declared declared = new DefinitionRepository.Declared(version,
            List.of(review, file),
            List.of(label(null, "approved"), label(51L, "accepted"), label(51L, "rejected")),
            List.of(level, empty),
            List.of(value(31L, "low"), value(31L, "high")),
            List.of(choice, note),
            List.of(contract(51L, 41L, true), contract(null, 42L, false)));
        when(repository.seeded(SCOPE, SELECTOR, 2)).thenReturn(Optional.of(declared));

        DefinitionView view = reader.read(SCOPE, SELECTOR, 2);

        assertThat(List.of(view.selector(), view.version(), view.state(),
                view.verdictRequired(), view.outcomeRequired()))
            .as("the head of the version")
            .containsExactly(SELECTOR, 2, "active", true, false);
        assertThat(view.outcomeLabels()).as("the version's own labels")
            .containsExactly("approved");
        assertThat(view.schema()).as("each field, a choice field with the name of its "
                + "enumeration and every other without one")
            .containsExactly(new DefinitionView.SchemaField(LEVEL, CHOICE, LEVEL),
                new DefinitionView.SchemaField("note", "text", null));
        assertThat(view.enumerations()).as("each enumeration with its values, an empty one "
                + "with none")
            .containsExactly(new DefinitionView.NamedValues(LEVEL, List.of("low", "high")),
                new DefinitionView.NamedValues("empty", List.of()));
        assertThat(view.activities()).as("each activity with its own labels and contract")
            .containsExactly(
                new DefinitionView.ActivityEntry("review", true, true, true,
                    List.of("accepted", "rejected"),
                    List.of(new DefinitionView.ContractEntry(LEVEL, true)), "text", true),
                new DefinitionView.ActivityEntry("file", false, false, false, List.of(),
                    List.of(), "none", false));
        assertThat(view.startContract()).as("the fields of no activity: the start contract")
            .containsExactly(new DefinitionView.ContractEntry("note", false));
        assertThat(view.systemEnumerations()).as("every system enumeration, by its name")
            .extracting(DefinitionView.NamedValues::name)
            .containsExactly("task_state", "instance_state", "act_kind", "verdict",
                "task_resolution", "hold_reason", "criterion_status", "path");
    }

    @Test
    void no_seeded_version_is_refused_as_not_found() {
        when(repository.seeded(SCOPE, SELECTOR, null)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reader.read(SCOPE, SELECTOR, null))
            .isInstanceOfSatisfying(DispatchException.class, refused ->
                assertThat(refused.reason()).isEqualTo(DispatchException.Reason.NOT_FOUND));
        verify(repository).seeded(SCOPE, SELECTOR, null);
    }

    // -----------------------------------------------------------------------

    private static DefinitionVersion version() {
        DefinitionVersion version = Mockito.mock(DefinitionVersion.class);
        when(version.id()).thenReturn(21L);
        when(version.version()).thenReturn(2);
        when(version.state()).thenReturn("active");
        when(version.verdictRequired()).thenReturn(true);
        when(version.outcomeRequired()).thenReturn(false);
        return version;
    }

    private static Enumeration enumeration(long id, String name) {
        Enumeration enumeration = Mockito.mock(Enumeration.class);
        when(enumeration.id()).thenReturn(id);
        when(enumeration.name()).thenReturn(name);
        return enumeration;
    }

    private static EnumerationValue value(long enumeration, String text) {
        EnumerationValue value = Mockito.mock(EnumerationValue.class);
        when(value.enumerationId()).thenReturn(enumeration);
        when(value.value()).thenReturn(text);
        return value;
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

    /** An activity whose requirements and may_ask are all the flag given. */
    private static Activity activity(long id, String name, boolean needs) {
        Activity activity = Mockito.mock(Activity.class);
        when(activity.id()).thenReturn(id);
        when(activity.name()).thenReturn(name);
        when(activity.verdictRequired()).thenReturn(needs);
        when(activity.outcomeRequired()).thenReturn(needs);
        when(activity.criteriaRequired()).thenReturn(needs);
        when(activity.inputKind()).thenReturn(needs ? "text" : "none");
        when(activity.mayAsk()).thenReturn(needs);
        return activity;
    }

    private static OutcomeLabel label(@Nullable Long activity, String text) {
        OutcomeLabel label = Mockito.mock(OutcomeLabel.class);
        when(label.activityId()).thenReturn(Optional.ofNullable(activity));
        when(label.label()).thenReturn(text);
        return label;
    }

    private static ContractField contract(@Nullable Long activity, long field, boolean required) {
        ContractField contract = Mockito.mock(ContractField.class);
        when(contract.activityId()).thenReturn(Optional.ofNullable(activity));
        when(contract.controlFieldId()).thenReturn(field);
        when(contract.required()).thenReturn(required);
        return contract;
    }
}
