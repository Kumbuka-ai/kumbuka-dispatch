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
 * The description of every argument. An optional argument's description
 * names its fallback and says that null has the same effect as leaving it
 * out (DEC-0043 R17); every one is worded positively (R21).
 */
@MessageBundle("argument")
public interface ArgumentTexts {

    @Message
    String scope();

    @Message
    String selector();

    @Message
    String address();

    @Message
    String conflictToken();

    @Message
    String receipt();

    @Message
    String duration();

    @Message
    String confirmation();

    @Message
    String remarkRequired();

    @Message
    String remarkOptional();

    @Message
    String fields();

    @Message
    String claimIdempotencyKey();

    @Message
    String claimNextIdempotencyKey();

    @Message
    String createIdempotencyKey();

    @Message
    String annotateIdempotencyKey();

    @Message
    String claimNextApparatus();

    @Message
    String deferNotBefore();

    @Message
    String askQuestion();

    @Message
    String askOptions();

    @Message
    String askFreeText();

    @Message
    String answerOption();

    @Message
    String answerText();

    @Message
    String holdReason();

    @Message
    String deliverText();

    @Message
    String deliverMetadata();

    @Message
    String createParent();

    @Message
    String createTitle();

    @Message
    String createApparatus();

    @Message
    String createText();

    @Message
    String createMetadata();

    @Message
    String updateTitle();

    @Message
    String updateApparatus();

    @Message
    String updateText();

    @Message
    String updateMetadata();

    @Message
    String readTextPart();

    @Message
    String queryState();

    @Message
    String queryApparatus();

    @Message
    String queryBracket();

    @Message
    String queryAddress();

    @Message
    String queryPageSize();

    @Message
    String queryAfter();

    @Message
    String querySort();

    @Message
    String queryDirection();

    @Message
    String annotateText();

    @Message
    String annotatePart();

    @Message
    String relateCuratedIn();
}
