/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.repository.ActRepository;
import ai.kumbuka.dispatch.repository.DefinitionRepository;
import ai.kumbuka.dispatch.repository.InstanceRepository;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The acts on an instance against mocked repositories and a mocked writer of
 * acts: what each writes, in which order, and what it refuses before it
 * writes anything. That the rows stand in the store as written, under the
 * service role, is {@code InstanceStartIT}'s, {@code InstanceCloseIT}'s and
 * {@code InstanceReferenceIT}'s.
 */
class InstanceServiceModuleTest {

    private static final UUID SCOPE = UUID.fromString("00000000-0000-0000-0000-0000000000cc");
    private static final String SLUG = "probe";
    private static final String SELECTOR = "sprint";
    private static final String REFERENCE_ROW = "ExternalReference";
    private static final long INSTANCE = 77L;
    private static final long VERSION = 21L;
    private static final Instant TOKEN = Instant.parse("2026-11-24T08:00:00.000001Z");
    private static final InstanceAddress ADDRESS = new InstanceAddress(SLUG, SELECTOR, 4);
    private static final Provenance PROVENANCE = new Provenance("ada", null,
        DispatchPermission.INSTANCE_CONCLUDE, Provenance.Path.INTERFACE, null);
    private static final Actor CONSOLE = new Actor("ada", DispatchPermission.INSTANCE_START,
        DispatchPermission.INSTANCE_CONCLUDE, DispatchPermission.INSTANCE_WITHDRAW,
        DispatchPermission.INSTANCE_REFER);

    private InstanceRepository instances;
    private DefinitionRepository definitions;
    private ActRepository actRows;
    private ActService acts;
    private InstanceService service;
    private final List<Object> inserted = new ArrayList<>();

    @BeforeEach
    void wire() {
        instances = Mockito.mock(InstanceRepository.class);
        definitions = Mockito.mock(DefinitionRepository.class);
        actRows = Mockito.mock(ActRepository.class);
        acts = Mockito.mock(ActService.class);
        service = new InstanceService(instances, definitions, actRows, acts);
        when(instances.insert(any())).thenAnswer(call -> {
            Object row = call.getArgument(0);
            inserted.add(row);
            if (row instanceof Instance instance) {
                instance.id = INSTANCE;
            }
            if (row instanceof FieldValue written) {
                FieldValue stored = Mockito.mock(FieldValue.class);
                when(stored.id()).thenReturn(500L);
                when(stored.controlFieldId()).thenReturn(written.controlFieldId());
                when(stored.value()).thenReturn(written.value());
                return stored;
            }
            return row;
        });
        when(actRows.lastInstanceAt(SCOPE, INSTANCE)).thenReturn(Optional.of(TOKEN));
        DefinitionVersion bound = version(false);
        when(definitions.version(SCOPE, VERSION)).thenReturn(Optional.of(bound));
        DefinitionRepository.Declared declared = declared(false);
        when(definitions.seeded(SCOPE, SELECTOR, null)).thenReturn(Optional.of(declared));
        when(definitions.bound(SCOPE, VERSION)).thenReturn(Optional.of(declared));
    }

    // -----------------------------------------------------------------------
    // start
    // -----------------------------------------------------------------------

    @Test
    void a_start_takes_the_selectors_next_number_and_writes_its_act_and_rows() {
        Selector counter = Mockito.mock(Selector.class);
        when(counter.takeNumber()).thenReturn(4L);
        when(instances.lockSelector(SCOPE, SELECTOR)).thenReturn(Optional.of(counter));
        Act start = act(900L);
        when(acts.write(eq(SCOPE), eq(INSTANCE), eq(Act.Kind.START), eq(Act.State.RUNNING),
            isNull(), eq(PROVENANCE))).thenReturn(start);

        InstanceView view = service.start(CONSOLE, SLUG, SCOPE, SELECTOR,
            new InstanceService.Start("a title", "the input", "trigger-ref-1",
                List.of("item-a", "item-a", "item-b"), List.of("ref-a"),
                Map.of("amount", 3)), PROVENANCE);

        assertThat(view.address().complete())
            .as("the instance takes the number the counter handed out")
            .isEqualTo("dispatch://probe/sprint/4");
        assertThat(List.of(view.state(), view.conflictToken(), view.version()))
            .as("running, under the time of its last act, bound to the version in force")
            .containsExactly("running", TOKEN.toString(), 2);
        assertThat(view.open()).as("and every call on it is open to the console")
            .containsExactly(InstanceCall.values());
        assertThat(inserted).extracting(row -> row.getClass().getSimpleName())
            .as("the instance, its input, its trigger, each distinct reference once, and the "
                + "field value with its projection, in this order")
            .containsExactly("Instance", "Content", REFERENCE_ROW, REFERENCE_ROW, REFERENCE_ROW,
                REFERENCE_ROW, "FieldValue", "InstanceFieldValue");
    }

    @Test
    void a_start_without_an_active_seeded_version_takes_no_number_and_writes_nothing() {
        when(definitions.seeded(SCOPE, SELECTOR, null)).thenReturn(Optional.empty());
        InstanceService.Start start = new InstanceService.Start("t", null, null, List.of(),
            List.of(), Map.of());

        assertThatThrownBy(() -> service.start(CONSOLE, SLUG, SCOPE, SELECTOR, start,
                PROVENANCE))
            .isInstanceOfSatisfying(DispatchException.class, refused ->
                assertThat(refused.reason()).isEqualTo(DispatchException.Reason.NOT_FOUND));
        verify(instances, never()).lockSelector(any(), any());
        assertThat(inserted).isEmpty();
    }

    @Test
    void a_start_refused_by_its_contract_takes_no_number() {
        InstanceService.Start start = new InstanceService.Start("t", null, null, List.of(),
            List.of(), Map.of());

        assertThatThrownBy(() -> service.start(CONSOLE, SLUG, SCOPE, SELECTOR, start,
                PROVENANCE))
            .isInstanceOfSatisfying(DispatchException.class, refused ->
                assertThat(refused.reason())
                    .isEqualTo(DispatchException.Reason.CONTRACT_FIELD_MISSING));
        verify(instances, never()).lockSelector(any(), any());
    }

    // -----------------------------------------------------------------------
    // the closings
    // -----------------------------------------------------------------------

    @Test
    void a_closed_instance_is_refused_with_its_state_and_no_call_open() {
        Instance closed = planted();
        closed.close(Instance.Resolution.WITHDRAWN);
        when(instances.lockInstance(SCOPE, SELECTOR, 4)).thenReturn(Optional.of(closed));

        assertThatThrownBy(() -> service.close(CONSOLE, ADDRESS, SCOPE,
                Instance.Resolution.CONCLUDED, closing(TOKEN.toString(), null), PROVENANCE))
            .isInstanceOfSatisfying(DispatchException.class, refused -> {
                assertThat(refused.reason()).isEqualTo(DispatchException.Reason.INSTANCE_CLOSED);
                assertThat(refused.standing().orElseThrow().open()).isEmpty();
                assertThat(refused.standing().orElseThrow().state()).isEqualTo("closed");
            });
        verify(acts, never()).write(any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void a_closing_without_a_token_or_with_a_stale_one_is_refused_before_it_writes() {
        when(instances.lockInstance(SCOPE, SELECTOR, 4)).thenReturn(Optional.of(planted()));

        assertThatThrownBy(() -> service.close(CONSOLE, ADDRESS, SCOPE,
                Instance.Resolution.CONCLUDED, closing(null, null), PROVENANCE))
            .isInstanceOfSatisfying(DispatchException.class, refused -> assertThat(
                refused.reason()).isEqualTo(DispatchException.Reason.CONFLICT_TOKEN_MISSING));
        assertThatThrownBy(() -> service.close(CONSOLE, ADDRESS, SCOPE,
                Instance.Resolution.CONCLUDED, closing("2026-11-24T07:59:59Z", null), PROVENANCE))
            .isInstanceOfSatisfying(DispatchException.class, refused -> {
                assertThat(refused.reason())
                    .isEqualTo(DispatchException.Reason.CONFLICT_TOKEN_STALE);
                assertThat(refused.standing().orElseThrow().conflictToken())
                    .as("naming the current token").isEqualTo(TOKEN.toString());
            });
        verify(acts, never()).write(any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void unfinished_tasks_are_refused_first_and_withdrawn_after_the_closing_act_on_confirmation() {
        when(instances.lockInstance(SCOPE, SELECTOR, 4)).thenReturn(Optional.of(planted()));
        Task first = task(11L, 1);
        Task second = task(12L, 2);
        when(instances.lockUnfinishedTasks(SCOPE, INSTANCE)).thenReturn(List.of(first, second));

        String[] handedOut = new String[1];
        assertThatThrownBy(() -> service.close(CONSOLE, ADDRESS, SCOPE,
                Instance.Resolution.CONCLUDED, closing(TOKEN.toString(), null), PROVENANCE))
            .isInstanceOfSatisfying(DispatchException.class, refused -> {
                assertThat(refused.reason())
                    .isEqualTo(DispatchException.Reason.CONFIRMATION_REQUIRED);
                assertThat(refused.offenders()).as("each unfinished task by its address")
                    .containsExactly("dispatch://probe/sprint/4.1", "dispatch://probe/sprint/4.2");
                handedOut[0] = refused.confirmation().orElseThrow();
            });
        verify(acts, never()).write(any(), anyLong(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.close(CONSOLE, ADDRESS, SCOPE,
                Instance.Resolution.WITHDRAWN, closing(TOKEN.toString(), handedOut[0]),
                PROVENANCE))
            .as("a confirmation handed out for the conclude does not withdraw")
            .isInstanceOfSatisfying(DispatchException.class, refused ->
                assertThat(refused.reason())
                    .isEqualTo(DispatchException.Reason.CONFIRMATION_STALE));

        Act closingAct = act(901L);
        when(acts.write(SCOPE, INSTANCE, Act.Kind.CONCLUDE, Act.State.CLOSED, null, PROVENANCE))
            .thenReturn(closingAct);
        InstanceView closed = service.close(CONSOLE, ADDRESS, SCOPE,
            Instance.Resolution.CONCLUDED, closing(TOKEN.toString(), handedOut[0]), PROVENANCE);

        InOrder order = Mockito.inOrder(acts, first, second);
        order.verify(acts).write(SCOPE, INSTANCE, Act.Kind.CONCLUDE, Act.State.CLOSED, null,
            PROVENANCE);
        order.verify(acts).write(SCOPE, INSTANCE, Act.Kind.WITHDRAW, Act.State.CLOSED, 11L,
            PROVENANCE, 901L);
        order.verify(first).withdraw();
        order.verify(acts).write(SCOPE, INSTANCE, Act.Kind.WITHDRAW, Act.State.CLOSED, 12L,
            PROVENANCE, 901L);
        order.verify(second).withdraw();
        assertThat(List.of(closed.state(), String.valueOf(closed.resolution())))
            .as("then the instance is closed").containsExactly("closed", "concluded");
        assertThat(inserted).extracting(row -> row.getClass().getSimpleName())
            .as("with its return and the text of its return")
            .containsExactly("InstanceReturn", "Content");
    }

    // -----------------------------------------------------------------------
    // references
    // -----------------------------------------------------------------------

    @Test
    void a_reference_the_instance_carries_is_set_once_and_one_it_lacks_is_not_ended() {
        when(instances.lockInstance(SCOPE, SELECTOR, 4)).thenReturn(Optional.of(planted()));
        when(instances.currentReference(SCOPE, INSTANCE, ExternalReference.Kind.ITEM, "a"))
            .thenReturn(Optional.of(Mockito.mock(ExternalReference.class)));

        service.attach(CONSOLE, ADDRESS, SCOPE, ExternalReference.Kind.ITEM, "a", PROVENANCE);
        verify(acts, never()).write(any(), anyLong(), any(), any(), any(), any());

        assertThatThrownBy(() -> service.detach(CONSOLE, ADDRESS, SCOPE,
                ExternalReference.Kind.REFERENCE, "a", PROVENANCE))
            .isInstanceOfSatisfying(DispatchException.class, refused -> {
                assertThat(refused.reason()).isEqualTo(DispatchException.Reason.REFERENCE_ABSENT);
                assertThat(refused.offenders()).containsExactly("fields.value");
            });

        ExternalReference held = Mockito.mock(ExternalReference.class);
        when(instances.currentReference(SCOPE, INSTANCE, ExternalReference.Kind.REFERENCE, "b"))
            .thenReturn(Optional.of(held));
        Act unrefer = act(902L);
        when(acts.write(SCOPE, INSTANCE, Act.Kind.UNREFER, Act.State.RUNNING, null, PROVENANCE))
            .thenReturn(unrefer);
        service.detach(CONSOLE, ADDRESS, SCOPE, ExternalReference.Kind.REFERENCE, "b",
            PROVENANCE);
        verify(held).endBy(902L);
    }

    // -----------------------------------------------------------------------

    private static Instance planted() {
        Instance instance = Instance.started(SCOPE, 3L, 4L, VERSION, "a title");
        instance.id = INSTANCE;
        return instance;
    }

    private static InstanceService.Closing closing(@Nullable String token,
                                                   @Nullable String confirmation) {
        return new InstanceService.Closing(token, confirmation, "the return", null, null, null);
    }

    private static Act act(long id) {
        Act act = Mockito.mock(Act.class);
        when(act.id()).thenReturn(id);
        return act;
    }

    private static Task task(long id, int sub) {
        Task task = Mockito.mock(Task.class);
        when(task.id()).thenReturn(id);
        when(task.sub()).thenReturn(sub);
        return task;
    }

    private static DefinitionVersion version(boolean verdictRequired) {
        DefinitionVersion version = Mockito.mock(DefinitionVersion.class);
        when(version.id()).thenReturn(VERSION);
        when(version.selectorId()).thenReturn(3L);
        when(version.version()).thenReturn(2);
        when(version.verdictRequired()).thenReturn(verdictRequired);
        return version;
    }

    /** A version with a start contract of one required number, {@code amount}. */
    private static DefinitionRepository.Declared declared(boolean verdictRequired) {
        ControlField amount = Mockito.mock(ControlField.class);
        when(amount.id()).thenReturn(1L);
        when(amount.name()).thenReturn("amount");
        when(amount.type()).thenReturn("number");
        when(amount.enumerationId()).thenReturn(Optional.empty());
        ContractField row = Mockito.mock(ContractField.class);
        when(row.activityId()).thenReturn(Optional.empty());
        when(row.controlFieldId()).thenReturn(1L);
        when(row.required()).thenReturn(true);
        return new DefinitionRepository.Declared(version(verdictRequired), List.of(), List.of(),
            List.of(), List.of(), List.of(amount), List.of(row));
    }
}
