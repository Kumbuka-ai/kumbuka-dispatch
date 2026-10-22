/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.domain.Actor;
import ai.kumbuka.dispatch.domain.DispatchException;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How the capacities are derived from a token, and what is refused rather
 * than defaulted.
 *
 * <p>The two guarantees hanging off the capacity are permissions rather than
 * conveniences, so every way of arriving without a clear one has to be a
 * refusal. A default here would decide one of them by omission — and the
 * omission would be invisible, because the caller would simply be treated as
 * whichever capacity the default named.
 */
class CallerActorTest {

    @Test
    void an_executor_role_yields_the_executing_capacity_and_the_tokens_subject() {
        Actor actor = actorFor("an-agent", Actor.ROLE_EXECUTOR);

        assertThat(actor.capacities()).containsExactly(Actor.Kind.EXECUTOR);
        assertThat(actor.subject())
            .as("authorship is derived from the token's stable subject and never accepted "
                + "from a caller, so a surface that read it from a body would let one "
                + "caller sign another's name to a transition")
            .isEqualTo("an-agent");
    }

    @Test
    void a_console_role_yields_the_console_capacity() {
        assertThat(actorFor("an-operator", Actor.ROLE_CONSOLE).capacities())
            .containsExactly(Actor.Kind.CONSOLE);
    }

    @Test
    void a_token_carrying_neither_capacity_is_refused_rather_than_defaulted() {
        assertThatThrownBy(() -> actorFor("a-stranger"))
            .isInstanceOf(DispatchException.class)
            .extracting(e -> ((DispatchException) e).reason())
            .isEqualTo(DispatchException.Reason.ACTOR_UNKNOWN);
    }

    /**
     * Both roles yield both capacities (TAR-0004 section 3, REQ-0034).
     *
     * <p>The two are capacities granted to an identity, not kinds of caller:
     * the person who works alone, or through assistants under the person's own
     * login, holds both and takes whichever part a call asks for. Four-eyes
     * comes from granting one without the other, not from refusing the token
     * that carries both.
     */
    @Test
    void a_token_carrying_both_roles_yields_both_capacities() {
        Actor actor = actorFor("a-person-alone", Actor.ROLE_EXECUTOR, Actor.ROLE_CONSOLE);

        assertThat(actor.isExecutor()).as("the executing capacity").isTrue();
        assertThat(actor.isConsole()).as("the commissioning capacity").isTrue();
        assertThat(actor.subject()).isEqualTo("a-person-alone");
    }

    private static Actor actorFor(String subject, String... roles) {
        var identity = QuarkusSecurityIdentity.builder()
            .setPrincipal(new QuarkusPrincipal(subject));
        for (String role : roles) {
            identity.addRole(role);
        }

        CallerActor caller = new CallerActor();
        caller.identity = identity.build();
        return caller.current();
    }
}
