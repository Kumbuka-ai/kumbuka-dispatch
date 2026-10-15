/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every message a refusal can carry, and the remedy of every reason.
 *
 * <p>This is the half of the declaration that makes "a refusal explains
 * itself" checkable rather than hoped for. A refusal is worded from exactly
 * one declared {@link Message}, a method of {@link RefusalMessages}; its
 * text is in {@code messages/refusal.properties}, and the remedy of every
 * reason in {@code messages/remedy.properties}. No text addressed to a
 * caller is written here or anywhere else in Java.
 *
 * <p><strong>The start-up guard reads this.</strong> Every {@link RefusalCode}
 * must have a remedy and at least one message; {@link #requireComplete(Map)}
 * is called at start-up and throws if a code lacks either. That is the
 * mechanism behind "a reason not in the table cannot be returned": not a rule
 * somebody keeps, but a service that does not come up.
 *
 * <h2>Only the platform's vocabulary (DEC-0043 R18, R19)</h2>
 *
 * The placeholders of a message take a call's name ({@link VerbName}), the
 * path or name of a declared argument ({@link ArgumentPath}, {@link
 * ArgumentName}), a declared bound ({@link Bound}), and lists of these; none
 * can be built from a request, and {@code MessagePlaceholderTypeTest} holds
 * every bundle to that. The address, the scope, the state, the conflict token
 * and the instant of a deferral are in no message: a refusal points at the
 * member of {@code data} or the read that shows them.
 */
public final class ReasonCatalogue {

    private ReasonCatalogue() {
    }

    /**
     * One declared message: the reason it is raised under, and the key of its
     * method in {@link RefusalMessages}, which is its key in the bundle file.
     */
    public enum Message {

        NOT_FOUND(RefusalCode.NOT_FOUND, "notFound"),
        STATE_DOES_NOT_ALLOW(RefusalCode.STATE_DOES_NOT_ALLOW, "stateDoesNotAllow"),
        STATE_NOTHING_OPEN(RefusalCode.STATE_DOES_NOT_ALLOW, "stateNothingOpen"),
        STATE_CLOSED(RefusalCode.STATE_DOES_NOT_ALLOW, "stateClosed"),
        ROLE_COMMISSIONER(RefusalCode.ROLE_DOES_NOT_ALLOW, "roleCommissioner"),
        ROLE_EXECUTOR(RefusalCode.ROLE_DOES_NOT_ALLOW, "roleExecutor"),
        ROLE_HOLDER(RefusalCode.ROLE_DOES_NOT_ALLOW, "roleHolder"),
        ROLE_AUTHOR(RefusalCode.ROLE_DOES_NOT_ALLOW, "roleAuthor"),
        ROLE_OTHER_THAN_THE_DELIVERER(RefusalCode.ROLE_DOES_NOT_ALLOW, "roleOtherThanTheDeliverer"),
        ROLE_CAPACITY(RefusalCode.ROLE_DOES_NOT_ALLOW, "roleCapacity"),
        HOLDER_OTHER(RefusalCode.NOT_THE_HOLDER, "holderOther"),
        HOLDER_NOBODY(RefusalCode.NOT_THE_HOLDER, "holderNobody"),
        HOLDER_LAPSED(RefusalCode.NOT_THE_HOLDER, "holderLapsed"),
        TEXT_HOLDER_OTHER(RefusalCode.NOT_THE_HOLDER, "textHolderOther"),
        TEXT_HOLDER_NOBODY(RefusalCode.NOT_THE_HOLDER, "textHolderNobody"),
        TEXT_HOLDER_LAPSED(RefusalCode.NOT_THE_HOLDER, "textHolderLapsed"),
        RECEIPT_WRONG(RefusalCode.RECEIPT_WRONG, "receiptWrong"),
        CHILDREN_NOT_FINISHED(RefusalCode.CHILDREN_NOT_FINISHED, "childrenNotFinished"),
        CHILDREN_CONFIRMATION_STALE(RefusalCode.CHILDREN_NOT_FINISHED, "childrenConfirmationStale"),
        DEFERRAL_PENDING(RefusalCode.DEFERRAL_PENDING, "deferralPending"),
        NOTHING_TO_TAKE(RefusalCode.NOTHING_TO_TAKE, "nothingToTake"),
        CLAIM_DURATION_INVALID(RefusalCode.CLAIM_DURATION_INVALID, "claimDurationInvalid"),
        PAGE_SIZE_REJECTED(RefusalCode.PAGE_SIZE_REJECTED, "pageSizeRejected"),
        CURSOR_MALFORMED(RefusalCode.CURSOR_MALFORMED, "cursorMalformed"),
        CURSOR_FOREIGN(RefusalCode.CURSOR_FOREIGN, "cursorForeign"),
        SORT_UNDECLARED(RefusalCode.SORT_UNDECLARED, "sortUndeclared"),
        ARGUMENT_UNKNOWN(RefusalCode.ARGUMENT_UNKNOWN, "argumentUnknown"),
        ARGUMENT_UNKNOWN_UNDER_FIELDS(RefusalCode.ARGUMENT_UNKNOWN, "argumentUnknownUnderFields"),
        CALL_UNKNOWN(RefusalCode.ARGUMENT_UNKNOWN, "callUnknown"),
        ARGUMENT_MISSING(RefusalCode.ARGUMENT_MISSING, "argumentMissing"),
        ARGUMENT_MISSING_OPTIONS(RefusalCode.ARGUMENT_MISSING, "argumentMissingOptions"),
        ARGUMENT_MISSING_ANSWER(RefusalCode.ARGUMENT_MISSING, "argumentMissingAnswer"),
        ARGUMENT_MISSING_CHANGE(RefusalCode.ARGUMENT_MISSING, "argumentMissingChange"),
        INVALID_FIELDS_OBJECT(RefusalCode.ARGUMENT_INVALID, "invalidFieldsObject"),
        INVALID_STRING(RefusalCode.ARGUMENT_INVALID, "invalidString"),
        INVALID_ONE_OF(RefusalCode.ARGUMENT_INVALID, "invalidOneOf"),
        INVALID_LIST_OF_STRINGS(RefusalCode.ARGUMENT_INVALID, "invalidListOfStrings"),
        INVALID_OBJECT(RefusalCode.ARGUMENT_INVALID, "invalidObject"),
        INVALID_BOOLEAN(RefusalCode.ARGUMENT_INVALID, "invalidBoolean"),
        INVALID_WHOLE_NUMBER(RefusalCode.ARGUMENT_INVALID, "invalidWholeNumber"),
        INVALID_PARENT_ELSEWHERE(RefusalCode.ARGUMENT_INVALID, "invalidParentElsewhere"),
        INVALID_PARENT_NOT_A_ROOT(RefusalCode.ARGUMENT_INVALID, "invalidParentNotARoot"),
        INVALID_APPARATUS_PATTERN(RefusalCode.ARGUMENT_INVALID, "invalidApparatusPattern"),
        INVALID_APPARATUS_UNBOUNDED(RefusalCode.ARGUMENT_INVALID, "invalidApparatusUnbounded"),
        INVALID_STATE_FILTER(RefusalCode.ARGUMENT_INVALID, "invalidStateFilter"),
        INVALID_BRACKET_FILTER(RefusalCode.ARGUMENT_INVALID, "invalidBracketFilter"),
        INVALID_ADDRESS_FILTER(RefusalCode.ARGUMENT_INVALID, "invalidAddressFilter"),
        INVALID_ADDRESS_FILTER_ELSEWHERE(RefusalCode.ARGUMENT_INVALID, "invalidAddressFilterElsewhere"),
        INVALID_VALUE_FILTER(RefusalCode.ARGUMENT_INVALID, "invalidValueFilter"),
        INVALID_LEASE_END(RefusalCode.ARGUMENT_INVALID, "invalidLeaseEnd"),
        INVALID_INSTANT(RefusalCode.ARGUMENT_INVALID, "invalidInstant"),
        INVALID_INSTANT_RANGE(RefusalCode.ARGUMENT_INVALID, "invalidInstantRange"),
        INVALID_ANSWER_ONE_OF_TWO(RefusalCode.ARGUMENT_INVALID, "invalidAnswerOneOfTwo"),
        INVALID_ADDRESS(RefusalCode.ARGUMENT_INVALID, "invalidAddress"),
        INVALID_SCOPE(RefusalCode.ARGUMENT_INVALID, "invalidScope"),
        INVALID_SELECTOR(RefusalCode.ARGUMENT_INVALID, "invalidSelector"),
        INVALID_METADATA(RefusalCode.ARGUMENT_INVALID, "invalidMetadata"),
        INVALID_CURATION_TARGET(RefusalCode.ARGUMENT_INVALID, "invalidCurationTarget"),
        INVALID_ADDENDUM_TARGET(RefusalCode.ARGUMENT_INVALID, "invalidAddendumTarget"),
        INVALID_ADDENDA_EXHAUSTED(RefusalCode.ARGUMENT_INVALID, "invalidAddendaExhausted"),
        INVALID_ANSWER_OPTION(RefusalCode.ARGUMENT_INVALID, "invalidAnswerOption"),
        INVALID_ANSWER_FREE_TEXT(RefusalCode.ARGUMENT_INVALID, "invalidAnswerFreeText"),
        INVALID_ONCE(RefusalCode.ARGUMENT_INVALID, "invalidOnce"),
        INVALID_BODY(RefusalCode.ARGUMENT_INVALID, "invalidBody"),
        INVALID_SURFACE_HEADER(RefusalCode.ARGUMENT_INVALID, "invalidSurfaceHeader"),
        INVALID_IN_REQUEST(RefusalCode.ARGUMENT_INVALID, "invalidInRequest"),
        VALUE_INVALID(RefusalCode.ARGUMENT_INVALID, "valueInvalid"),
        VALUE_GIVEN_TWICE(RefusalCode.ARGUMENT_INVALID, "valueGivenTwice"),
        CONFLICT_TOKEN_STALE(RefusalCode.CONFLICT_TOKEN_STALE, "conflictTokenStale"),
        SELECTOR_UNKNOWN(RefusalCode.SELECTOR_UNKNOWN, "selectorUnknown"),
        SCOPE_KIND_UNSUPPORTED(RefusalCode.SCOPE_KIND_UNSUPPORTED, "scopeKindUnsupported"),
        SCOPE_READ_ONLY(RefusalCode.SCOPE_READ_ONLY, "scopeReadOnly"),
        SCOPE_LOCKED(RefusalCode.SCOPE_LOCKED, "scopeLocked"),
        IDEMPOTENCY_KEY_REUSED(RefusalCode.IDEMPOTENCY_KEY_REUSED, "idempotencyKeyReused"),
        ROUTE_CREATE(RefusalCode.CALL_NOT_AT_THIS_ADDRESS, "routeCreate"),
        ROUTE_QUERY(RefusalCode.CALL_NOT_AT_THIS_ADDRESS, "routeQuery"),
        ROUTE_ON_A_BRACKET_KIND(RefusalCode.CALL_NOT_AT_THIS_ADDRESS, "routeOnABracketKind"),
        ROUTE_READ(RefusalCode.CALL_NOT_AT_THIS_ADDRESS, "routeRead"),
        ROUTE_READ_CALL(RefusalCode.CALL_NOT_AT_THIS_ADDRESS, "routeReadCall"),
        ROUTE_UPDATE(RefusalCode.CALL_NOT_AT_THIS_ADDRESS, "routeUpdate"),
        ROUTE_DELETE(RefusalCode.CALL_NOT_AT_THIS_ADDRESS, "routeDelete"),
        ROUTE_ON_A_TASK(RefusalCode.CALL_NOT_AT_THIS_ADDRESS, "routeOnATask"),
        UNEXPECTED_FAILURE(RefusalCode.UNEXPECTED_FAILURE, "unexpectedFailure"),
        UNEXPECTED_FAILURE_OF_REQUEST(RefusalCode.UNEXPECTED_FAILURE, "unexpectedFailureOfRequest");

        private final RefusalCode code;
        private final String key;

        Message(RefusalCode code, String key) {
            this.code = code;
            this.key = key;
        }

        public RefusalCode code() {
            return code;
        }

        /** The key of the message in {@code messages/refusal.properties}. */
        public String key() {
            return key;
        }

        /** The message as its file holds it, placeholders and all. */
        public String template() {
            return BundleFile.template("refusal", key);
        }
    }

    /**
     * One declared reason: the code, its messages, and what the caller can do
     * about it.
     *
     * @param code     the caller-facing reason, stable across rewordings
     * @param messages the messages raised under it
     * @param remedy   what the caller does next: an instruction, worded as the
     *                 act to take (DEC-0042, DEC-0043 R21). Not sent on the
     *                 wire, where {@code data.next} carries the calls, but
     *                 declared, because a reason nobody could act on is one
     *                 that should not have been declared.
     */
    public record Reason(RefusalCode code, List<Message> messages, String remedy) {

        public Reason {
            messages = List.copyOf(messages);
        }
    }

    /** The remedy of one reason, from its bundle. */
    public static String remedy(RefusalCode code) {
        RemedyTexts remedies = Texts.remedies();
        return switch (code) {
            case NOT_FOUND -> remedies.notFound();
            case STATE_DOES_NOT_ALLOW -> remedies.stateDoesNotAllow();
            case ROLE_DOES_NOT_ALLOW -> remedies.roleDoesNotAllow();
            case NOT_THE_HOLDER -> remedies.notTheHolder();
            case RECEIPT_WRONG -> remedies.receiptWrong();
            case CHILDREN_NOT_FINISHED -> remedies.childrenNotFinished();
            case DEFERRAL_PENDING -> remedies.deferralPending();
            case NOTHING_TO_TAKE -> remedies.nothingToTake();
            case CLAIM_DURATION_INVALID -> remedies.claimDurationInvalid();
            case PAGE_SIZE_REJECTED -> remedies.pageSizeRejected();
            case CURSOR_MALFORMED -> remedies.cursorMalformed();
            case CURSOR_FOREIGN -> remedies.cursorForeign();
            case SORT_UNDECLARED -> remedies.sortUndeclared();
            case ARGUMENT_UNKNOWN -> remedies.argumentUnknown();
            case ARGUMENT_MISSING -> remedies.argumentMissing();
            case ARGUMENT_INVALID -> remedies.argumentInvalid();
            case CONFLICT_TOKEN_STALE -> remedies.conflictTokenStale();
            case SELECTOR_UNKNOWN -> remedies.selectorUnknown();
            case SCOPE_KIND_UNSUPPORTED -> remedies.scopeKindUnsupported();
            case SCOPE_READ_ONLY -> remedies.scopeReadOnly();
            case SCOPE_LOCKED -> remedies.scopeLocked();
            case IDEMPOTENCY_KEY_REUSED -> remedies.idempotencyKeyReused();
            case CALL_NOT_AT_THIS_ADDRESS -> remedies.callNotAtThisAddress();
            case UNEXPECTED_FAILURE -> remedies.unexpectedFailure();
        };
    }

    /** The declared reasons by code, for the start-up guard. Built when asked: it reads the bundles. */
    public static Map<RefusalCode, Reason> byCode() {
        Map<RefusalCode, Reason> declared = new LinkedHashMap<>();
        for (RefusalCode code : RefusalCode.values()) {
            List<Message> messages = Arrays.stream(Message.values())
                .filter(m -> m.code() == code)
                .toList();
            declared.put(code, new Reason(code, messages, remedy(code)));
        }
        return java.util.Collections.unmodifiableMap(declared);
    }

    /** Every declared reason, in the order of the codes. */
    public static List<Reason> declared() {
        return List.copyOf(byCode().values());
    }

    /** One declared reason. */
    public static Reason of(RefusalCode code) {
        return byCode().get(code);
    }

    /**
     * Refuses a catalogue that does not cover the codes.
     *
     * <p>Called from the start-up guard. A code with no message cannot be
     * worded and would escape as an unexpected failure the first time it is
     * raised; a code with no remedy is a refusal nobody could act on.
     */
    public static void requireComplete(Map<RefusalCode, Reason> catalogue) {
        List<String> undeclared = Arrays.stream(RefusalCode.values())
            .filter(code -> !catalogue.containsKey(code))
            .map(Enum::name)
            .toList();

        if (!undeclared.isEmpty()) {
            throw new IllegalStateException(
                "the reason catalogue does not declare " + undeclared + ". A reason not "
                    + "in the table cannot be returned, and the service refuses to start "
                    + "rather than raise one it cannot word.");
        }

        for (Reason reason : catalogue.values()) {
            if (reason.messages().isEmpty()
                || reason.remedy() == null || reason.remedy().isBlank()) {
                throw new IllegalStateException(
                    reason.code() + " is declared without a message or without a remedy. "
                        + "A refusal that cannot say what happened, or cannot say what to "
                        + "do about it, is not a declared refusal.");
            }
        }
    }
}
