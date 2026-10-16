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
 */
@MessageBundle("tool")
public interface ToolDescriptions {

    @Message
    String send();

    @Message
    String claim();

    @Message
    String claimNext();

    @Message
    String release();

    @Message
    String defer();

    @Message
    String renew();

    @Message
    String ask();

    @Message
    String answer();

    @Message
    String hold();

    @Message
    String resume();

    @Message
    String deliver();

    @Message
    String rework();

    @Message
    String accept();

    @Message
    String reject();

    @Message
    String fail();

    @Message
    String withdraw();

    @Message
    String create();

    @Message
    String update();

    @Message
    String delete();

    @Message
    String read();

    @Message
    String readText();

    @Message
    String query();

    @Message
    String annotate();

    @Message
    String relate();

    @Message
    String unrelate();
}
