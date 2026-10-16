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
 * What a task waits for, where nothing is open to the caller: a notice,
 * which states a fact and may state it in the negative (DEC-0043 R21).
 */
@MessageBundle("notice")
public interface Notices {

    @Message
    String waitingDraft();

    @Message
    String waitingOpen();

    @Message
    String waitingDeferred();

    @Message
    String waitingActive();

    @Message
    String waitingQuestion();

    @Message
    String waitingParked();

    @Message
    String waitingResume();

    @Message
    String waitingDelivered();

    @Message
    String waitingClosed();
}
