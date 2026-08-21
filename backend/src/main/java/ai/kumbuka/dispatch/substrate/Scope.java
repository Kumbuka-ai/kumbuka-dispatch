package ai.kumbuka.dispatch.substrate;

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
 * The unit of tenancy, as this service holds it.
 *
 * <p>This is substrate rather than domain. It carries no exchange semantics —
 * no role, no status, no freeze — and exists so that the tenancy axis has a
 * place to live and can be observed holding.
 *
 * <p>{@link #platformScopeId} is the scope's identity in the platform
 * directory. It is <strong>stored and never resolved</strong>: no foreign key,
 * no join, no view across a service boundary. A reference positions; it does
 * not prove, and it does not open.
 */
@Entity
@Table(name = "scope", schema = "dispatch")
public class Scope {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false)
    public UUID id;

    /**
     * The tenancy axis. Hibernate populates and filters on it — layer 1 —
     * and the row-level-security policy keys on the same column — layer 2.
     */
    @TenantId
    @Convert(converter = StringUuidConverter.class)
    @Column(name = "tenant_id", nullable = false)
    public String tenantId;

    /** Identity in the platform directory. Stored, never resolved. */
    @Column(name = "platform_scope_id", nullable = false)
    public UUID platformScopeId;

    /** The addressable name: {@code dispatch://<slug>/…}. */
    @Column(name = "slug", nullable = false)
    public String slug;

    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    public Instant updatedAt;
}
