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
import ai.kumbuka.dispatch.domain.DispatchPermission;
import io.quarkus.security.StringPermission;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How the actor is derived from the identity: its subject, and the
 * permissions of this service the identity holds, whoever handed them to it;
 * and what is refused rather than defaulted.
 *
 * <p>The identities here hold permissions directly, as the platform's
 * resolver will hand them over, and carry no role: the actor reads no role.
 * A default would decide a permission by omission, and the omission would be
 * invisible.
 */
class CallerActorTest {

    @Test
    void the_actor_holds_the_permissions_the_identity_holds_and_the_tokens_subject() {
        Actor actor = actorFor("an-agent", List.of("dispatch.task.execute",
            "dispatch.instance.read", "dispatch.instance.read-text-as-holder"));

        assertThat(actor.permissions()).containsExactlyInAnyOrder(
            DispatchPermission.TASK_EXECUTE, DispatchPermission.INSTANCE_READ,
            DispatchPermission.INSTANCE_READ_TEXT_AS_HOLDER);
        assertThat(actor.subject())
            .as("authorship is derived from the token's stable subject and never accepted "
                + "from a caller, so a surface that read it from a body would let one "
                + "caller sign another's name to a transition")
            .isEqualTo("an-agent");
    }

    @Test
    void a_permission_outside_the_catalogue_is_not_carried() {
        Actor actor = actorFor("an-operator", List.of("dispatch.task.commission",
            "worklist.item.write"));

        assertThat(actor.permissions()).containsExactly(DispatchPermission.TASK_COMMISSION);
    }

    @Test
    void an_identity_holding_no_permission_of_the_service_is_refused_rather_than_defaulted() {
        assertThatThrownBy(() -> actorFor("a-stranger", List.of()))
            .isInstanceOf(DispatchException.class)
            .extracting(e -> ((DispatchException) e).reason())
            .isEqualTo(DispatchException.Reason.ACTOR_UNKNOWN);
    }

    /**
     * A realm role is no permission: an identity that carries the console's
     * role and holds no permission is refused, because the actor reads the
     * permissions and never a role (DEC-0052).
     */
    @Test
    void a_role_without_a_permission_is_no_permission() {
        var identity = QuarkusSecurityIdentity.builder()
            .setPrincipal(new QuarkusPrincipal("a-role-only"))
            .addRole("dispatch-console")
            .addPermissionChecker(required -> Uni.createFrom().item(false));
        CallerActor caller = new CallerActor();
        caller.identity = identity.build();

        assertThatThrownBy(caller::current)
            .isInstanceOf(DispatchException.class)
            .extracting(e -> ((DispatchException) e).reason())
            .isEqualTo(DispatchException.Reason.ACTOR_UNKNOWN);
    }

    /** An identity of {@code subject} holding exactly the permissions named. */
    private static Actor actorFor(String subject, List<String> held) {
        List<StringPermission> permissions = held.stream().map(StringPermission::new).toList();
        var identity = QuarkusSecurityIdentity.builder()
            .setPrincipal(new QuarkusPrincipal(subject))
            .addPermissionChecker(required -> Uni.createFrom().item(
                permissions.stream().anyMatch(p -> p.implies(required))));

        CallerActor caller = new CallerActor();
        caller.identity = identity.build();
        return caller.current();
    }
}
