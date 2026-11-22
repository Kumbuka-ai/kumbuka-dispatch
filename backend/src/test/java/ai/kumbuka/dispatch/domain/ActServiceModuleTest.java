/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.repository.ActRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The writer of acts against a mocked repository: the number it hands out,
 * the order of lock and read it hands it out in, the time it takes, and what
 * it writes of what it is handed.
 *
 * <p>That two writers on one instance never take one number is the store's
 * and the lock's, observed in {@code ActServiceIT}; here the writer is held to
 * asking for the lock before it reads the sequence.
 */
class ActServiceModuleTest {

    private static final UUID SCOPE = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final long INSTANCE = 41L;
    private static final Instant NOW = Instant.parse("2026-11-22T10:15:30.123456Z");

    private ActRepository repository;
    private ActService writer;

    @BeforeEach
    void wire() {
        repository = Mockito.mock(ActRepository.class);
        writer = new ActService(repository, Clock.fixed(NOW, ZoneOffset.UTC));
        Instance instance = new Instance();
        instance.id = INSTANCE;
        instance.scopeId = SCOPE;
        when(repository.lockInstance(SCOPE, INSTANCE)).thenReturn(Optional.of(instance));
        when(repository.append(any(Act.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    void an_act_takes_the_number_after_the_last_of_its_instance_and_the_time_of_the_clock() {
        when(repository.lastSequence(SCOPE, INSTANCE)).thenReturn(6);

        Act written = writer.write(SCOPE, INSTANCE, Act.Kind.CLAIM, Act.State.ACTIVE, 7L,
            new Provenance("delegate-of-ada", "ada", DispatchPermission.TASK_EXECUTE,
                Provenance.Path.INTERFACE, null));

        assertThat(List.of(written.sequence(), written.at(), written.instanceId(),
                written.scopeId(), written.taskId().orElseThrow(), written.kind(),
                written.toState()))
            .as("the seventh act of an instance with six, at the instant of the service's "
                + "clock, on the instance and task it was handed, of the kind and to the state "
                + "named as the store spells them")
            .containsExactly(7, NOW, INSTANCE, SCOPE, 7L, "claim", "active");
        assertThat(List.of(written.actorSubject(), written.actorOwnerSubject().orElseThrow(),
                written.permission().orElseThrow(), written.path().orElseThrow(),
                written.authenticationLevel().isPresent()))
            .as("with the provenance it was handed, unchecked and unchanged")
            .containsExactly("delegate-of-ada", "ada", "dispatch.task.execute", "interface",
                false);
    }

    @Test
    void the_first_act_of_an_instance_takes_number_one() {
        when(repository.lastSequence(SCOPE, INSTANCE)).thenReturn(0);

        Act written = writer.write(SCOPE, INSTANCE, Act.Kind.START, Act.State.RUNNING, null,
            new Provenance("ada", null, DispatchPermission.INSTANCE_START,
                Provenance.Path.CONSOLE, "step-up"));

        assertThat(List.of(written.sequence(), written.taskId().isPresent(),
                written.actorOwnerSubject().isPresent(), written.path().orElseThrow(),
                written.authenticationLevel().orElseThrow()))
            .as("the first act is number 1, about no task, by a person acting directly in the "
                + "console at the authentication level of the session")
            .containsExactly(1, false, false, "console", "step-up");
    }

    @Test
    void the_lock_on_the_instance_is_taken_before_the_sequence_is_read_and_the_act_appended() {
        when(repository.lastSequence(SCOPE, INSTANCE)).thenReturn(2);

        writer.write(SCOPE, INSTANCE, Act.Kind.REFER, Act.State.RUNNING, null,
            new Provenance("ada", null, DispatchPermission.INSTANCE_START,
                Provenance.Path.CONSOLE, null));

        InOrder order = Mockito.inOrder(repository);
        order.verify(repository).lockInstance(SCOPE, INSTANCE);
        order.verify(repository).lastSequence(SCOPE, INSTANCE);
        ArgumentCaptor<Act> appended = ArgumentCaptor.forClass(Act.class);
        order.verify(repository).append(appended.capture());
        assertThat(appended.getValue().sequence())
            .as("the number read under the lock, plus one, is the number appended")
            .isEqualTo(3);
    }

    @Test
    void an_instance_the_scope_does_not_hold_for_the_tenant_is_not_found_and_nothing_is_written() {
        when(repository.lockInstance(SCOPE, 99L)).thenReturn(Optional.empty());
        Provenance provenance = new Provenance("ada", null, DispatchPermission.INSTANCE_START,
            Provenance.Path.CONSOLE, null);

        assertThatThrownBy(() -> writer.write(SCOPE, 99L, Act.Kind.START, Act.State.RUNNING, null,
                provenance))
            .as("no instance, no act: the refusal is the one of an absent object")
            .isInstanceOfSatisfying(DispatchException.class, refused ->
                assertThat(refused.reason()).isEqualTo(DispatchException.Reason.NOT_FOUND));
        verify(repository, never()).lastSequence(any(), Mockito.anyLong());
        verify(repository, never()).append(any());
    }

    @Test
    void a_provenance_without_subject_permission_or_path_is_refused_where_it_is_built() {
        assertThatThrownBy(() -> new Provenance(null, "ada", DispatchPermission.INSTANCE_START,
                Provenance.Path.INTERFACE, null))
            .as("an act without the subject of its actor has nobody to attribute")
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Provenance("ada", null, null, Provenance.Path.CONSOLE, null))
            .as("nor one without the permission its call checked")
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Provenance("ada", null, DispatchPermission.INSTANCE_START,
                null, null))
            .as("nor one without the path it arrived by")
            .isInstanceOf(NullPointerException.class);
    }
}
