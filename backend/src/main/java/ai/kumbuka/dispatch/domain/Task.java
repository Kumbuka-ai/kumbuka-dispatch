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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
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

    @Column(name = "hold_reason")
    private String holdReason;

    @Column(name = "outcome")
    private String outcome;

    @Column(name = "state_changed_at", nullable = false)
    private Instant stateChangedAt;

    @Column(name = "state_changed_by")
    private String stateChangedBy;

    /** The options of the pending question and whether free text is admitted. */
    @Column(name = "question_options", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> questionOptions;

    @Column(name = "not_before")
    private Instant notBefore;

    @Column(name = "lapse_count", nullable = false)
    private Short lapseCount = 0;

    @Column(name = "holder_subject")
    private String holderSubject;

    /** SHA-256 of the receipt, hex-encoded. Never the receipt. */
    @Column(name = "holder_receipt_hash")
    private String holderReceiptHash;

    @Column(name = "lease_expires_at")
    private Instant leaseExpiresAt;

    @Column(name = "curated_in_id")
    private Long curatedInId;

    // --- the commission's attributes, frozen at send (V18) -----------------

    @Column(name = "title", nullable = false)
    public String title;

    @Column(name = "apparatus", nullable = false)
    public String apparatus;

    @Column(name = "dispatch_metadata", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    public Map<String, Object> dispatchMetadata;

    @Column(name = "return_metadata", columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> returnMetadata;

    // --- stamps -------------------------------------------------------------

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "created_by", updatable = false)
    public String createdBy;

    /**
     * The instant of the last write that changed the task, from the service's
     * clock; the conflict token hangs on it (C10.1). Written here and nowhere
     * else: by {@link #begin} at the insert and by {@link #stampTheWrite} on
     * every update the persistence context finds the row changed in.
     */
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    public String updatedBy;

    /** The instant of the write in progress, handed in by {@link #touch}; never stored. */
    @Transient
    private Instant writtenAt;

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

    HoldReason storedHoldReason() {
        return HoldReason.fromWireName(holdReason);
    }

    public Outcome outcome() {
        return Outcome.fromWireName(outcome);
    }

    public Map<String, Object> questionOptions() {
        return questionOptions;
    }

    public Instant notBefore() {
        return notBefore;
    }

    public int lapseCount() {
        return lapseCount;
    }

    /** The stored holder, lapsed or not. */
    String storedHolder() {
        return holderSubject;
    }

    String receiptHash() {
        return holderReceiptHash;
    }

    Instant leaseExpiresAt() {
        return leaseExpiresAt;
    }

    public Long curatedInId() {
        return curatedInId;
    }

    public Map<String, Object> returnMetadata() {
        return returnMetadata;
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
    void enter(TaskState target, HoldReason reason, Outcome result, Instant at, String by) {
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

    /** Makes a new holder: receipt hash and lease, together. */
    void award(String subject, String receipt, Instant leaseEnd) {
        this.holderSubject = subject;
        this.holderReceiptHash = Receipt.hash(receipt);
        this.leaseExpiresAt = leaseEnd;
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

    void writeReturnMetadata(Map<String, Object> metadata) {
        this.returnMetadata = metadata;
    }

    void curateIn(Long targetId) {
        this.curatedInId = targetId;
    }
}
