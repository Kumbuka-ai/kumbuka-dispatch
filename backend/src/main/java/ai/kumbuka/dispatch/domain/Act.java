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
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One change of the control of an instance: a row of {@code dispatch.act} of
 * the store line, appended and never changed.
 *
 * <p>Mapped with what the writing of an act needs: the instance and, where the
 * act is about one, the task; the sequence within the instance; the kind and
 * the state it leads to; and its provenance. The payload columns of the kinds
 * that carry one are mapped by the change that writes that kind. Every column
 * is written once, at the insert: the runtime role holds no {@code UPDATE} on
 * the table, and the mapping says so.
 */
@Entity
@Table(name = "act", schema = "dispatch")
public class Act {

    /** The kinds of act, as the store names them. */
    public enum Kind {
        START, SEND, RECALL, CLAIM, RENEW, RELEASE, DEFER, ASK, ANSWER, HOLD, RESUME, DELIVER,
        REWORK, ACCEPT, REJECT, FAIL, WITHDRAW, CONCLUDE, ANNOTATE, TAG, UNTAG, REFER, UNREFER,
        RELATE, UNRELATE, IMPORT;

        /** The kind as the store writes it. */
        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The states an act leads its instance or its task to, as the store names them. */
    public enum State {
        RUNNING, CLOSED, DRAFT, RECALLED, OPEN, ACTIVE, ON_HOLD, DELIVERED;

        /** The state as the store writes it. */
        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Absent until the act is written: the store draws it at the insert. */
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false, insertable = false)
    private @Nullable Long id;

    /** Absent until the act is written: the persistence provider sets the bound tenant. */
    @TenantId
    @Convert(converter = StringUuidConverter.class)
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private @Nullable String tenantId;

    @Column(name = "scope_id", nullable = false, updatable = false)
    private UUID scopeId;

    @Column(name = "instance_id", nullable = false, updatable = false)
    private Long instanceId;

    /** The place of this act among the acts of its instance, from 1. */
    @Column(name = "sequence", nullable = false, updatable = false)
    private Integer sequence;

    /** Absent on an act about the instance alone. */
    @Column(name = "task_id", updatable = false)
    private @Nullable Long taskId;

    @Column(name = "kind", nullable = false, updatable = false)
    private String kind;

    @Column(name = "actor_subject", nullable = false, updatable = false)
    private String actorSubject;

    /** Absent where the person acted directly. */
    @Column(name = "actor_owner_subject", updatable = false)
    private @Nullable String actorOwnerSubject;

    /** Absent on an act copied from a stock that recorded none. */
    @Column(name = "permission", updatable = false)
    private @Nullable String permission;

    /** Absent on an act copied from a stock that recorded none. */
    @Column(name = "path", updatable = false)
    private @Nullable String path;

    /** Absent where the call carried no authentication level. */
    @Column(name = "authentication_level", updatable = false)
    private @Nullable String authenticationLevel;

    @Column(name = "at", nullable = false, updatable = false)
    private Instant at;

    @Column(name = "to_state", nullable = false, updatable = false)
    private String toState;

    /** For the persistence provider only. */
    protected Act() {
    }

    private Act(Instance instance, int sequence, Kind kind, State toState,
                @Nullable Long taskId, Provenance provenance, Instant at) {
        this.scopeId = instance.scopeId;
        this.instanceId = instance.id;
        this.sequence = sequence;
        this.kind = kind.wireName();
        this.toState = toState.wireName();
        this.taskId = taskId;
        this.actorSubject = provenance.actorSubject();
        this.actorOwnerSubject = provenance.actorOwnerSubject();
        this.permission = provenance.permission().wireName();
        this.path = provenance.path().wireName();
        this.authenticationLevel = provenance.authenticationLevel();
        this.at = at;
    }

    /**
     * An act on an instance, at the place in its sequence the writer handed
     * out under the instance's lock. The tenant is the bound one, set by the
     * persistence provider from the tenant context.
     *
     * @param taskId the task the act is about; absent on an act about the
     *               instance alone
     */
    static Act of(Instance instance, int sequence, Kind kind, State toState,
                  @Nullable Long taskId, Provenance provenance, Instant at) {
        return new Act(instance, sequence, kind, toState, taskId, provenance, at);
    }

    /** The act's surrogate, drawn by the store at the insert. */
    public Long id() {
        return Objects.requireNonNull(id, "an act has an id once it is written");
    }

    /** The tenant the act belongs to, set at the insert. */
    public UUID tenantId() {
        return UUID.fromString(Objects.requireNonNull(tenantId,
            "an act has a tenant once it is written"));
    }

    public UUID scopeId() {
        return scopeId;
    }

    public Long instanceId() {
        return instanceId;
    }

    public int sequence() {
        return sequence;
    }

    /** The task the act is about; empty on an act about the instance alone. */
    public Optional<Long> taskId() {
        return Optional.ofNullable(taskId);
    }

    /** The kind as the store wrote it. */
    public String kind() {
        return kind;
    }

    /** The state the act leads to, as the store wrote it. */
    public String toState() {
        return toState;
    }

    public Instant at() {
        return at;
    }

    public String actorSubject() {
        return actorSubject;
    }

    /** The person the acting identity belongs to; empty where the person acted directly. */
    public Optional<String> actorOwnerSubject() {
        return Optional.ofNullable(actorOwnerSubject);
    }

    /** The permission the call checked, as the store wrote it; empty where none was recorded. */
    public Optional<String> permission() {
        return Optional.ofNullable(permission);
    }

    /** The path the call arrived by, as the store wrote it; empty where none was recorded. */
    public Optional<String> path() {
        return Optional.ofNullable(path);
    }

    /** The authentication level of the session; empty where the call carried none. */
    public Optional<String> authenticationLevel() {
        return Optional.ofNullable(authenticationLevel);
    }
}
