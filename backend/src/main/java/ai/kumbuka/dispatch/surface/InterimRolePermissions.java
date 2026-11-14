/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.domain.DispatchPermission;
import io.quarkus.security.StringPermission;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The interim source of this service's permissions: it reads the two realm
 * roles of the caller's token and hands the identity the permissions each of
 * them stands for today. The one place in the service that names a role.
 *
 * <p>An interim on purpose, and the one deliberate departure from the
 * contract's rule that no service reads a role (C15.3): the platform's
 * security resolver will hand the permissions to the identity itself, and
 * this class goes then, whole. Nothing else changes with it: every call
 * checks its permission on the identity, never a role, so the checks do not
 * see which source filled it.
 *
 * <p>It grants exactly what the roles admit today, nothing wider and nothing
 * narrower: a token with both roles holds the union, a token with neither
 * holds no permission of this service.
 */
@ApplicationScoped
public class InterimRolePermissions implements SecurityIdentityAugmentor {

    /** The realm role of the console: commissioning and deciding. */
    static final String CONSOLE_ROLE = "dispatch-console";

    /** The realm role of an executor: taking tasks up and working them. */
    static final String EXECUTOR_ROLE = "dispatch-executor";

    /** What each role stands for. */
    private static final Map<String, Set<DispatchPermission>> GRANTED = Map.of(
        CONSOLE_ROLE, EnumSet.of(
            DispatchPermission.INSTANCE_START,
            DispatchPermission.INSTANCE_CONCLUDE,
            DispatchPermission.INSTANCE_WITHDRAW,
            DispatchPermission.TASK_COMMISSION,
            DispatchPermission.TASK_DECIDE,
            DispatchPermission.TASK_CONFIRM,
            DispatchPermission.TASK_WITHDRAW,
            DispatchPermission.TASK_TAG,
            DispatchPermission.INSTANCE_READ,
            DispatchPermission.INSTANCE_READ_METADATA,
            DispatchPermission.INSTANCE_READ_TEXT),
        EXECUTOR_ROLE, EnumSet.of(
            DispatchPermission.TASK_EXECUTE,
            DispatchPermission.TASK_TAG,
            DispatchPermission.INSTANCE_READ,
            DispatchPermission.INSTANCE_READ_METADATA,
            DispatchPermission.INSTANCE_READ_TEXT_AS_HOLDER));

    @Override
    public Uni<SecurityIdentity> augment(SecurityIdentity identity,
                                         AuthenticationRequestContext context) {
        return Uni.createFrom().item(withPermissions(identity));
    }

    /**
     * The identity with the permissions its roles stand for. The checker is
     * added also where the roles stand for none, so that an identity this
     * source has seen always answers a permission by what was granted, and
     * never by the absence of any checker.
     */
    public static SecurityIdentity withPermissions(SecurityIdentity identity) {
        if (identity.isAnonymous()) {
            return identity;
        }
        List<StringPermission> held = grantedTo(identity.getRoles()).stream()
            .map(permission -> new StringPermission(permission.wireName()))
            .toList();
        return QuarkusSecurityIdentity.builder(identity)
            .addPermissionChecker(required -> Uni.createFrom().item(
                held.stream().anyMatch(permission -> permission.implies(required))))
            .build();
    }

    /** The permissions a set of realm roles stands for: the union of each role's. */
    static Set<DispatchPermission> grantedTo(Set<String> roles) {
        Set<DispatchPermission> granted = EnumSet.noneOf(DispatchPermission.class);
        roles.stream()
            .map(GRANTED::get)
            .filter(Objects::nonNull)
            .forEach(granted::addAll);
        return granted;
    }
}
