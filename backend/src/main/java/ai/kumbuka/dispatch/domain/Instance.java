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
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.TenantId;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * One run of a declared process: a row of {@code dispatch.instance} of the
 * store line.
 *
 * <p>The row an act is written to, and the row its writer locks so that the
 * sequence of the instance's acts is handed out one at a time. {@code start}
 * writes it with its selector, its number, the version in force and its title;
 * the title is never changed (D19). Its state and its resolution are the
 * projection of the acts that close it, and the only columns a later act
 * changes: the update names the changed columns alone, since the runtime role
 * holds no {@code UPDATE} on any other.
 */
@Entity
@DynamicUpdate
@Table(name = "instance", schema = "dispatch")
public class Instance {

    /** The two states of an instance, as the store names them (DEC-0046). */
    public enum State {
        RUNNING, CLOSED;

        /** The state as the store writes it. */
        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** How a closed instance was closed, as the store names it. */
    public enum Resolution {
        CONCLUDED, WITHDRAWN;

        /** The resolution as the store writes it. */
        public String wireName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false, insertable = false)
    public Long id;

    @TenantId
    @Convert(converter = StringUuidConverter.class)
    @Column(name = "tenant_id", nullable = false, updatable = false)
    public String tenantId;

    @Column(name = "scope_id", nullable = false, updatable = false)
    public UUID scopeId;

    @Column(name = "selector_id", updatable = false)
    private Long selectorId;

    @Column(name = "number", updatable = false)
    private Long number;

    @Column(name = "definition_version_id", updatable = false)
    private Long definitionVersionId;

    /** Absent on an instance copied from a stock that recorded none. */
    @Column(name = "title", updatable = false)
    private @Nullable String title;

    /** The sub the next task of the instance takes: from 1, never 0 (D24). */
    @Column(name = "next_sub")
    private int nextSub;

    @Column(name = "state")
    private String state;

    /** Absent while the instance runs. */
    @Column(name = "resolution")
    private @Nullable String resolution;

    /**
     * An instance of a selector under the number its counter handed out,
     * bound to the version in force, running, with no task yet.
     */
    static Instance started(UUID scopeId, long selectorId, long number, long versionId,
                            String title) {
        Instance instance = new Instance();
        instance.scopeId = scopeId;
        instance.selectorId = selectorId;
        instance.number = number;
        instance.definitionVersionId = versionId;
        instance.title = title;
        instance.nextSub = 1;
        instance.state = State.RUNNING.wireName();
        return instance;
    }

    /**
     * Hands out the sub of the next task and moves the counter on. Called
     * under the lock of the instance's row, so two tasks created at once take
     * two subs, one after the other, without a gap.
     */
    int takeSub() {
        int sub = nextSub;
        nextSub = sub + 1;
        return sub;
    }

    /** Closes the instance: its state and its resolution, which the closing act names. */
    void close(Resolution how) {
        this.state = State.CLOSED.wireName();
        this.resolution = how.wireName();
    }

    /** Whether the instance still runs: every act on it is admitted only then. */
    public boolean running() {
        return State.RUNNING.wireName().equals(state);
    }

    public long number() {
        return number;
    }

    public long selectorId() {
        return selectorId;
    }

    /** The version of its selector's definition the instance is bound to. */
    public long definitionVersionId() {
        return definitionVersionId;
    }

    /** The title its start gave it; empty on an instance whose stock recorded none. */
    public Optional<String> title() {
        return Optional.ofNullable(title);
    }

    /** The state as the store wrote it. */
    public String state() {
        return state;
    }

    /** How it was closed, as the store wrote it; empty while it runs. */
    public Optional<String> resolution() {
        return Optional.ofNullable(resolution);
    }
}
