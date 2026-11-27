/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.repository;

import ai.kumbuka.dispatch.domain.Draft;
import ai.kumbuka.dispatch.domain.DraftCriterion;
import ai.kumbuka.dispatch.domain.DraftFreeFields;
import ai.kumbuka.dispatch.domain.DraftText;
import ai.kumbuka.dispatch.domain.IdempotencyRecord;
import ai.kumbuka.dispatch.domain.Instance;
import ai.kumbuka.dispatch.domain.Task;
import ai.kumbuka.dispatch.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceException;
import jakarta.transaction.Transactional;
import org.hibernate.exception.ConstraintViolationException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every statement the calls on a task in draft issue against {@code task},
 * the draft workspace -- {@code draft}, {@code draft_criterion}, {@code
 * draft_free_fields} and {@code draft_text} -- and the ledger of idempotency
 * keys, {@code idempotency_key}.
 *
 * <p>JPA lives in this package, refusals stay above it, and an absent row is
 * an empty {@link Optional}. Every statement goes through the ORM, so the
 * tenant discriminator applies to each, and through a transaction the tenancy
 * binding has bound, so the row-level security of the store applies as well.
 * Every statement names the scope beside the tenant the discriminator adds.
 *
 * <p>A locking read answers the row as it stands under the lock ({@link
 * #underLock}). No key of the store deletes along, so the workspace is deleted
 * children first: the texts, then the criteria and the free fields, then the
 * draft; the task after its draft.
 */
@ApplicationScoped
@TenantBound
public class TaskRepository {

    private static final String P_SCOPE = "scope";
    private static final String P_DRAFT = "draft";
    private static final String P_TASK = "task";
    private static final String P_CALLER = "caller";
    private static final String P_INSTANCE = "instance";

    /** The constraint of the store that holds one key per caller and scope. */
    private static final String KEY_OF_THE_CALLER = "uq_idempotency_key_caller_key";

    @Inject EntityManager em;

    /** The instance of a selector by its number, without a lock; empty where the scope holds none. */
    @Transactional
    public Optional<Instance> instance(UUID scopeId, String selector, long number) {
        return em.createQuery("""
                SELECT i FROM Instance i, Selector s
                WHERE s.id = i.selectorId AND s.scopeId = :scope AND i.scopeId = :scope
                  AND s.name = :selector AND i.number = :number
                """, Instance.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter("selector", selector)
            .setParameter("number", number)
            .getResultList()
            .stream()
            .findFirst();
    }

    /** An instance by its surrogate, without a lock; empty where the scope holds none. */
    @Transactional
    public Optional<Instance> instance(UUID scopeId, long instanceId) {
        return em.createQuery("""
                SELECT i FROM Instance i WHERE i.scopeId = :scope AND i.id = :instance
                """, Instance.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_INSTANCE,instanceId)
            .getResultList()
            .stream()
            .findFirst();
    }

    /** The draft of a task, without a lock; empty where the task has none. */
    @Transactional
    public Optional<Draft> draft(UUID scopeId, long taskId) {
        return em.createQuery("""
                SELECT d FROM Draft d WHERE d.scopeId = :scope AND d.taskId = :task
                """, Draft.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_TASK, taskId)
            .getResultList()
            .stream()
            .findFirst();
    }

    /** The task of an instance by its sub, without a lock; empty where the instance holds none. */
    @Transactional
    public Optional<Task> task(UUID scopeId, long instanceId, int sub) {
        return em.createQuery("""
                SELECT t FROM Task t
                WHERE t.scopeId = :scope AND t.instanceId = :instance AND t.sub = :sub
                """, Task.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_INSTANCE,instanceId)
            .setParameter("sub", sub)
            .getResultList()
            .stream()
            .findFirst();
    }

    /** A task by its surrogate, without a lock; empty where the scope holds none. */
    @Transactional
    public Optional<Task> task(UUID scopeId, long taskId) {
        return em.createQuery("""
                SELECT t FROM Task t WHERE t.scopeId = :scope AND t.id = :task
                """, Task.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_TASK, taskId)
            .getResultList()
            .stream()
            .findFirst();
    }

    /**
     * The task of an instance by its sub, locked for the caller's
     * transaction: every call on a task decides on the row as it stands under
     * this lock. A task another transaction deleted before the lock is gone.
     */
    @Transactional
    public Optional<Task> lockTask(UUID scopeId, long instanceId, int sub) {
        return em.createQuery("""
                SELECT t FROM Task t
                WHERE t.scopeId = :scope AND t.instanceId = :instance AND t.sub = :sub
                """, Task.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_INSTANCE,instanceId)
            .setParameter("sub", sub)
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .getResultList()
            .stream()
            .findFirst()
            .map(this::underLock);
    }

    /**
     * The draft of a task, locked for the caller's transaction: the lock
     * under which its conflict token is compared and moved on, and under
     * which it is sent. Empty where the task has none: it was sent or deleted.
     */
    @Transactional
    public Optional<Draft> lockDraft(UUID scopeId, long taskId) {
        return em.createQuery("""
                SELECT d FROM Draft d WHERE d.scopeId = :scope AND d.taskId = :task
                """, Draft.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_TASK, taskId)
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .getResultList()
            .stream()
            .findFirst()
            .map(this::underLock);
    }

    /** The criteria of a draft, in their order. */
    @Transactional
    public List<DraftCriterion> criteria(UUID scopeId, long draftId) {
        return em.createQuery("""
                SELECT c FROM DraftCriterion c
                WHERE c.scopeId = :scope AND c.draftId = :draft
                ORDER BY c.ordinal
                """, DraftCriterion.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_DRAFT, draftId)
            .getResultList();
    }

    /** The texts of a draft: of its commission and of its criteria. */
    @Transactional
    public List<DraftText> texts(UUID scopeId, long draftId) {
        return em.createQuery("""
                SELECT t FROM DraftText t
                WHERE t.scopeId = :scope AND t.draftId = :draft
                ORDER BY t.id
                """, DraftText.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_DRAFT, draftId)
            .getResultList();
    }

    /** The free fields of a draft; empty where it carries none. */
    @Transactional
    public Optional<DraftFreeFields> freeFields(UUID scopeId, long draftId) {
        return em.createQuery("""
                SELECT f FROM DraftFreeFields f WHERE f.scopeId = :scope AND f.draftId = :draft
                """, DraftFreeFields.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_DRAFT, draftId)
            .getResultList()
            .stream()
            .findFirst();
    }

    /** Deletes the criteria of a draft with their texts, texts first. */
    @Transactional
    public void deleteCriteria(UUID scopeId, long draftId) {
        em.createQuery("""
                DELETE FROM DraftText t
                WHERE t.scopeId = :scope AND t.draftId = :draft AND t.draftCriterionId IS NOT NULL
                """)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_DRAFT, draftId)
            .executeUpdate();
        em.createQuery("""
                DELETE FROM DraftCriterion c WHERE c.scopeId = :scope AND c.draftId = :draft
                """)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_DRAFT, draftId)
            .executeUpdate();
    }

    /** Deletes the text of the commission of a draft. */
    @Transactional
    public void deleteCommissionText(UUID scopeId, long draftId) {
        em.createQuery("""
                DELETE FROM DraftText t
                WHERE t.scopeId = :scope AND t.draftId = :draft AND t.draftCriterionId IS NULL
                """)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_DRAFT, draftId)
            .executeUpdate();
    }

    /** Deletes the free fields of a draft. */
    @Transactional
    public void deleteFreeFields(UUID scopeId, long draftId) {
        em.createQuery("""
                DELETE FROM DraftFreeFields f WHERE f.scopeId = :scope AND f.draftId = :draft
                """)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_DRAFT, draftId)
            .executeUpdate();
    }

    /**
     * Empties the draft workspace of a task: its texts, its criteria, its free
     * fields and the draft, children first, since no key deletes along.
     */
    @Transactional
    public void deleteWorkspace(UUID scopeId, Draft draft) {
        deleteCommissionText(scopeId, draft.id());
        deleteCriteria(scopeId, draft.id());
        deleteFreeFields(scopeId, draft.id());
        em.createQuery("DELETE FROM Draft d WHERE d.scopeId = :scope AND d.id = :draft")
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_DRAFT, draft.id())
            .executeUpdate();
        em.detach(draft);
    }

    /**
     * Deletes a task outright, its draft workspace emptied before. The key of
     * an idempotency key that names it lets go of it by itself: the store
     * sets its {@code task_id} to NULL.
     */
    @Transactional
    public void deleteTask(UUID scopeId, Task task) {
        em.createQuery("DELETE FROM Task t WHERE t.scopeId = :scope AND t.id = :task")
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_TASK, task.id())
            .executeUpdate();
        em.detach(task);
    }

    /**
     * The key a caller spent in a scope since the instant given; empty where
     * the caller spent none, or spent it before that instant, which counts as
     * absent (C10.4).
     */
    @Transactional
    public Optional<IdempotencyRecord> spentKey(UUID scopeId, String caller, String key,
                                                Instant since) {
        return em.createQuery("""
                SELECT k FROM IdempotencyRecord k
                WHERE k.scopeId = :scope AND k.callerSubject = :caller
                  AND k.idempotencyKey = :spent AND k.createdAt > :since
                """, IdempotencyRecord.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_CALLER, caller)
            .setParameter("spent", key)
            .setParameter("since", since)
            .getResultList()
            .stream()
            .findFirst();
    }

    /**
     * Deletes the keys a caller spent in a scope at or before the instant
     * given: the expired ones, deleted when the caller next writes a key, in
     * its transaction and before the insert (C10.4, REA-0010 D30). Another
     * caller's keys stay.
     */
    @Transactional
    public void deleteExpiredKeys(UUID scopeId, String caller, Instant until) {
        em.createQuery("""
                DELETE FROM IdempotencyRecord k
                WHERE k.scopeId = :scope AND k.callerSubject = :caller AND k.createdAt <= :until
                """)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_CALLER, caller)
            .setParameter("until", until)
            .executeUpdate();
    }

    /**
     * Writes the key a caller spends, and flushes at once. False where the
     * caller holds the key in the scope already: another transaction wrote it
     * after this one read the ledger, and the store's {@code
     * uq_idempotency_key_caller_key} refused the second row. The insert waits
     * for that transaction to end, so the key it meets is committed. The
     * caller's transaction cannot go on after the refusal and is to be rolled
     * back; every other refusal of the store is thrown as it came.
     */
    @Transactional
    public boolean spendKey(IdempotencyRecord key) {
        try {
            em.persist(key);
            em.flush();
            return true;
        } catch (PersistenceException refused) {
            if (refusedBy(refused, KEY_OF_THE_CALLER)) {
                return false;
            }
            throw refused;
        }
    }

    /**
     * Writes a row and flushes at once, so a refusal of the store falls in
     * this call and the row has its surrogate.
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

    /** Whether the store refused a statement by the constraint named, anywhere in its causes. */
    private static boolean refusedBy(Throwable refused, String constraint) {
        for (Throwable cause = refused; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violated
                    && constraint.equals(violated.getConstraintName())) {
                return true;
            }
        }
        return false;
    }

    /** The row as it stands under the lock just taken, also where this transaction read it before. */
    private <T> T underLock(T entity) {
        em.refresh(entity);
        return entity;
    }
}
