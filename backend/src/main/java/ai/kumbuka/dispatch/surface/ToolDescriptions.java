/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import io.quarkus.qute.i18n.Message;
import io.quarkus.qute.i18n.MessageBundle;

/**
 * The description of every call on the assistant surface, at most {@link
 * SurfaceDeclaration#DESCRIPTION_BUDGET} characters each, worded as the act
 * (DEC-0043 R3, R21).
 *
 * <p>One per declared call, and none for a call the service does not offer: a
 * call's description comes back with the change that declares it.
 */
@MessageBundle("tool")
public interface ToolDescriptions {

    @Message
    String readDefinition();

    @Message
    String start();

    @Message
    String conclude();

    @Message
    String withdraw();

    @Message
    String referenceAttach();

    @Message
    String referenceDetach();

    @Message
    String create();

    @Message
    String update();

    @Message
    String delete();

    @Message
    String send();

    @Message
    String claim();

    @Message
    String renew();

    @Message
    String release();

    @Message
    String defer();
}
