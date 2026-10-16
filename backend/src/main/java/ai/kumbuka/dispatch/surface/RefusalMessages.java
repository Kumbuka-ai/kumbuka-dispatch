/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import java.util.List;

import io.quarkus.qute.i18n.Message;
import io.quarkus.qute.i18n.MessageBundle;

/**
 * The message of every refusal, one method per message.
 *
 * A message states what happened and may state a finding in the negative
 * (DEC-0043 R21). Its placeholders take only the platform's vocabulary
 * (R18): a call's name, an argument's path or name, a declared bound, or a
 * list of one of them. The texts are in {@code messages/refusal.properties}.
 */
@MessageBundle("refusal")
public interface RefusalMessages {

    @Message
    String notFound();

    @Message
    String stateDoesNotAllow(VerbName call, List<VerbName> calls);

    @Message
    String stateNothingOpen(VerbName call);

    @Message
    String stateClosed(VerbName call);

    @Message
    String roleCommissioner(VerbName call);

    @Message
    String roleExecutor(VerbName call);

    @Message
    String roleHolder(VerbName call);

    @Message
    String roleAuthor(VerbName call);

    @Message
    String roleOtherThanTheDeliverer(VerbName call);

    @Message
    String roleCapacity(VerbName call);

    @Message
    String holderOther(VerbName call);

    @Message
    String holderNobody(VerbName call);

    @Message
    String holderLapsed(VerbName call);

    @Message
    String textHolderOther(VerbName call);

    @Message
    String textHolderNobody(VerbName call);

    @Message
    String textHolderLapsed(VerbName call);

    @Message
    String receiptWrong(VerbName call);

    @Message
    String childrenUnfinished(VerbName call);

    @Message
    String childrenConfirmationStale(VerbName call);

    @Message
    String rootUnsent(VerbName call);

    @Message
    String rootClosed(VerbName call);

    @Message
    String childrenPresent(VerbName call);

    @Message
    String deferralPending(VerbName call, VerbName read);

    @Message
    String nothingToTake();

    @Message
    String claimDurationInvalid(VerbName call);

    @Message
    String pageSizeRejected(VerbName call, Bound max);

    @Message
    String cursorMalformed(VerbName query);

    @Message
    String cursorForeign(VerbName call, List<ArgumentName> differs);

    @Message
    String sortUndeclared(VerbName call);

    @Message
    String argumentUnknown(VerbName call, List<ArgumentPath> accepts);

    @Message
    String argumentUnknownUnderFields(VerbName call, List<ArgumentPath> accepts);

    @Message
    String callUnknown(List<VerbName> accepts);

    @Message
    String methodNotCarried();

    @Message
    String argumentMissing(VerbName call, ArgumentPath path);

    @Message
    String argumentMissingOptions(VerbName call);

    @Message
    String argumentMissingAnswer(VerbName call);

    @Message
    String argumentMissingChange(VerbName call);

    @Message
    String invalidFieldsObject(VerbName call, ArgumentPath path);

    @Message
    String invalidString(VerbName call, ArgumentPath path);

    @Message
    String invalidOneOf(VerbName call, ArgumentPath path);

    @Message
    String invalidListOfStrings(VerbName call, ArgumentPath path);

    @Message
    String invalidObject(VerbName call, ArgumentPath path);

    @Message
    String invalidBoolean(VerbName call, ArgumentPath path);

    @Message
    String invalidWholeNumber(VerbName call, ArgumentPath path);

    @Message
    String invalidParentElsewhere(VerbName call, ArgumentPath path);

    @Message
    String invalidParentNotARoot(VerbName call, ArgumentPath path);

    @Message
    String invalidApparatusPattern(VerbName call, ArgumentPath path);

    @Message
    String invalidApparatusUnbounded(VerbName call, ArgumentPath path);

    @Message
    String invalidStateFilter(VerbName call, ArgumentPath path);

    @Message
    String invalidBracketFilter(VerbName call, ArgumentPath path);

    @Message
    String invalidAddressFilter(VerbName call, ArgumentPath path);

    @Message
    String invalidAddressFilterElsewhere(VerbName call, ArgumentPath path);

    @Message
    String invalidValueFilter(VerbName call, ArgumentPath path);

    @Message
    String invalidLeaseEnd(VerbName call, ArgumentPath path);

    @Message
    String invalidInstant(VerbName call, ArgumentPath path);

    @Message
    String invalidInstantRange(VerbName call, ArgumentPath path);

    @Message
    String invalidAnswerOneOfTwo(VerbName call, ArgumentPath path);

    @Message
    String invalidAddress(VerbName call, ArgumentPath path);

    @Message
    String invalidScope(VerbName call, ArgumentPath path);

    @Message
    String invalidSelector(VerbName call, ArgumentPath path);

    @Message
    String invalidMetadata(VerbName call, ArgumentPath path);

    @Message
    String invalidCurationTarget(VerbName call, ArgumentPath path);

    @Message
    String invalidAddendumTarget(VerbName call, ArgumentPath path);

    @Message
    String invalidAddendaExhausted(VerbName call, ArgumentPath path);

    @Message
    String optionUnoffered(VerbName call);

    @Message
    String freeTextUnoffered(VerbName call);

    @Message
    String invalidOnce(VerbName call, ArgumentPath path);

    @Message
    String invalidBody(VerbName call);

    @Message
    String headerInvalid();

    @Message
    String invalidInRequest(ArgumentPath path);

    @Message
    String scopeReserved();

    @Message
    String valueInvalid(VerbName call);

    @Message
    String valueGivenTwice(VerbName call);

    @Message
    String conflictTokenStale(VerbName call);

    @Message
    String selectorUnknown();

    @Message
    String selectorWithdrawn();

    @Message
    String conflictTokenMissing(VerbName call);

    @Message
    String scopeKindUnsupported();

    @Message
    String scopeReadOnly(VerbName call);

    @Message
    String scopeLocked();

    @Message
    String idempotencyKeyReused();

    @Message
    String routeCreate(VerbName call);

    @Message
    String routeQuery(VerbName call);

    @Message
    String routeOnABracketKind(VerbName call);

    @Message
    String routeRead(VerbName call);

    @Message
    String routeReadCall(VerbName call);

    @Message
    String routeUpdate(VerbName call);

    @Message
    String routeDelete(VerbName call);

    @Message
    String routeOnATask(VerbName call);

    @Message
    String unexpectedFailure(VerbName call);

    @Message
    String unexpectedFailureOfRequest();
}
