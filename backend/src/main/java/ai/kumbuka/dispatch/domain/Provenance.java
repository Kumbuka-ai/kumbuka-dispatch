/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;

/**
 * Where an act comes from: who made the call, on whose behalf, by which
 * permission, by which path, and at which authentication level.
 *
 * <p>Every act carries it, and it is derived by the server from the verified
 * identity and the path the call arrived by, never accepted from a caller.
 * Nothing here decides whether the permission was held or the path admits the
 * act: the writer records what it is handed (DEC-0053), and the store refuses
 * a combination its checks do not admit, an authentication level on the path
 * {@code interface} among them.
 *
 * @param actorSubject        the subject of the identity that made the call: the
 *                            delegated identity on {@code interface}, the person on
 *                            {@code console}
 * @param actorOwnerSubject   the subject of the person the acting identity belongs
 *                            to; absent where the person acted directly
 * @param permission          the one permission the call checked about its caller
 * @param path                the path the call arrived by
 * @param authenticationLevel the authentication level of the session the call came
 *                            from, as the realm names it; absent where the call
 *                            carried none
 */
public record Provenance(String actorSubject, @Nullable String actorOwnerSubject,
                         DispatchPermission permission, Path path,
                         @Nullable String authenticationLevel) {

    /** The two paths an act arrives by (DEC-0053, REA-0010 step 5). */
    public enum Path {

        /** Over an interface, by a delegated identity. */
        INTERFACE,

        /** In the console, by the person acting directly. */
        CONSOLE;

        /** The path as the store writes it. */
        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    public Provenance {
        Objects.requireNonNull(actorSubject, "actorSubject");
        Objects.requireNonNull(permission, "permission");
        Objects.requireNonNull(path, "path");
    }
}
