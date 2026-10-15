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
 * The description of every value of a closed set of values (DEC-0043 R21).
 */
@MessageBundle("value")
public interface ValueTexts {

    @Message
    String holdReasonDependency();

    @Message
    String holdReasonExternal();

    @Message
    String readTextPartDispatch();

    @Message
    String readTextPartReturn();

    @Message
    String readTextPartThread();

    @Message
    String readTextPartAddenda();

    @Message
    String annotatePartDispatch();

    @Message
    String annotatePartReturn();

    @Message
    String annotatePartQuestion();

    @Message
    String annotatePartAnswer();

    @Message
    String annotatePartRemark();

    @Message
    String sortId();

    @Message
    String sortCreatedAt();

    @Message
    String directionAsc();

    @Message
    String directionDesc();
}
