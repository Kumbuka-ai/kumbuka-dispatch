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
 * One idempotency key a caller has spent on a task call.
 *
 * <p>What {@link SpentKey} is for an exchange, pointing at {@code task}: the
 * key, the call it was spent on, a digest of that call's arguments and the
 * task it answered with. Remembered for {@link SpentKey#REMEMBERED_FOR}.
 */
@Entity
@Table(name = "task_idempotency_key", schema = "dispatch")
public class SpentTaskKey {

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

    @Column(name = "task_id", nullable = false)
    public Long taskId;

    @Column(name = "caller_subject", nullable = false)
    public String callerSubject;

    @Column(name = "idempotency_key", nullable = false)
    public String key;

    @Column(name = "call_name", nullable = false)
    public String callName;

    @Column(name = "argument_digest", nullable = false)
    public String argumentDigest;

    @Column(name = "first_seen_at", nullable = false)
    public Instant firstSeenAt;

    /** Whether this row still speaks for the key at {@code now}. */
    public boolean stillStandsAt(Instant now) {
        return firstSeenAt != null && firstSeenAt.isAfter(now.minus(SpentKey.REMEMBERED_FOR));
    }

    /** Whether this row records the same call the caller is now making. */
    public boolean records(String call, String digest) {
        return callName.equals(call) && argumentDigest.equals(digest);
    }
}
