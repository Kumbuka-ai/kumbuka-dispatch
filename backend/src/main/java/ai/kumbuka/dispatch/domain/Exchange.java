package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.tenancy.StringUuidConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.generator.EventType;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One exchange: the commission, its answer, and the state of that exchange.
 *
 * <p><strong>Two roles, one identity.</strong> The dispatch fields and the
 * handover fields live on the same row because they are two roles of one
 * thing, not two things that reference each other. Splitting them would give
 * the exchange two places to carry a state and two rows to keep in step, and
 * the predecessor of this service did exactly that — its pair invariant then
 * had to be checked beside the transition rather than at it, which made a
 * mismatched pair permanently unclosable.
 *
 * <p><strong>There is no status setter.</strong> The field moves only through
 * the transition methods below, each of which is one verb, and each of which
 * checks its own precondition. A setter would be a way to reach any state from
 * any other, and the freeze is a rule about transitions rather than about
 * fields — so a generic write is not a shortcut around one check but around
 * all of them.
 */
@Entity
@Table(name = "exchange", schema = "dispatch")
public class Exchange {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    public UUID id;

    @TenantId
    @Convert(converter = StringUuidConverter.class)
    @Column(name = "tenant_id", nullable = false)
    public String tenantId;

    /** The platform scope this exchange belongs to. Stored, never resolved from here. */
    @Column(name = "scope_id", nullable = false)
    public UUID scopeId;

    // --- identity ---------------------------------------------------------

    @Column(name = "selector", nullable = false, updatable = false)
    public String selector;

    @Column(name = "number", nullable = false, updatable = false)
    public Integer number;

    /** 0 is the bracket itself; 1..n are its children. */
    @Column(name = "sub", nullable = false, updatable = false)
    public Integer sub;

    /** A single letter when this row is an addendum, null otherwise. */
    @Column(name = "addendum_suffix", updatable = false)
    public String addendumSuffix;

    // --- state ------------------------------------------------------------

    @Column(name = "status", nullable = false)
    private String status = ExchangeStatus.DRAFT.wireName();

    // --- the dispatch role ------------------------------------------------

    @Column(name = "title", nullable = false)
    public String title;

    @Column(name = "body", nullable = false)
    public String body = "";

    @Column(name = "apparatus", nullable = false)
    public String apparatus;

    @Column(name = "dispatch_date", nullable = false)
    public LocalDate dispatchDate;

    @Column(name = "sent_at")
    private Instant sentAt;

    // --- the handover role ------------------------------------------------

    @Column(name = "handover_body")
    private String handoverBody;

    @Column(name = "ratified_at")
    private Instant ratifiedAt;

    // --- technical fields, server-derived ---------------------------------

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "created_by", updatable = false)
    public String createdBy;

    /**
     * Written by the database on every update, and read-only here.
     *
     * <p>{@code @Generated} is what tells Hibernate to fetch the value back
     * rather than to send one: without it the entity would carry whatever it
     * last saw, and an insert followed by an update in the same transaction
     * would try to write a null into a not-null column.
     */
    @Generated(event = {EventType.INSERT, EventType.UPDATE})
    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    public Instant updatedAt;

    @Column(name = "updated_by")
    public String updatedBy;

    // ----------------------------------------------------------------------
    // Reading the state
    // ----------------------------------------------------------------------

    public ExchangeStatus status() {
        return ExchangeStatus.fromWireName(status);
    }

    public boolean frozen() {
        return sentAt != null;
    }

    public Instant sentAt() {
        return sentAt;
    }

    public String handoverBody() {
        return handoverBody;
    }

    public Instant ratifiedAt() {
        return ratifiedAt;
    }

    public boolean isAddendum() {
        return addendumSuffix != null;
    }

    /** True for the exchange the bracket is derived from. */
    public boolean isBracketRoot() {
        return sub == 0 && addendumSuffix == null;
    }

    /** {@code sprint/149.2}, or {@code sprint/149.0a} for an addendum. */
    public String address() {
        return selector + "/" + number + "." + sub
            + (addendumSuffix == null ? "" : addendumSuffix);
    }

    // ----------------------------------------------------------------------
    // The transitions. One method per verb, and no other way in.
    // ----------------------------------------------------------------------

    /**
     * Applies a verb.
     *
     * @return true when the transition happened, false when it was a
     *         successful no-op because the exchange was already there
     * @throws DispatchException when the verb is not permitted from the
     *         current status
     */
    boolean apply(Transition transition) {
        ExchangeStatus current = status();

        // Re-terminating an already terminal exchange succeeds and changes
        // nothing. Without this, a sequence across two services that is
        // retried after a partial failure can never complete: the retry finds
        // the exchange it already terminated and would be told it is too late.
        if (current == transition.to() && transition.idempotentWhenAlreadyThere()) {
            return false;
        }

        if (!transition.permittedFrom(current)) {
            throw new DispatchException(
                DispatchException.Reason.TRANSITION_NOT_PERMITTED,
                "%s is %s and cannot %s; %s is reachable only from %s".formatted(
                    address(), current.wireName(), transition.verb(),
                    transition.to().wireName(),
                    transition.from().stream().map(ExchangeStatus::wireName).sorted().toList()));
        }

        status = transition.to().wireName();
        return true;
    }

    /**
     * Freezes the dispatch. Called only from the send verb, which is also
     * where the timestamp comes from — the entity does not read the clock,
     * so a test can state when something was sent.
     */
    void freezeDispatch(Instant at) {
        this.sentAt = at;
    }

    /** Writes and freezes the answer, in the same call as the transition to returned. */
    void freezeHandover(String answer, Instant at) {
        this.handoverBody = answer;
        this.ratifiedAt = at;
    }
}
