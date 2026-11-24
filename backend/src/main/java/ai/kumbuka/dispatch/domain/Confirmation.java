/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;

/**
 * The confirmation that closes an instance with unfinished tasks (TAR-0004
 * 2.3, REA-0010 S5): computed from the set of its unfinished tasks and stored
 * nowhere.
 *
 * <p>The same set gives the same confirmation on every call and host, and
 * another set gives another, so a confirmation handed out for one set closes
 * nothing once the set changed. It is computed for the closing it is handed
 * out for, so a confirmation of a {@code withdraw} does not conclude.
 */
final class Confirmation {

    private Confirmation() {
    }

    /**
     * The confirmation of a closing of an instance over its unfinished tasks.
     *
     * @param closing the kind of the closing act
     * @param tasks   the surrogates of the unfinished tasks, in any order
     */
    static String of(Act.Kind closing, long instanceId, List<Long> tasks) {
        StringBuilder canonical = new StringBuilder()
            .append(closing.wireName()).append(':').append(instanceId);
        tasks.stream().sorted().forEach(task -> canonical.append(':').append(task));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException absent) {
            throw new IllegalStateException("every Java platform carries SHA-256", absent);
        }
    }
}
