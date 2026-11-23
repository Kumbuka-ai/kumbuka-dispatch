/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.repository;

import ai.kumbuka.dispatch.domain.Act;
import ai.kumbuka.dispatch.domain.Instance;
import ai.kumbuka.dispatch.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * Every statement the writing of an act issues against {@code instance} and
 * {@code act} of the store line.
 *
 * <p>JPA lives in this package, refusals stay above it, and an absent row is
 * an empty {@link Optional}. Every statement goes through the ORM, so the
 * tenant discriminator applies to each, and through a transaction the tenancy
 * binding has bound, so the row-level security of the store applies as well.
 */
@ApplicationScoped
@TenantBound
public class ActRepository {

    private static final String P_SCOPE = "scope";
    private static final String P_INSTANCE = "instance";

    @Inject EntityManager em;

    /**
     * The instance in a scope, locked for the caller's transaction, as the row
     * stands under the lock: see {@link #underLock}.
     *
     * <p>The lock is what serialises the writers of one instance: a second
     * writer waits here until the first one's transaction ends, and then reads
     * the sequence the first one wrote.
     */
    @Transactional
    public Optional<Instance> lockInstance(UUID scopeId, long instanceId) {
        return em.createQuery("""
                SELECT i FROM Instance i WHERE i.id = :instance AND i.scopeId = :scope
                """, Instance.class)
            .setParameter(P_INSTANCE, instanceId)
            .setParameter(P_SCOPE, scopeId)
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .getResultList()
            .stream()
            .findFirst()
            .map(this::underLock);
    }

    /** The highest sequence among the acts of an instance; 0 where it has none. */
    @Transactional
    public int lastSequence(UUID scopeId, long instanceId) {
        Integer last = em.createQuery("""
                SELECT MAX(a.sequence) FROM Act a
                WHERE a.instanceId = :instance AND a.scopeId = :scope
                """, Integer.class)
            .setParameter(P_INSTANCE, instanceId)
            .setParameter(P_SCOPE, scopeId)
            .getSingleResult();
        return last == null ? 0 : last;
    }

    /** Appends an act and writes it at once, so a refusal of the store falls in this call. */
    @Transactional
    public Act append(Act act) {
        em.persist(act);
        em.flush();
        return act;
    }

    /** The act of this surrogate in one scope, if there is one. */
    @Transactional
    public Optional<Act> find(UUID scopeId, long actId) {
        return em.createQuery("""
                SELECT a FROM Act a WHERE a.id = :act AND a.scopeId = :scope
                """, Act.class)
            .setParameter("act", actId)
            .setParameter(P_SCOPE, scopeId)
            .getResultList()
            .stream()
            .findFirst();
    }

    /**
     * The row as it stands under the lock just taken, also where this
     * transaction read it before: the persistence context would otherwise
     * answer the object it holds, read before the lock.
     */
    private <T> T underLock(T entity) {
        em.refresh(entity);
        return entity;
    }
}
