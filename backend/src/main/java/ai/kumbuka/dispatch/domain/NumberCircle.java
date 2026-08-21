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

import java.util.UUID;

/**
 * The counter for one scope and selector.
 *
 * <p>Consumed inside the transaction that inserts the exchange, so a rolled
 * back creation returns its number. That is what removes the class "burned
 * number" structurally: there is no moment at which a number exists and its
 * object does not.
 */
@Entity
@Table(name = "number_circle", schema = "dispatch")
public class NumberCircle {

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

    @Column(name = "selector", nullable = false)
    public String selector;

    @Column(name = "next_number", nullable = false)
    public Integer nextNumber;
}
