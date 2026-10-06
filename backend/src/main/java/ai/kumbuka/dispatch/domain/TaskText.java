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
import org.hibernate.annotations.TenantId;
import org.hibernate.generator.EventType;

import java.time.Instant;
import java.util.UUID;

/**
 * One text of a task: the commission, an answer, a question, the answer to a
 * question, a remark, or an addendum to one of them.
 *
 * <p>Mutable while its task is a draft and unchanged from the moment the task
 * is sent; the database refuses the change ({@code task_text_frozen_after_send},
 * V17). An addendum is a row with a letter suffix on the type it supplements,
 * and has no title.
 */
@Entity
@Table(name = "task_text", schema = "dispatch")
public class TaskText {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false, insertable = false)
    public Long id;

    @TenantId
    @Convert(converter = StringUuidConverter.class)
    @Column(name = "tenant_id", nullable = false)
    public String tenantId;

    @Column(name = "scope_id", nullable = false, updatable = false)
    public UUID scopeId;

    /** The task by its surrogate; the key over tenant, scope and task binds it. */
    @Column(name = "task_id", nullable = false, updatable = false)
    public Long taskId;

    @Column(name = "text_type", nullable = false, updatable = false)
    private String textType;

    /** Empty on a base text; a letter on an addendum. */
    @Column(name = "addendum_suffix", updatable = false)
    public String addendumSuffix;

    @Column(name = "text", nullable = false)
    public String text;

    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "created_by", updatable = false)
    public String createdBy;

    public TextType type() {
        return TextType.fromWireName(textType);
    }

    void type(TextType type) {
        this.textType = type.wireName();
    }

    public boolean isAddendum() {
        return addendumSuffix != null;
    }
}
