/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.repository;

import ai.kumbuka.dispatch.domain.Holding;
import ai.kumbuka.dispatch.domain.IssuedReceipt;
import ai.kumbuka.dispatch.domain.Lease;
import ai.kumbuka.dispatch.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every statement the calls that hold a task issue against {@code holding},
 * {@code receipt} and {@code lease}.
 *
 * <p>JPA lives in this package, refusals stay above it, and an absent row is
 * an empty {@link Optional}. Every statement goes through the ORM, so the
 * tenant discriminator applies to each, and through a transaction the tenancy
 * binding has bound, so the row-level security of the store applies as well.
 * Every statement names the scope beside the tenant the discriminator adds.
 *
 * <p>Nothing here locks: a call decides under the lock of its task's row,
 * which {@link TaskRepository#lockTask} takes, and reads the holdings of the
 * task after it. The rows are inserted through {@link TaskRepository#insert}.
 */
@ApplicationScoped
@TenantBound
public class HoldingRepository {

    private static final String P_SCOPE = "scope";
    private static final String P_HOLDING = "holding";

    @Inject EntityManager em;

    /** The holdings of a task, oldest first. */
    @Transactional
    public List<Holding> holdings(UUID scopeId, long taskId) {
        return em.createQuery("""
                SELECT h FROM Holding h WHERE h.scopeId = :scope AND h.taskId = :task
                ORDER BY h.id
                """, Holding.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter("task", taskId)
            .getResultList();
    }

    /** The holding a claim opened, by its act; empty where the scope holds none. */
    @Transactional
    public Optional<Holding> openedBy(UUID scopeId, long claimActId) {
        return em.createQuery("""
                SELECT h FROM Holding h WHERE h.scopeId = :scope AND h.claimActId = :act
                """, Holding.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter("act", claimActId)
            .getResultList()
            .stream()
            .findFirst();
    }

    /** The youngest lease of a holding: the one that holds; empty where it has none. */
    @Transactional
    public Optional<Lease> youngestLease(UUID scopeId, long holdingId) {
        return em.createQuery("""
                SELECT l FROM Lease l WHERE l.scopeId = :scope AND l.holdingId = :holding
                ORDER BY l.id DESC
                """, Lease.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_HOLDING, holdingId)
            .setMaxResults(1)
            .getResultList()
            .stream()
            .findFirst();
    }

    /** The youngest receipt of a holding: the one a holder's call presents; empty where none. */
    @Transactional
    public Optional<IssuedReceipt> youngestReceipt(UUID scopeId, long holdingId) {
        return em.createQuery("""
                SELECT r FROM IssuedReceipt r WHERE r.scopeId = :scope AND r.holdingId = :holding
                ORDER BY r.id DESC
                """, IssuedReceipt.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_HOLDING, holdingId)
            .setMaxResults(1)
            .getResultList()
            .stream()
            .findFirst();
    }
}
