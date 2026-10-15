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
 * The remedy of every refusal reason: the act the caller performs next,
 * worded positively (DEC-0042, DEC-0043 R21).
 */
@MessageBundle("remedy")
public interface RemedyTexts {

    @Message
    String notFound();

    @Message
    String stateDoesNotAllow();

    @Message
    String roleDoesNotAllow();

    @Message
    String notTheHolder();

    @Message
    String receiptWrong();

    @Message
    String childrenNotFinished();

    @Message
    String deferralPending();

    @Message
    String nothingToTake();

    @Message
    String claimDurationInvalid();

    @Message
    String pageSizeRejected();

    @Message
    String cursorMalformed();

    @Message
    String cursorForeign();

    @Message
    String sortUndeclared();

    @Message
    String argumentUnknown();

    @Message
    String argumentMissing();

    @Message
    String argumentInvalid();

    @Message
    String conflictTokenStale();

    @Message
    String selectorUnknown();

    @Message
    String scopeKindUnsupported();

    @Message
    String scopeReadOnly();

    @Message
    String scopeLocked();

    @Message
    String idempotencyKeyReused();

    @Message
    String callNotAtThisAddress();

    @Message
    String unexpectedFailure();
}
