package ai.kumbuka.dispatch.api;

import ai.kumbuka.dispatch.domain.Actor;
import ai.kumbuka.dispatch.domain.DispatchException;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How the capacity is derived from a token, and what is refused rather than
 * defaulted.
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

        assertThat(actor.kind()).isEqualTo(Actor.Kind.EXECUTOR);
        assertThat(actor.subject())
            .as("authorship is derived from the token's stable subject and never accepted "
                + "from a caller, so a surface that read it from a body would let one "
                + "caller sign another's name to a transition")
            .isEqualTo("an-agent");
    }

    @Test
    void a_console_role_yields_the_console_capacity() {
        assertThat(actorFor("an-operator", Actor.ROLE_CONSOLE).kind())
            .isEqualTo(Actor.Kind.CONSOLE);
    }

    @Test
    void a_token_carrying_neither_capacity_is_refused_rather_than_defaulted() {
        assertThatThrownBy(() -> actorFor("a-stranger"))
            .isInstanceOf(DispatchException.class)
            .extracting(e -> ((DispatchException) e).reason())
            .isEqualTo(DispatchException.Reason.ACTOR_UNKNOWN);
    }

    /**
     * Both roles is refused, and it is not a superset.
     *
     * <p>An identity that is an executor <em>and</em> a console would pass the
     * ratification bolt while also being the thing the bolt exists to stop.
     * The two are capacities, not levels, so holding both is a configuration
     * error rather than a broader permission.
     */
    @Test
    void a_token_carrying_both_capacities_is_refused_because_they_are_not_levels() {
        assertThatThrownBy(() -> actorFor("a-hybrid", Actor.ROLE_EXECUTOR, Actor.ROLE_CONSOLE))
            .isInstanceOf(DispatchException.class)
            .hasMessageContaining("both");
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
