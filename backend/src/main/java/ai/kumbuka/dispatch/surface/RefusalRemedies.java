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
public interface RefusalRemedies {

    @Message
    String payloadMalformed();

    @Message
    String addressMalformed();

    @Message
    String argumentUnknown();

    @Message
    String argumentMissing();

    @Message
    String argumentInvalid();

    @Message
    String pageSizeRejected();

    @Message
    String cursorMalformed();

    @Message
    String cursorForeign();

    @Message
    String sortUndeclared();

    @Message
    String roleDoesNotAllow();

    @Message
    String scopeReadOnly();

    @Message
    String notFound();

    @Message
    String verbNotCarried();

    @Message
    String stateDoesNotAllow();

    @Message
    String leaseLapsed();

    @Message
    String claimHeld();

    @Message
    String receiptWrong();

    @Message
    String drawEmpty();

    @Message
    String childrenNotFinished();

    @Message
    String deferralPending();

    @Message
    String idempotencyKeyReused();

    @Message
    String scopeLocked();

    @Message
    String conflictTokenStale();

    @Message
    String selectorUndeclared();

    @Message
    String selectorWithdrawn();

    @Message
    String scopeKindUnsupported();

    @Message
    String conflictTokenMissing();

    @Message
    String unexpectedFailure();
}
