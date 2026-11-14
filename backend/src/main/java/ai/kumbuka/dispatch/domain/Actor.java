/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Who is calling, and which permissions of this service the caller holds.
 *
 * <p>The permissions are the caller's, granted outside the service; nothing
 * here knows a role (DEC-0052). Two of this service's guarantees hang off
 * them rather than off a convenience: an identity without the permission to
 * confirm cannot ratify, and one that may read a text only as its holder
 * cannot obtain the body of a commission it has not claimed. Both are
 * checked at the adapter and again in the core, so they hold for every caller
 * that reaches the core, including the next adapter nobody has written yet.
 *
 * <p>The permissions are granted to an identity; they are not kinds of
 * caller (TAR-0004 section 3). One identity may hold every one: the person who
 * works alone, or through assistants acting under the person's own login,
 * takes whichever part a call asks for. Four-eyes comes from what is granted,
 * never from the kind of the caller, which over this surface is not
 * observable (REQ-0034).
 *
 * @param subject     the token's stable subject. Authorship is derived from it and
 *                    never accepted from a caller.
 * @param permissions the permissions of this service the caller holds; never empty
 */
public record Actor(String subject, Set<DispatchPermission> permissions) {

    public Actor {
        if (subject == null || subject.isBlank()) {
            throw new DispatchException(DispatchException.Reason.ACTOR_UNKNOWN,
                "an actor must carry a subject. Authorship is derived from the token and "
                    + "never accepted from a caller, so a call with no subject has nobody "
                    + "to attribute and cannot be recorded.");
        }
        if (permissions == null || permissions.isEmpty()
                || permissions.stream().anyMatch(Objects::isNull)) {
            throw new DispatchException(DispatchException.Reason.ACTOR_UNKNOWN,
                "an actor must hold a permission of this service. Defaulting one would "
                    + "decide a permission by omission.");
        }
        permissions = Set.copyOf(EnumSet.copyOf(permissions));
    }

    /**
     * An actor holding the permissions named.
     *
     * @throws DispatchException {@code ACTOR_UNKNOWN} when none is named
     */
    public Actor(String subject, DispatchPermission... permissions) {
        this(subject, new HashSet<>(Arrays.asList(permissions)));
    }

    /** Whether the caller holds {@code permission}. */
    public boolean holds(DispatchPermission permission) {
        return permissions.contains(permission);
    }
}
