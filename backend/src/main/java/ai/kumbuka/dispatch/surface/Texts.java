/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import io.quarkus.qute.i18n.MessageBundles;

/**
 * The one way to a text addressed to a caller: every bundle, by its kind.
 *
 * <p>Every text the surface addresses to a caller lives in a Qute
 * type-safe message bundle (service-construction, "texts addressed to a
 * caller"), one interface per kind of text, with the texts in {@code
 * src/main/resources/messages/}. One set of texts per component: there is
 * no variant per deployment, tenant or call, and no localized bundle.
 *
 * <p>Read when a text is needed, never at class initialisation: the bundles
 * are built with the application and are reachable once it runs.
 */
public final class Texts {

    private Texts() {
    }

    /** The message of every refusal. */
    public static RefusalMessages refusals() {
        return MessageBundles.get(RefusalMessages.class);
    }

    /** The remedy of every refusal reason. */
    public static RemedyTexts remedies() {
        return MessageBundles.get(RemedyTexts.class);
    }

    /** What each step of next does. */
    public static NextTexts next() {
        return MessageBundles.get(NextTexts.class);
    }

    /** What a task waits for. */
    public static NoticeTexts notices() {
        return MessageBundles.get(NoticeTexts.class);
    }

    /** The description of every call. */
    public static ToolTexts tools() {
        return MessageBundles.get(ToolTexts.class);
    }

    /** The description of every argument. */
    public static ArgumentTexts arguments() {
        return MessageBundles.get(ArgumentTexts.class);
    }

    /** The description of every value of a closed set. */
    public static ValueTexts values() {
        return MessageBundles.get(ValueTexts.class);
    }

    /** The errors of the protocol this service words itself. */
    public static ProtocolMessages protocol() {
        return MessageBundles.get(ProtocolMessages.class);
    }
}
