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
 * Who is calling, and which capacities the caller holds.
 *
 * <p>Two capacities, because two of this service's guarantees are permissions
 * bound to the caller rather than conveniences: an identity without the
 * commissioning capacity cannot ratify, and it cannot obtain the body of a
 * commission it has not claimed. Both live in the core. An adapter that simply
 * did not project those paths would be positioning, not enforcement — anything
 * that must hold has to hold for every caller that reaches the core, including
 * the next adapter nobody has written yet.
 *
 * <p>The capacities are granted to an identity; they are not kinds of caller
 * (TAR-0004 section 3). One identity may hold both: the person who works
 * alone, or through assistants acting under the person's own login, takes
 * whichever part a call asks for. Four-eyes comes from granting the executing
 * capacity without the commissioning one, never from the kind of the caller,
 * which over this surface is not observable (REQ-0034).
 *
 * <h2>Where the capacities come from, and what is assumed</h2>
 *
 * From the realm roles the service already reads off the access token. Nothing
 * new is invented here: the identity provider is the same one the service is
 * bound to, and the claim path is the one already configured.
 *
 * <p>What IS an assumption, and is recorded as one rather than buried: that
 * the two roles below are the names the realm will carry. The identity of an
 * individual agent is a separate and deliberately open question — nothing here
 * depends on being able to tell two executors apart.
 *
 * @param subject    the token's stable subject. Authorship is derived from it and
 *                   never accepted from a caller.
 * @param capacities the capacities the caller holds; never empty
 */
public record Actor(String subject, Set<Kind> capacities) {

    /** The realm role that grants the executing capacity. */
    public static final String ROLE_EXECUTOR = "dispatch-executor";
    /** The realm role that grants the commissioning capacity. */
    public static final String ROLE_CONSOLE = "dispatch-console";

    public enum Kind {
        /**
         * Takes commissions up and performs them: claims, writes a return
         * draft, terminates what it holds. Alone it may not ratify, and it may
         * not read the body of a commission it has not claimed.
         */
        EXECUTOR,
        /**
         * Commissions and ratifies, and may read a commission body without
         * holding the claim, because operators read and edit returns as a
         * matter of course.
         */
        CONSOLE
    }

    public Actor {
        if (subject == null || subject.isBlank()) {
            throw new DispatchException(DispatchException.Reason.ACTOR_UNKNOWN,
                "an actor must carry a subject. Authorship is derived from the token and "
                    + "never accepted from a caller, so a call with no subject has nobody "
                    + "to attribute and cannot be recorded.");
        }
        if (capacities == null || capacities.isEmpty()
                || capacities.stream().anyMatch(Objects::isNull)) {
            throw new DispatchException(DispatchException.Reason.ACTOR_UNKNOWN,
                "an actor must carry a capacity. Defaulting one would decide a permission "
                    + "by omission, and the two permissions that hang off this are the two "
                    + "that carry a bolt rather than a convenience.");
        }
        capacities = Set.copyOf(EnumSet.copyOf(capacities));
    }

    /**
     * An actor holding the capacities named.
     *
     * @throws DispatchException {@code ACTOR_UNKNOWN} when none is named
     */
    public Actor(String subject, Kind... capacities) {
        this(subject, new HashSet<>(Arrays.asList(capacities)));
    }

    public boolean isExecutor() {
        return capacities.contains(Kind.EXECUTOR);
    }

    public boolean isConsole() {
        return capacities.contains(Kind.CONSOLE);
    }
}
