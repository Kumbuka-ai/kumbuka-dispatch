/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.surface.InstanceWire;
import ai.kumbuka.dispatch.surface.TaskWire;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The conflict token of a draft is its {@code changed_at} at microsecond
 * precision, written by the application from the injected clock as {@code
 * max(now, previous + 1 µs)} on every create and every update that changes the
 * draft, under the lock of its row, and left as it is by an update that
 * changes nothing (C10.1, DEC-0041, REA-0010 D29).
 *
 * <p>The clock is the test's: {@link SettableClock} holds one instant, so two
 * writes fall into the same microsecond by construction and the expected
 * tokens are written here from that instant, not read from the service. The
 * store's column is read back by SQL beside the token the answer hands out.
 */
@QuarkusTest
@TestProfile(SettableClock.Profile.class)
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ConflictTokenIT {

    private static final Instant STILL = Instant.parse("2032-05-06T07:08:09.123456Z");
    private static final Actor CONSOLE = new Actor(InstanceWire.SUBJECT,
        DispatchPermission.TASK_COMMISSION, DispatchPermission.INSTANCE_START);
    private static final Provenance PROVENANCE = new Provenance(InstanceWire.SUBJECT, null,
        DispatchPermission.TASK_COMMISSION, Provenance.Path.INTERFACE, null);

    @Inject TaskService tasks;
    @Inject InstanceService instances;
    @Inject SettableClock clock;

    @Test
    void two_updates_in_the_same_microsecond_yield_two_rising_tokens() {
        clock.set(STILL);
        TaskView draft = create("same-microsecond");

        TaskView first = update(draft, "first", null);
        TaskView second = update(first, "second", null);

        assertThat(clock.instant())
            .as("RED STATE, observed: the clock stood still through the create and both "
                + "updates, so the clock alone would give all three the one token")
            .isEqualTo(STILL);
        assertThat(List.of(draft.conflictToken(), first.conflictToken(), second.conflictToken()))
            .as("the create takes the clock's instant, and each update one microsecond more")
            .containsExactly(STILL.toString(), STILL.plusNanos(1000).toString(),
                STILL.plusNanos(2000).toString());
        assertThat(changedAt(second)).as("as the store holds it")
            .isEqualTo(STILL.plusNanos(2000));
    }

    @Test
    void the_token_follows_the_clock_and_never_returns_to_an_earlier_value() {
        clock.set(STILL.plusSeconds(60));
        TaskView draft = create("follows");

        clock.set(STILL.plusSeconds(120));
        TaskView later = update(draft, "later", null);
        clock.set(STILL.plusSeconds(30));
        TaskView setBack = update(later, "set back", null);

        assertThat(later.conflictToken()).as("the clock moved on, and the token with it")
            .isEqualTo(STILL.plusSeconds(120).toString());
        assertThat(setBack.conflictToken())
            .as("the clock was set back, and the token rose by one microsecond instead")
            .isEqualTo(STILL.plusSeconds(120).plusNanos(1000).toString());
    }

    @Test
    void an_update_that_changes_nothing_keeps_the_token() {
        clock.set(STILL.plusSeconds(180));
        TaskView draft = create("unchanged");
        clock.set(STILL.plusSeconds(240));

        TaskView same = update(draft, "a title", null);

        assertThat(same.conflictToken()).as("the title it already has: nothing changed")
            .isEqualTo(draft.conflictToken());
        assertThat(update(same, "another title", null).conflictToken())
            .as("RED STATE, observed: another title moves it")
            .isEqualTo(STILL.plusSeconds(240).toString());
    }

    @Test
    void a_change_of_the_text_alone_moves_the_token() {
        clock.set(STILL.plusSeconds(300));
        TaskView draft = create("text-alone");
        clock.set(STILL.plusSeconds(360));

        TaskView changed = update(draft, null, "a new text");

        assertThat(changed.conflictToken()).as("the text is the draft's, and changes it")
            .isEqualTo(STILL.plusSeconds(360).toString());
        assertThat(update(changed, null, "a new text").conflictToken())
            .as("and the same text again changes nothing").isEqualTo(changed.conflictToken());
    }

    // -----------------------------------------------------------------------

    private TaskView create(String prefix) {
        String selector = TaskWire.plant(prefix, "review").selector();
        InstanceAddress instance = instances.start(CONSOLE, InstanceWire.SLUG, InstanceWire.SCOPE,
            selector, new InstanceService.Start("an instance", null, null, List.of(), List.of(),
                Map.of()), new Provenance(InstanceWire.SUBJECT, null,
                DispatchPermission.INSTANCE_START, Provenance.Path.INTERFACE, null)).address();
        return tasks.create(CONSOLE, InstanceWire.SLUG, InstanceWire.SCOPE, selector,
            new TaskService.Create(instance, new TaskService.Drafting("a title", null, null, null,
                List.of(), null, null), null, List.of(), IdempotencyKey.NONE), PROVENANCE);
    }

    private TaskView update(TaskView standing, @Nullable String title, @Nullable String text) {
        return tasks.update(CONSOLE, standing.address(), InstanceWire.SCOPE,
            new TaskService.Update(standing.conflictToken(), title, null, null, null, null, null,
                text, Set.of()));
    }

    private static Instant changedAt(TaskView task) {
        return ((Timestamp) InstanceWire.rows("SELECT changed_at FROM dispatch.draft "
            + "WHERE task_id = ?", task.id()).get(0).get("changed_at")).toInstant();
    }
}
