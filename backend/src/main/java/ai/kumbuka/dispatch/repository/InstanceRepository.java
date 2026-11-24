/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.repository;

import ai.kumbuka.dispatch.domain.ExternalReference;
import ai.kumbuka.dispatch.domain.Instance;
import ai.kumbuka.dispatch.domain.Selector;
import ai.kumbuka.dispatch.domain.Task;
import ai.kumbuka.dispatch.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every statement the acts on an instance issue against {@code selector},
 * {@code instance}, {@code task} and the rows an act writes beside itself:
 * its return, its references, its field values with their projection, and
 * its content.
 *
 * <p>JPA lives in this package, refusals stay above it, and an absent row is
 * an empty {@link Optional}. Every statement goes through the ORM, so the
 * tenant discriminator applies to each, and through a transaction the tenancy
 * binding has bound, so the row-level security of the store applies as well.
 * Every statement names the scope beside the tenant the discriminator adds.
 *
 * <p>A locking read answers the row as it stands under the lock ({@link
 * #underLock}): the persistence context would otherwise answer an object it
 * read before the lock.
 */
@ApplicationScoped
@TenantBound
public class InstanceRepository {

    private static final String P_SCOPE = "scope";
    private static final String P_INSTANCE = "instance";
    private static final String P_SELECTOR = "selector";

    @Inject EntityManager em;

    /**
     * The selector of this name in a scope, locked for the caller's
     * transaction: the lock that hands out the numbers of its instances one
     * at a time. A second start under the selector waits here until the first
     * one's transaction ends, and then reads the counter the first moved on.
     */
    @Transactional
    public Optional<Selector> lockSelector(UUID scopeId, String selector) {
        return em.createQuery("""
                SELECT s FROM Selector s WHERE s.scopeId = :scope AND s.name = :selector
                """, Selector.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_SELECTOR, selector)
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .getResultList()
            .stream()
            .findFirst()
            .map(this::underLock);
    }

    /**
     * The instance of a selector by its number, locked for the caller's
     * transaction: every act on an instance decides on the row as it stands
     * under this lock, the one the writer of its acts takes as well.
     */
    @Transactional
    public Optional<Instance> lockInstance(UUID scopeId, String selector, long number) {
        return em.createQuery("""
                SELECT i FROM Instance i, Selector s
                WHERE s.id = i.selectorId AND s.scopeId = :scope AND i.scopeId = :scope
                  AND s.name = :selector AND i.number = :number
                """, Instance.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_SELECTOR, selector)
            .setParameter("number", number)
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .getResultList()
            .stream()
            .findFirst()
            .map(this::underLock);
    }

    /**
     * The tasks of an instance that are unfinished, in the order of their
     * sub, locked for the caller's transaction: a task closed by another
     * transaction before the lock is read as it stands, closed, and is no
     * longer among them.
     */
    @Transactional
    public List<Task> lockUnfinishedTasks(UUID scopeId, long instanceId) {
        return em.createQuery("""
                SELECT t FROM Task t
                WHERE t.scopeId = :scope AND t.instanceId = :instance
                ORDER BY t.sub
                """, Task.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_INSTANCE, instanceId)
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .getResultList()
            .stream()
            .map(this::underLock)
            .filter(Task::unfinished)
            .toList();
    }

    /**
     * The reference of an instance of a kind and value that holds, ended by
     * no act; empty where the instance carries none. At most one holds, by a
     * unique index of the store.
     */
    @Transactional
    public Optional<ExternalReference> currentReference(UUID scopeId, long instanceId,
                                                        ExternalReference.Kind kind,
                                                        String value) {
        return em.createQuery("""
                SELECT r FROM ExternalReference r
                WHERE r.scopeId = :scope AND r.instanceId = :instance
                  AND r.kind = :kind AND r.value = :value AND r.endedByActId IS NULL
                """, ExternalReference.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_INSTANCE, instanceId)
            .setParameter("kind", kind.wireName())
            .setParameter("value", value)
            .getResultList()
            .stream()
            .findFirst();
    }

    /**
     * Writes a row an act writes beside itself, and flushes at once, so a
     * refusal of the store falls in this call and the row has its surrogate.
     */
    @Transactional
    public <T> T insert(T row) {
        em.persist(row);
        em.flush();
        return row;
    }

    /** Writes the changes made to the managed rows of this transaction at once. */
    @Transactional
    public void flush() {
        em.flush();
    }

    /** The row as it stands under the lock just taken, also where this transaction read it before. */
    private <T> T underLock(T entity) {
        em.refresh(entity);
        return entity;
    }
}
