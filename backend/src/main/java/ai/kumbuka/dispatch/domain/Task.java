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
import jakarta.persistence.Table;
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

    /** Written by the database on every update; the conflict token hangs on it. */
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    public Instant updatedAt;

    @Column(name = "updated_by")
    public String updatedBy;

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

    public Instant stateChangedAt() {
        return stateChangedAt;
    }

    public String stateChangedBy() {
        return stateChangedBy;
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
     * The conflict token: the last write, truncated to what the column stores.
     *
     * <p>Null before the first flush: the database writes {@code updated_at},
     * and a task not yet inserted has none.
     */
    public String conflictToken() {
        return updatedAt == null ? null : updatedAt.truncatedTo(ChronoUnit.MICROS).toString();
    }

    // ----------------------------------------------------------------------
    // Writing. Each method writes one consistent row shape.
    // ----------------------------------------------------------------------

    /** Stamps who changed the row last; every write of the kernel passes here. */
    void touch(String by) {
        this.updatedBy = by;
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

    /** Stamps the creation: the state a new task begins in holds since now. */
    void begin(Instant at, String by) {
        this.stateChangedAt = at;
        this.stateChangedBy = by;
        this.createdBy = by;
        this.updatedBy = by;
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
