/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.repository;

import ai.kumbuka.dispatch.domain.Tag;
import ai.kumbuka.dispatch.domain.TaskTag;
import ai.kumbuka.dispatch.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Every statement on the tags of a task: the vocabulary the platform
 * publishes, and {@code task_tag}, the relation this service holds (V24).
 *
 * <p>The vocabulary is read through {@code platform.tag_access} and nothing
 * else, as scopes are read through {@code platform.scope_access}: a view the
 * platform publishes, filtered to the scopes the bound subject may see, on
 * which this service holds {@code SELECT} and nothing more.
 *
 * <h2>Native, and why</h2>
 *
 * The view has no entity and should have none, for the reason {@link
 * ScopeAccessRepository} gives. The insert of a relation is native because it
 * answers a relation already there as nothing done ({@code ON CONFLICT DO
 * NOTHING}), which JPQL cannot write and which two callers attaching the same
 * tag at once need. The detach is JPQL on the mapped relation.
 */
@ApplicationScoped
@TenantBound
public class TagRepository {

    private static final String P_TASK = "task";
    private static final String P_TAG = "tag";

    @Inject EntityManager em;

    /**
     * The tag of this token in one scope, as the bound subject sees it, or
     * empty: a token the scope does not declare, and a scope the subject may
     * not see, answer alike.
     */
    @Transactional
    public Optional<Tag> find(UUID scopeId, String token) {
        List<?> rows = em.createNativeQuery("""
                SELECT tag_id, token, status
                FROM platform.tag_access
                WHERE scope_id = :scope AND token = :token
                """)
            .setParameter("scope", scopeId)
            .setParameter("token", token)
            .getResultList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = (Object[]) rows.get(0);
        return Optional.of(new Tag((UUID) row[0], (String) row[1],
            Tag.WITHDRAWN.equals(row[2])));
    }

    /**
     * Attaches a tag to a task, in the task's tenant; a tag the task already
     * carries stays as it is, and the first attachment keeps its stamps.
     */
    @Transactional
    public void attach(Long taskId, UUID tagId, String by) {
        em.createNativeQuery("""
                INSERT INTO dispatch.task_tag (tenant_id, task_id, tag_id, attached_by)
                SELECT t.tenant_id, t.id, :tag, :by FROM dispatch.task t WHERE t.id = :task
                ON CONFLICT (task_id, tag_id) DO NOTHING
                """)
            .setParameter(P_TAG, tagId)
            .setParameter("by", by)
            .setParameter(P_TASK, taskId)
            .executeUpdate();
    }

    /** Detaches a tag from a task; a tag the task does not carry leaves nothing to do. */
    @Transactional
    public void detach(Long taskId, UUID tagId) {
        em.createQuery("DELETE FROM TaskTag x WHERE x.taskId = :task AND x.tagId = :tag")
            .setParameter(P_TASK, taskId)
            .setParameter(P_TAG, tagId)
            .executeUpdate();
    }

    /**
     * The tokens of the tags several tasks carry, by task, in ascending order;
     * withdrawn tags included. A task that carries none is absent from the
     * answer. Two statements for a page of a listing at most.
     *
     * <p>The relation is read first, from this service's own table; the
     * vocabulary is asked only for the tags it names. A page none of whose
     * tasks carries a tag therefore reads nothing of the platform's, and a
     * read or a listing of untagged tasks stands also where the platform does
     * not publish its vocabulary yet.
     *
     * <p>Sorted here rather than by the database, so the order is the order
     * of the characters and not the collation's, which may pass over the
     * colon and the hyphen.
     */
    @Transactional
    public Map<Long, List<String>> tokens(Collection<Long> taskIds) {
        if (taskIds.isEmpty()) {
            return Map.of();
        }
        List<TaskTag> carried = em.createQuery(
                "SELECT x FROM TaskTag x WHERE x.taskId IN :tasks", TaskTag.class)
            .setParameter("tasks", taskIds)
            .getResultList();
        if (carried.isEmpty()) {
            return Map.of();
        }
        Map<UUID, String> named = new LinkedHashMap<>();
        for (Object entry : em.createNativeQuery("""
                SELECT tag_id, token FROM platform.tag_access WHERE tag_id IN (:tags)
                """)
            .setParameter("tags", carried.stream().map(x -> x.tagId).distinct().toList())
            .getResultList()) {
            Object[] row = (Object[]) entry;
            named.put((UUID) row[0], (String) row[1]);
        }
        Map<Long, List<String>> tokens = new LinkedHashMap<>();
        for (TaskTag relation : carried) {
            String token = named.get(relation.tagId);
            if (token != null) {
                tokens.computeIfAbsent(relation.taskId, id -> new ArrayList<>()).add(token);
            }
        }
        tokens.values().forEach(list -> list.sort(String::compareTo));
        return tokens;
    }
}
