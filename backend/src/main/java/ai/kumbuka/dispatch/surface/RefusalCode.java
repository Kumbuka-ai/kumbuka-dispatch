/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.domain.DispatchException;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The catalogue of refusals: every code this service can answer with, its
 * HTTP status, the messages raised under it and the remedy (C8.5).
 *
 * <p><strong>A code not in this enum cannot be returned.</strong> Each code
 * carries its status, so the status of a refusal is a fact of the catalogue and
 * not a choice a mapper makes; {@link RefusalCatalogueGuard} refuses the start
 * of a service whose catalogue misses a message or a remedy for a code.
 *
 * <p>The codes and statuses are the platform's table (C8.7) wherever the table
 * names the condition. The codes beyond it are this service's own and are
 * declared here with their statuses; {@code SharedCodeTableTest} holds the two
 * apart.
 *
 * <p>These are the caller's vocabulary, not the kernel's. The kernel's {@link
 * DispatchException.Reason} says what the domain refused; {@link #of} says what
 * the caller is told, in one switch with no default, so a reason added to the
 * kernel is a compile error here rather than a refusal that escapes as an
 * unexpected failure. Several kernel reasons map onto one code, and that is
 * the point: the kernel tells an unresolved scope from a missing task because
 * it decides differently; a caller must not be able to.
 */
public enum RefusalCode {

    /** The body is no JSON object. */
    PAYLOAD_MALFORMED(400),

    /** An address, a scope or a bracket kind is not in the grammar. */
    ADDRESS_MALFORMED(400),

    /** An argument the call does not declare, at any depth. Nothing was written. */
    ARGUMENT_UNKNOWN(400),

    /** An argument the call requires did not arrive. */
    ARGUMENT_MISSING(400),

    /** An argument arrived with a value outside its declared form. */
    ARGUMENT_INVALID(400),

    /**
     * {@code Kumbuka-Surface} arrived with a value other than {@code assistant},
     * or twice. Decided after authentication and before anything else (C14.4).
     */
    HEADER_INVALID(400),

    /** A page size outside 1 to 200, or no whole number (ADR-0049). */
    PAGE_SIZE_REJECTED(400),

    /** A cursor this service did not hand out. */
    CURSOR_MALFORMED(400),

    /** A cursor handed out for another listing. */
    CURSOR_FOREIGN(400),

    /** A sort a listing does not declare. */
    SORT_UNDECLARED(400),

    /**
     * The call belongs to another part. Decided after the address is
     * resolved: an object the caller cannot see is {@link #NOT_FOUND}.
     */
    ROLE_INSUFFICIENT(403),

    /** The caller may read this scope and may not write to it. */
    SCOPE_READ_ONLY(403),

    /**
     * Nothing is visible at the address given: absent, invisible and
     * unroutable answer alike, byte for byte and with no {@code data}.
     */
    NOT_FOUND(404),

    /** A verb this service does not carry, or does not carry at this address. */
    VERB_NOT_CARRIED(405),

    /** The task's state does not permit the call. */
    TRANSITION_REFUSED(409),

    /**
     * The call is the holder's, and the caller holds no claim on the task:
     * nobody holds it, or the caller's own lease on it ended, whatever
     * receipt the call carried.
     */
    CLAIM_ABSENT(409),

    /** The call is the holder's, and another executor holds the task. */
    CLAIM_HELD(409),

    /** A receipt arrived, the caller's hold stands, and it is not that hold's receipt. */
    RECEIPT_WRONG(409),

    /** The draw found nothing it could take. About a set, never an address. */
    DRAW_EMPTY(409),

    /** The call would close a bracket root with unfinished children. */
    CHILDREN_UNFINISHED(409),

    /** A child is created only under a root that was sent; this one is a draft. */
    ROOT_UNSENT(409),

    /** A child is created only under a root that is not closed; this one is. */
    ROOT_CLOSED(409),

    /** A draft root is deleted only while no task stands under it. */
    CHILDREN_PRESENT(409),

    /**
     * The answer names an option the question does not offer, or free text
     * the question does not admit. Decided against the question the task
     * holds, so after the address is resolved.
     */
    OPTION_UNOFFERED(409),

    /** A claim on a task deferred until an instant that has not come. */
    DEFERRAL_PENDING(409),

    /** The same idempotency key, a different call. */
    IDEMPOTENCY_KEY_REUSED(409),

    /** The scope is locked, and a locked scope refuses every write. */
    SCOPE_LOCKED(409),

    /** The token presented is not the one the task holds. */
    CONFLICT_TOKEN_STALE(412),

    /** The bracket kind is not declared in this scope. */
    SELECTOR_UNDECLARED(422),

    /** The bracket kind was declared in this scope and is withdrawn. */
    SELECTOR_WITHDRAWN(422),

    /** The scope is of a kind this service does not carry tasks in. */
    SCOPE_KIND_UNSUPPORTED(422),

    /** The call is fenced by the conflict token and arrived without one. */
    CONFLICT_TOKEN_MISSING(428),

    /** A defect on our side, reported as one, with a reference. */
    UNEXPECTED_FAILURE(500);

    private final int status;

    RefusalCode(int status) {
        this.status = status;
    }

    /** The HTTP status this code answers with on the REST surface. */
    public int status() {
        return status;
    }

    /**
     * The code a kernel refusal is told as.
     *
     * <p>{@code CLAIM_REQUIRED} is told as {@link #CLAIM_HELD}: a holder's call
     * by a caller who holds nothing. Where nobody holds the task the edge
     * answers {@link #CLAIM_ABSENT} instead, because it reads the task's
     * situation and the kernel's reason does not say which it was.
     */
    public static RefusalCode of(DispatchException.Reason reason) {
        return switch (reason) {
            case NOT_FOUND, SCOPE_UNRESOLVED -> NOT_FOUND;
            case TRANSITION_NOT_PERMITTED -> TRANSITION_REFUSED;
            case RATIFICATION_NOT_PERMITTED, ACTOR_UNKNOWN -> ROLE_INSUFFICIENT;
            case CLAIM_REQUIRED -> CLAIM_HELD;
            case LEASE_LAPSED -> CLAIM_ABSENT;
            case RECEIPT_MISMATCH -> RECEIPT_WRONG;
            case CONFIRMATION_REQUIRED, CONFIRMATION_STALE -> CHILDREN_UNFINISHED;
            case ROOT_UNSENT -> ROOT_UNSENT;
            case ROOT_CLOSED -> ROOT_CLOSED;
            case CHILDREN_PRESENT -> CHILDREN_PRESENT;
            case ANSWER_NOT_AN_OPTION -> OPTION_UNOFFERED;
            case DEFERRAL_PENDING -> DEFERRAL_PENDING;
            case NOTHING_TO_CLAIM -> DRAW_EMPTY;
            case PAGE_SIZE_REJECTED -> PAGE_SIZE_REJECTED;
            case CURSOR_MALFORMED -> CURSOR_MALFORMED;
            case CURSOR_FOREIGN -> CURSOR_FOREIGN;
            case SORT_UNDECLARED -> SORT_UNDECLARED;
            case SELECTOR_NOT_DECLARED, SELECTOR_IN_USE -> SELECTOR_UNDECLARED;
            case SELECTOR_WITHDRAWN -> SELECTOR_WITHDRAWN;
            case SCOPE_KIND_UNSUPPORTED -> SCOPE_KIND_UNSUPPORTED;
            case SCOPE_READ_ONLY -> SCOPE_READ_ONLY;
            case SCOPE_LOCKED -> SCOPE_LOCKED;
            case CLAIM_DURATION_NOT_POSITIVE, ADDENDUM_SUFFIX_EXHAUSTED, METADATA_REFUSED,
                 FILTER_VALUE_REFUSED, CURATION_TARGET_SELF ->
                ARGUMENT_INVALID;
            case IDEMPOTENCY_KEY_REUSED -> IDEMPOTENCY_KEY_REUSED;
            case FILTER_FIELD_UNKNOWN -> ARGUMENT_UNKNOWN;
            case UPDATE_EMPTY, ADDENDUM_TEXT_MISSING, RECEIPT_ABSENT -> ARGUMENT_MISSING;
            case CONFLICT_TOKEN_MISSING -> CONFLICT_TOKEN_MISSING;
            case CONFLICT_TOKEN_STALE -> CONFLICT_TOKEN_STALE;
            case SESSION_NOT_BOUND -> UNEXPECTED_FAILURE;
        };
    }

    /** What the caller does next: an instruction, from the remedy bundle. */
    public String remedy() {
        RefusalRemedies remedies = Texts.remedies();
        return switch (this) {
            case PAYLOAD_MALFORMED -> remedies.payloadMalformed();
            case ADDRESS_MALFORMED -> remedies.addressMalformed();
            case ARGUMENT_UNKNOWN -> remedies.argumentUnknown();
            case ARGUMENT_MISSING -> remedies.argumentMissing();
            case ARGUMENT_INVALID -> remedies.argumentInvalid();
            case HEADER_INVALID -> remedies.headerInvalid();
            case PAGE_SIZE_REJECTED -> remedies.pageSizeRejected();
            case CURSOR_MALFORMED -> remedies.cursorMalformed();
            case CURSOR_FOREIGN -> remedies.cursorForeign();
            case SORT_UNDECLARED -> remedies.sortUndeclared();
            case ROLE_INSUFFICIENT -> remedies.roleInsufficient();
            case SCOPE_READ_ONLY -> remedies.scopeReadOnly();
            case NOT_FOUND -> remedies.notFound();
            case VERB_NOT_CARRIED -> remedies.verbNotCarried();
            case TRANSITION_REFUSED -> remedies.transitionRefused();
            case CLAIM_ABSENT -> remedies.claimAbsent();
            case CLAIM_HELD -> remedies.claimHeld();
            case RECEIPT_WRONG -> remedies.receiptWrong();
            case DRAW_EMPTY -> remedies.drawEmpty();
            case CHILDREN_UNFINISHED -> remedies.childrenUnfinished();
            case ROOT_UNSENT -> remedies.rootUnsent();
            case ROOT_CLOSED -> remedies.rootClosed();
            case CHILDREN_PRESENT -> remedies.childrenPresent();
            case OPTION_UNOFFERED -> remedies.optionUnoffered();
            case DEFERRAL_PENDING -> remedies.deferralPending();
            case IDEMPOTENCY_KEY_REUSED -> remedies.idempotencyKeyReused();
            case SCOPE_LOCKED -> remedies.scopeLocked();
            case CONFLICT_TOKEN_STALE -> remedies.conflictTokenStale();
            case SELECTOR_UNDECLARED -> remedies.selectorUndeclared();
            case SELECTOR_WITHDRAWN -> remedies.selectorWithdrawn();
            case SCOPE_KIND_UNSUPPORTED -> remedies.scopeKindUnsupported();
            case CONFLICT_TOKEN_MISSING -> remedies.conflictTokenMissing();
            case UNEXPECTED_FAILURE -> remedies.unexpectedFailure();
        };
    }

    /** The messages raised under this code, in declaration order. */
    public List<Message> messages() {
        return Arrays.stream(Message.values()).filter(m -> m.code() == this).toList();
    }

    /**
     * One declared code: its messages and its remedy.
     *
     * @param remedy what the caller does next, worded as the act to take
     *               (DEC-0042, DEC-0043 R21). Declared, not sent: on the wire
     *               {@code data.next} carries the calls
     */
    public record Declared(RefusalCode code, List<Message> messages, String remedy) {

        public Declared {
            messages = List.copyOf(messages);
        }
    }

    /** The catalogue, by code. Built when asked: it reads the bundles. */
    public static Map<RefusalCode, Declared> catalogue() {
        Map<RefusalCode, Declared> declared = new LinkedHashMap<>();
        for (RefusalCode code : values()) {
            declared.put(code, new Declared(code, code.messages(), code.remedy()));
        }
        return java.util.Collections.unmodifiableMap(declared);
    }

    /**
     * One declared message: the code it is raised under, and the key of its
     * method in {@link RefusalMessages}, which is its key in the bundle file.
     */
    public enum Message {

        NOT_FOUND(RefusalCode.NOT_FOUND, "notFound"),
        STATE_DOES_NOT_ALLOW(RefusalCode.TRANSITION_REFUSED, "stateDoesNotAllow"),
        STATE_NOTHING_OPEN(RefusalCode.TRANSITION_REFUSED, "stateNothingOpen"),
        STATE_CLOSED(RefusalCode.TRANSITION_REFUSED, "stateClosed"),
        HOLDER_NOBODY(RefusalCode.CLAIM_ABSENT, "holderNobody"),
        TEXT_HOLDER_NOBODY(RefusalCode.CLAIM_ABSENT, "textHolderNobody"),
        ROLE_COMMISSIONER(RefusalCode.ROLE_INSUFFICIENT, "roleCommissioner"),
        ROLE_EXECUTOR(RefusalCode.ROLE_INSUFFICIENT, "roleExecutor"),
        ROLE_HOLDER(RefusalCode.ROLE_INSUFFICIENT, "roleHolder"),
        ROLE_AUTHOR(RefusalCode.ROLE_INSUFFICIENT, "roleAuthor"),
        ROLE_OTHER_THAN_THE_DELIVERER(RefusalCode.ROLE_INSUFFICIENT, "roleOtherThanTheDeliverer"),
        ROLE_CAPACITY(RefusalCode.ROLE_INSUFFICIENT, "roleCapacity"),
        HOLDER_OTHER(RefusalCode.CLAIM_HELD, "holderOther"),
        TEXT_HOLDER_OTHER(RefusalCode.CLAIM_HELD, "textHolderOther"),
        HOLDER_LAPSED(RefusalCode.CLAIM_ABSENT, "holderLapsed"),
        TEXT_HOLDER_LAPSED(RefusalCode.CLAIM_ABSENT, "textHolderLapsed"),
        RECEIPT_WRONG(RefusalCode.RECEIPT_WRONG, "receiptWrong"),
        CHILDREN_UNFINISHED(RefusalCode.CHILDREN_UNFINISHED, "childrenUnfinished"),
        CHILDREN_CONFIRMATION_STALE(RefusalCode.CHILDREN_UNFINISHED, "childrenConfirmationStale"),
        ROOT_UNSENT(RefusalCode.ROOT_UNSENT, "rootUnsent"),
        ROOT_CLOSED(RefusalCode.ROOT_CLOSED, "rootClosed"),
        CHILDREN_PRESENT(RefusalCode.CHILDREN_PRESENT, "childrenPresent"),
        OPTION_UNOFFERED(RefusalCode.OPTION_UNOFFERED, "optionUnoffered"),
        FREE_TEXT_UNOFFERED(RefusalCode.OPTION_UNOFFERED, "freeTextUnoffered"),
        DEFERRAL_PENDING(RefusalCode.DEFERRAL_PENDING, "deferralPending"),
        NOTHING_TO_TAKE(RefusalCode.DRAW_EMPTY, "nothingToTake"),
        CLAIM_DURATION_INVALID(RefusalCode.ARGUMENT_INVALID, "claimDurationInvalid"),
        PAGE_SIZE_REJECTED(RefusalCode.PAGE_SIZE_REJECTED, "pageSizeRejected"),
        CURSOR_MALFORMED(RefusalCode.CURSOR_MALFORMED, "cursorMalformed"),
        CURSOR_FOREIGN(RefusalCode.CURSOR_FOREIGN, "cursorForeign"),
        SORT_UNDECLARED(RefusalCode.SORT_UNDECLARED, "sortUndeclared"),
        ARGUMENT_UNKNOWN(RefusalCode.ARGUMENT_UNKNOWN, "argumentUnknown"),
        ARGUMENT_UNKNOWN_UNDER_FIELDS(RefusalCode.ARGUMENT_UNKNOWN, "argumentUnknownUnderFields"),
        CALL_UNKNOWN(RefusalCode.VERB_NOT_CARRIED, "callUnknown"),
        ARGUMENT_MISSING(RefusalCode.ARGUMENT_MISSING, "argumentMissing"),
        ARGUMENT_MISSING_OPTIONS(RefusalCode.ARGUMENT_MISSING, "argumentMissingOptions"),
        ARGUMENT_MISSING_ANSWER(RefusalCode.ARGUMENT_MISSING, "argumentMissingAnswer"),
        ARGUMENT_MISSING_CHANGE(RefusalCode.ARGUMENT_MISSING, "argumentMissingChange"),
        CONFLICT_TOKEN_MISSING(RefusalCode.CONFLICT_TOKEN_MISSING, "conflictTokenMissing"),
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
        INVALID_ADDRESS(RefusalCode.ADDRESS_MALFORMED, "invalidAddress"),
        INVALID_SCOPE(RefusalCode.ADDRESS_MALFORMED, "invalidScope"),
        INVALID_SELECTOR(RefusalCode.ADDRESS_MALFORMED, "invalidSelector"),
        INVALID_METADATA(RefusalCode.ARGUMENT_INVALID, "invalidMetadata"),
        INVALID_CURATION_TARGET(RefusalCode.ARGUMENT_INVALID, "invalidCurationTarget"),
        INVALID_ADDENDUM_TARGET(RefusalCode.ARGUMENT_INVALID, "invalidAddendumTarget"),
        INVALID_ADDENDA_EXHAUSTED(RefusalCode.ARGUMENT_INVALID, "invalidAddendaExhausted"),
        INVALID_ONCE(RefusalCode.ARGUMENT_INVALID, "invalidOnce"),
        INVALID_BODY(RefusalCode.PAYLOAD_MALFORMED, "invalidBody"),
        HEADER_INVALID(RefusalCode.HEADER_INVALID, "headerInvalid"),
        INVALID_IN_REQUEST(RefusalCode.ADDRESS_MALFORMED, "invalidInRequest"),
        SCOPE_RESERVED(RefusalCode.ADDRESS_MALFORMED, "scopeReserved"),
        VALUE_INVALID(RefusalCode.ARGUMENT_INVALID, "valueInvalid"),
        VALUE_GIVEN_TWICE(RefusalCode.ARGUMENT_INVALID, "valueGivenTwice"),
        CONFLICT_TOKEN_STALE(RefusalCode.CONFLICT_TOKEN_STALE, "conflictTokenStale"),
        SELECTOR_UNKNOWN(RefusalCode.SELECTOR_UNDECLARED, "selectorUnknown"),
        SELECTOR_WITHDRAWN(RefusalCode.SELECTOR_WITHDRAWN, "selectorWithdrawn"),
        SCOPE_KIND_UNSUPPORTED(RefusalCode.SCOPE_KIND_UNSUPPORTED, "scopeKindUnsupported"),
        SCOPE_READ_ONLY(RefusalCode.SCOPE_READ_ONLY, "scopeReadOnly"),
        SCOPE_LOCKED(RefusalCode.SCOPE_LOCKED, "scopeLocked"),
        IDEMPOTENCY_KEY_REUSED(RefusalCode.IDEMPOTENCY_KEY_REUSED, "idempotencyKeyReused"),
        ROUTE_CREATE(RefusalCode.VERB_NOT_CARRIED, "routeCreate"),
        ROUTE_QUERY(RefusalCode.VERB_NOT_CARRIED, "routeQuery"),
        ROUTE_ON_A_BRACKET_KIND(RefusalCode.VERB_NOT_CARRIED, "routeOnABracketKind"),
        ROUTE_READ(RefusalCode.VERB_NOT_CARRIED, "routeRead"),
        ROUTE_READ_CALL(RefusalCode.VERB_NOT_CARRIED, "routeReadCall"),
        ROUTE_UPDATE(RefusalCode.VERB_NOT_CARRIED, "routeUpdate"),
        ROUTE_DELETE(RefusalCode.VERB_NOT_CARRIED, "routeDelete"),
        ROUTE_ON_A_TASK(RefusalCode.VERB_NOT_CARRIED, "routeOnATask"),
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
}
