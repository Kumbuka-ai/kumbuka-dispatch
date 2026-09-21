package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.tenancy.StringUuidConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.TenantId;

import java.time.Instant;
import java.util.UUID;

/**
 * One idempotency key a caller has spent, and what the call it named answered
 * with.
 *
 * <p>The row is what makes section 3's promise keepable: "a call repeated by
 * the same caller in the same scope with the same key, within 24 hours of the
 * first, creates nothing and answers with the answer the first call produced,
 * in the object's current state". Without it the argument was accepted and
 * discarded, which is the defect class the whole assistant surface exists to
 * remove.
 *
 * <h2>Why the arguments are a digest and not the arguments</h2>
 *
 * The rule needs one bit: same call again, or a different call under a key
 * already spent. Storing the arguments to answer it would put a commission's
 * title and text in a second table — a second place the ops boundary has to
 * withhold them from, for a comparison that never reads them. A SHA-256 over
 * the canonical form answers the question and carries nothing readable.
 *
 * <h2>Why the answer is an identity and not a projection</h2>
 *
 * "In the object's current state" is the contract's own wording. A stored
 * projection would answer with the state at the first call and would be wrong
 * the moment anything happened to the exchange; the identity is re-read, so
 * the repeat is a read of the object as it stands.
 */
@Entity
@Table(name = "idempotency_key", schema = "dispatch")
public class SpentKey {

    /** How long a spent key is remembered. Section 3 fixes it at 24 hours. */
    public static final java.time.Duration REMEMBERED_FOR = java.time.Duration.ofHours(24);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false, insertable = false)
    public Long id;

    @TenantId
    @Convert(converter = StringUuidConverter.class)
    @Column(name = "tenant_id", nullable = false)
    public String tenantId;

    @Column(name = "scope_id", nullable = false)
    public UUID scopeId;

    /** The authenticated caller. Two callers may spend the same key. */
    @Column(name = "caller_subject", nullable = false)
    public String callerSubject;

    @Column(name = "idempotency_key", nullable = false)
    public String key;

    /** The call the key was spent on; the same key on another call is a reuse. */
    @Column(name = "call_name", nullable = false)
    public String callName;

    @Column(name = "argument_digest", nullable = false)
    public String argumentDigest;

    /** What the first call answered with, by durable identity (ADR-0014). */
    @Column(name = "exchange_id", nullable = false)
    public Long exchangeId;

    @Column(name = "first_seen_at", nullable = false)
    public Instant firstSeenAt;

    /**
     * Whether this row still speaks for the key at {@code now}.
     *
     * <p>An entry older than the window is not deleted — there is no delete
     * verb in this service — it simply stops counting, and the next call under
     * that key overwrites it. So expiry is a question asked at read time and
     * never a background job whose absence would silently extend the window.
     */
    public boolean stillStandsAt(Instant now) {
        return firstSeenAt != null && firstSeenAt.isAfter(now.minus(REMEMBERED_FOR));
    }

    /** Whether this row records the same call the caller is now making. */
    public boolean records(String call, String digest) {
        return callName.equals(call) && argumentDigest.equals(digest);
    }
}
