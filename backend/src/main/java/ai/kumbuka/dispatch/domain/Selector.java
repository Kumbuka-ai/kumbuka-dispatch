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
 * A bracket name, declared before use.
 *
 * <p>Never created by first use and never by a verb. A typo must not silently
 * open a namespace, and a selector later carries things a string cannot — an
 * aspect, a script, configuration that steers an agent. It can never be
 * renamed either, because every address ever issued depends on it; withdrawal
 * is a status, and only a never-used selector may be withdrawn.
 */
@Entity
@Table(name = "selector", schema = "dispatch")
public class Selector {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    public UUID id;

    @TenantId
    @Convert(converter = StringUuidConverter.class)
    @Column(name = "tenant_id", nullable = false)
    public String tenantId;

    @Column(name = "scope_id", nullable = false)
    public UUID scopeId;

    @Column(name = "name", nullable = false, updatable = false)
    public String name;

    @Column(name = "withdrawn", nullable = false)
    public Boolean withdrawn = Boolean.FALSE;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    public Instant createdAt;
}
