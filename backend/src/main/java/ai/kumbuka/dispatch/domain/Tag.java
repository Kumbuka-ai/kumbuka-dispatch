/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One tag of a scope's vocabulary, as the platform publishes it in {@code
 * platform.tag_access}: declared there, never here.
 *
 * <p>A withdrawn tag takes no new task; the tasks that carry it keep it, and
 * it can still be detached and listed by.
 *
 * @param id        the platform's id of the tag
 * @param token     the tag as callers write it
 * @param withdrawn whether the platform withdrew it
 */
public record Tag(UUID id, String token, boolean withdrawn) {

    /** The form of a token, as the platform declares it. */
    public static final Pattern TOKEN_FORM = Pattern.compile("[a-z0-9][a-z0-9:-]{0,62}");

    /** The status the platform writes for a withdrawn tag. */
    public static final String WITHDRAWN_STATUS = "withdrawn";

    /** Whether a text has the form of a token at all. */
    public static boolean isToken(String text) {
        return TOKEN_FORM.matcher(text).matches();
    }
}
