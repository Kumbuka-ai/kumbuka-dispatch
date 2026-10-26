/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.tenancy.StringUuidConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.TenantId;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The process of one task: address, state and attributes, holding, metadata.
 *
 * <p>Its texts are rows of {@link TaskText}. A transition writes this row and
 * inserts at most one text row; it never rewrites a text (concept section 1).
 *
 * <p><strong>Nothing here decides.</strong> The state moves only through
 * {@link #enter}, which {@link TaskService} calls after {@link Decision#of}
 * permitted the verb on the {@link TaskSituation} — never the stored state. There
 * is no setter for the state and none for the holding columns, so a write that
 * skipped the decision would have to go through the one method that writes
 * the row shape the table checks.
 */
@Entity
@Table(name = "task", schema = "dispatch")
public class Task {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false, insertable = false)
    public Long id;

    /** The durable identity, drawn by the database at the insert. */
    @Generated(event = EventType.INSERT)
    @Column(name = "uuid", nullable = false, insertable = false, updatable = false)
    public UUID uuid;

    @TenantId
    @Convert(converter = StringUuidConverter.class)
    @Column(name = "tenant_id", nullable = false)
    public String tenantId;

    @Column(name = "scope_id", nullable = false, updatable = false)
    public UUID scopeId;

    /** Eager, because every address renders the selector's name. */
    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "selector_id", nullable = false, updatable = false)
    public Selector selector;

    @Column(name = "number", nullable = false, updatable = false)
    public Integer number;

    /** 0 is the bracket root; 1..n are its children. */
    @Column(name = "sub", nullable = false, updatable = false)
    public Integer sub;

    // --- state and attributes ---------------------------------------------

    @Column(name = "state", nullable = false)
    private String state = TaskState.DRAFT.wireName();

    /** Absent in every state but {@code on_hold}. */
    @Column(name = "hold_reason")
    private @Nullable String holdReason;

    /** Absent in every state but {@code closed}. */
    @Column(name = "outcome")
    private @Nullable String outcome;

    /**
     * Whether the identity that delivered accepted the task itself: stamped
     * true or false by the acceptance and by nothing else, never changed
     * afterwards (V22). Absent where it is unknown: on every task not closed
     * as accepted, and on every task accepted before the stamp existed.
     */
    @Column(name = "self_accepted")
    private @Nullable Boolean selfAccepted;

    @Column(name = "state_changed_at", nullable = false)
    private Instant stateChangedAt;

    /** Absent on a task copied from a stock row that named nobody. */
    @Column(name = "state_changed_by")
    private @Nullable String stateChangedBy;

    /** The options of the pending question and whether free text is admitted. */
    @Column(name = "question_options", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private @Nullable Map<String, Object> questionOptions;

    /** Absent unless the task was deferred and is open. */
    @Column(name = "not_before")
    private @Nullable Instant notBefore;

    @Column(name = "lapse_count", nullable = false)
    private Short lapseCount = 0;

    /**
     * The instant of the first take-up: set by it and by nothing else, never
     * changed afterwards (V23). Absent where the task was not taken up under
     * a kernel that records it; {@link TaskSituation#of} derives the rest.
     */
    @Column(name = "first_taken_at")
    private @Nullable Instant firstTakenAt;

    /** Absent while nobody holds the task. */
    @Column(name = "holder_subject")
    private @Nullable String holderSubject;

    /** SHA-256 of the receipt, hex-encoded. Never the receipt. Absent with the holder. */
    @Column(name = "holder_receipt_hash")
    private @Nullable String holderReceiptHash;

    /** Absent unless the task is active under a lease. */
    @Column(name = "lease_expires_at")
    private @Nullable Instant leaseExpiresAt;

    /** Absent unless the closed task was curated into another. */
    @Column(name = "curated_in_id")
    private @Nullable Long curatedInId;

    // --- the commission's attributes, frozen at send (V18) -----------------

    @Column(name = "title", nullable = false)
    public String title;

    @Column(name = "apparatus", nullable = false)
    public String apparatus;

    @Column(name = "dispatch_metadata", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    public @Nullable Map<String, Object> dispatchMetadata;

    @Column(name = "return_metadata", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private @Nullable Map<String, Object> returnMetadata;

    // --- stamps -------------------------------------------------------------

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    public Instant createdAt;

    /** Absent on a task copied from a stock row that named nobody. */
    @Column(name = "created_by", updatable = false)
    public @Nullable String createdBy;

    /**
     * The instant of the last write that changed the task, from the service's
     * clock; the conflict token hangs on it (C10.1). Written here and nowhere
     * else: by {@link #begin} at the insert and by {@link #stampTheWrite} on
     * every update the persistence context finds the row changed in.
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Absent on a task copied from a stock row that named nobody. */
    @Column(name = "updated_by")
    public @Nullable String updatedBy;

    /**
     * The instant of the write in progress, handed in by {@link #touch}; never
     * stored. Absent outside a write.
     */
    @Transient
    private @Nullable Instant writtenAt;

    /** Whether the write in progress has stamped the token already ({@link #changedBeside}). */
    @Transient
    private boolean stamped;

    // ----------------------------------------------------------------------
    // Reading what is stored
    // ----------------------------------------------------------------------

    /** The stored state. Read through {@link TaskSituation#of} everywhere but there. */
    TaskState storedState() {
        return TaskState.fromWireName(state);
    }

    Optional<HoldReason> storedHoldReason() {
        return HoldReason.fromWireName(holdReason);
    }

    public Optional<Outcome> outcome() {
        return Outcome.fromWireName(outcome);
    }

    /**
     * Whether the identity that delivered accepted the task itself; empty where
     * that is unknown.
     */
    public Optional<Boolean> selfAccepted() {
        return Optional.ofNullable(selfAccepted);
    }

    public Optional<Map<String, Object>> questionOptions() {
        return Optional.ofNullable(questionOptions);
    }

    public Optional<Instant> notBefore() {
        return Optional.ofNullable(notBefore);
    }

    public int lapseCount() {
        return lapseCount;
    }

    /** The instant of the first take-up, where it was recorded. */
    public Optional<Instant> firstTakenAt() {
        return Optional.ofNullable(firstTakenAt);
    }

    /** The stored holder, lapsed or not. */
    Optional<String> storedHolder() {
        return Optional.ofNullable(holderSubject);
    }

    Optional<String> receiptHash() {
        return Optional.ofNullable(holderReceiptHash);
    }

    Optional<Instant> leaseExpiresAt() {
        return Optional.ofNullable(leaseExpiresAt);
    }

    public Optional<Long> curatedInId() {
        return Optional.ofNullable(curatedInId);
    }

    public Optional<Map<String, Object>> returnMetadata() {
        return Optional.ofNullable(returnMetadata);
    }

    public boolean isBracketRoot() {
        return sub == 0;
    }

    /** {@code sprint/149.2}; a task has no suffix. */
    public ExchangeAddress address() {
        return new ExchangeAddress(selector.name, number, sub);
    }

    /**
     * The conflict token: the instant of the last write that changed the task,
     * at the microsecond the column stores, as ISO-8601 text.
     */
    public String conflictToken() {
        return updatedAt.truncatedTo(ChronoUnit.MICROS).toString();
    }

    /** The instant of the last write that changed the task. */
    public Instant updatedAt() {
        return updatedAt;
    }

    // ----------------------------------------------------------------------
    // Writing. Each method writes one consistent row shape.
    // ----------------------------------------------------------------------

    /**
     * Opens a write: who writes, and the instant the token takes if the write
     * changes the row. Every write of the kernel passes here before it flushes.
     */
    void touch(String by, Instant at) {
        this.updatedBy = by;
        this.writtenAt = at;
    }

    /**
     * Moves the token for a change the row itself does not show: a draft's
     * text, which lives in its own row and is fenced by the task's token all
     * the same.
     */
    void changedBeside(Instant at) {
        this.updatedAt = following(at);
        this.stamped = true;
    }

    /**
     * Stamps the token on a write that changes the row, as {@code max(at,
     * previous + 1 µs)}, so that two writes in one microsecond yield two tokens
     * and a clock set back never yields an earlier one (C10.1).
     *
     * <p>Called by the persistence context only for an update it found the row
     * changed in, so a write that changes nothing leaves the token as it is
     * (DEC-0041). A changed row whose write did not pass {@link #touch} is a
     * write the token would not fence, and it is refused here rather than
     * stored.
     */
    @PreUpdate
    void stampTheWrite() {
        if (!stamped) {
            if (writtenAt == null) {
                throw new IllegalStateException("task " + id + " is written without touch(), "
                    + "and its conflict token would not move");
            }
            this.updatedAt = following(writtenAt);
        }
        this.stamped = false;
        this.writtenAt = null;
    }

    /** {@code max(at, previous + 1 µs)}, at the microsecond the column stores. */
    private Instant following(Instant at) {
        Instant now = at.truncatedTo(ChronoUnit.MICROS);
        if (updatedAt == null) {
            return now;
        }
        Instant floor = updatedAt.truncatedTo(ChronoUnit.MICROS).plus(1, ChronoUnit.MICROS);
        return now.isBefore(floor) ? floor : now;
    }

    /**
     * Enters the target state of a permitted transition.
     *
     * <p>Clears what the table admits only in another state, so the row
     * satisfies the shape constraints of V17 whatever state it came from:
     * {@code hold_reason} only in {@code on_hold}, {@code outcome} only in
     * {@code closed}, {@code not_before} only in {@code open}, the question's
     * options only with a question.
     */
    void enter(TaskState target, @Nullable HoldReason reason, @Nullable Outcome result,
               Instant at, String by) {
        this.state = target.wireName();
        this.holdReason = reason == null ? null : reason.wireName();
        this.outcome = result == null ? null : result.wireName();
        if (target != TaskState.OPEN) {
            this.notBefore = null;
        }
        if (reason != HoldReason.QUESTION) {
            this.questionOptions = null;
        }
        this.stateChangedAt = at;
        this.stateChangedBy = by;
    }

    /**
     * Stamps the creation: the state a new task begins in holds since now, and
     * the first token is now.
     */
    void begin(Instant at, String by) {
        this.stateChangedAt = at;
        this.stateChangedBy = by;
        this.createdBy = by;
        this.updatedBy = by;
        this.updatedAt = at.truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * Makes a new holder: receipt hash and lease, together. The first holder
     * also stamps the instant of the first take-up; every later one leaves it.
     */
    void award(String subject, String receipt, Instant leaseEnd, Instant at) {
        this.holderSubject = subject;
        this.holderReceiptHash = Receipt.hash(receipt);
        this.leaseExpiresAt = leaseEnd;
        if (firstTakenAt == null) {
            this.firstTakenAt = at;
        }
    }

    /**
     * Replaces the receipt of the holder and nothing else: holder, lease and
     * count stay. The earlier receipt no longer matches.
     */
    void reissue(String receipt) {
        this.holderReceiptHash = Receipt.hash(receipt);
    }

    /** Records one lapsed lease. The count rises when the next executor takes the task up. */
    void recordLapse() {
        this.lapseCount = (short) (lapseCount + 1);
    }

    /** Sets the lease; the holder stays. */
    void lease(Instant leaseEnd) {
        this.leaseExpiresAt = leaseEnd;
    }

    /** Ends the lease and keeps the holder: the work is paused or delivered. */
    void pause() {
        this.leaseExpiresAt = null;
    }

    /** Stamps whether the acceptance is the deliverer's own. Nothing changes the stamp. */
    void stampAcceptance(boolean byTheDeliverer) {
        this.selfAccepted = byTheDeliverer;
    }

    /** Drops holder, receipt hash and lease together. */
    void dropHolder() {
        this.holderSubject = null;
        this.holderReceiptHash = null;
        this.leaseExpiresAt = null;
    }

    void deferUntil(Instant instant) {
        this.notBefore = instant;
    }

    void ask(Map<String, Object> options) {
        this.questionOptions = options;
    }

    void writeReturnMetadata(@Nullable Map<String, Object> metadata) {
        this.returnMetadata = metadata;
    }

    void curateIn(@Nullable Long targetId) {
        this.curatedInId = targetId;
    }
}
