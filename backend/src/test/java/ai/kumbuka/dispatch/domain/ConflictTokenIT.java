/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import ai.kumbuka.dispatch.tenancy.TenantContext;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static ai.kumbuka.dispatch.domain.TaskStage.C;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The conflict token is the task's {@code updated_at} at microsecond
 * precision, written by the application from the injected clock as {@code
 * max(now, previous + 1 µs)} on every write that changes the task, and left
 * as it is by a write that changes nothing (C10.1, DEC-0041).
 *
 * <p>The clock is the test's: {@link SettableClock} holds one instant, so two
 * writes fall into the same microsecond by construction and the expected
 * tokens are written here from that instant, not read from the service.
 *
 * <p>RED STATE, observed against the tree with the database trigger: the
 * token was the database's transaction time, not the clock's, so the cases on
 * two writes in one microsecond and on a clock set back failed on the first
 * token; the update of the text alone left the token where it was, because
 * the task row was not written. The write that changes nothing kept its token
 * there too, as it must.
 */
@QuarkusTest
@TestProfile(SettableClock.Profile.class)
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ConflictTokenIT {

    static final UUID SCOPE = UUID.fromString("00000000-0000-0000-0000-000000000010");
    static final String SELECTOR = "sprint";

    private static final Instant T = Instant.parse("2031-03-04T05:06:07.123456Z");

    @Inject TaskService tasks;
    @Inject TenantContext tenantContext;
    @Inject SettableClock clock;

    private AutoCloseable binding;

    @BeforeEach
    void freshTenant() {
        UUID tenant = UUID.randomUUID();
        DomainFixture.declareSelector(tenant, SCOPE, SELECTOR);
        binding = tenantContext.bind(tenant);
        clock.set(T);
    }

    @AfterEach
    void unbind() throws Exception {
        binding.close();
    }

    @Test
    void two_writes_in_the_same_microsecond_yield_two_tokens() {
        TaskView created = draft("first");
        assertThat(created.conflictToken())
            .as("the token of a new task is the clock's instant at the microsecond")
            .isEqualTo(token(T));

        TaskView once = rename(created, "second");
        TaskView twice = rename(once, "third");

        assertThat(once.conflictToken())
            .as("a write in the same microsecond moves the token by one microsecond")
            .isEqualTo(token(T.plus(1, ChronoUnit.MICROS)));
        assertThat(twice.conflictToken()).isEqualTo(token(T.plus(2, ChronoUnit.MICROS)));
    }

    @Test
    void the_token_follows_the_clock_and_never_returns_to_an_earlier_value() {
        TaskView created = draft("first");

        Instant later = T.plusSeconds(60);
        clock.set(later);
        TaskView forward = rename(created, "second");
        assertThat(forward.conflictToken()).as("the clock moved on, so does the token")
            .isEqualTo(token(later));

        clock.set(T.minusSeconds(3600));
        TaskView back = rename(forward, "third");
        assertThat(back.conflictToken())
            .as("a clock set back moves the token past the last one, never to the clock")
            .isEqualTo(token(later.plus(1, ChronoUnit.MICROS)));
    }

    @Test
    void a_write_that_changes_nothing_keeps_the_token() {
        TaskView created = draft("unchanged");
        clock.set(T.plusSeconds(5));

        TaskView same = tasks.update(SCOPE, created.address(),
            new TaskService.Draft("unchanged", null, null, null), created.conflictToken(), C);

        assertThat(same.conflictToken())
            .as("the title written is the title stored: nothing changed, neither does the token")
            .isEqualTo(created.conflictToken());
        assertThat(read(created).conflictToken()).isEqualTo(created.conflictToken());
    }

    @Test
    void a_change_of_the_dispatch_text_alone_moves_the_token() {
        TaskView created = draft("texted");

        TaskView changed = tasks.update(SCOPE, created.address(),
            new TaskService.Draft(null, null, "another commission", null),
            created.conflictToken(), C);

        assertThat(changed.conflictToken())
            .as("a draft's text is part of what its token fences; a writer holding the earlier "
                + "token must be refused, so the token moves")
            .isNotEqualTo(created.conflictToken())
            .isEqualTo(token(T.plus(1, ChronoUnit.MICROS)));
        assertThat(read(created).conflictToken()).isEqualTo(changed.conflictToken());
    }

    // -----------------------------------------------------------------------

    private TaskView draft(String title) {
        return tasks.create(SCOPE, SELECTOR, null,
            new TaskService.Draft(title, "code", "the commission", null), C, IdempotencyKey.NONE);
    }

    private TaskView rename(TaskView task, String title) {
        return tasks.update(SCOPE, task.address(), new TaskService.Draft(title, null, null, null),
            task.conflictToken(), C);
    }

    private TaskView read(TaskView task) {
        return tasks.read(SCOPE, task.address(), C);
    }

    /** The token an instant renders as: ISO-8601, UTC, cut to the microsecond. */
    private static String token(Instant at) {
        return at.truncatedTo(ChronoUnit.MICROS).toString();
    }
}
