/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.repository;

import ai.kumbuka.dispatch.domain.Activity;
import ai.kumbuka.dispatch.domain.ContractField;
import ai.kumbuka.dispatch.domain.ControlField;
import ai.kumbuka.dispatch.domain.DefinitionVersion;
import ai.kumbuka.dispatch.domain.Enumeration;
import ai.kumbuka.dispatch.domain.EnumerationValue;
import ai.kumbuka.dispatch.domain.OutcomeLabel;
import ai.kumbuka.dispatch.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.transaction.Transactional;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every statement the read of a definition issues against the definition and
 * schema tables of the store line, and no statement that writes.
 *
 * <p>A version is read with everything it declares in a fixed number of
 * statements: one for the version, and one each for its activities, its
 * outcome labels, its enumerations, their values, its control fields and its
 * contract rows. Each of the six reads every row of its table that belongs to
 * the version, so the count does not grow with the activities or the
 * enumerations a version declares.
 *
 * <p>JPA lives in this package, refusals stay above it, and an absent row is
 * an empty {@link Optional}. Every statement goes through the ORM, so the
 * tenant discriminator applies to each, and through a transaction the tenancy
 * binding has bound, so the row-level security of the store applies as well.
 * Every statement names the scope beside the tenant the discriminator adds.
 */
@ApplicationScoped
@TenantBound
public class DefinitionRepository {

    private static final String P_SCOPE = "scope";
    private static final String P_VERSION = "version";

    /** The seeded version of a selector that is active: at most one is, by a unique index. */
    private static final String ACTIVE = """
        SELECT v FROM DefinitionVersion v, Selector s
        WHERE s.id = v.selectorId AND s.scopeId = :scope AND v.scopeId = :scope
          AND s.name = :selector AND v.seed = true AND v.state = 'active'
        """;

    /** The seeded version of a selector by its number. */
    private static final String NUMBERED = """
        SELECT v FROM DefinitionVersion v, Selector s
        WHERE s.id = v.selectorId AND s.scopeId = :scope AND v.scopeId = :scope
          AND s.name = :selector AND v.seed = true AND v.version = :version
        """;

    @Inject EntityManager em;

    /**
     * What a version declares, read in one go.
     *
     * @param version      the version itself
     * @param activities   its activities, in the order they were declared
     * @param labels       the outcome labels of the version and of its
     *                     activities, in the order of their ordinal
     * @param enumerations its enumerations, in the order they were declared
     * @param values       the values of those enumerations, by enumeration and ordinal
     * @param fields       its control fields, in the order they were declared
     * @param contracts    the rows of its start and result contracts, in the
     *                     order they were declared
     */
    public record Declared(DefinitionVersion version, List<Activity> activities,
                           List<OutcomeLabel> labels, List<Enumeration> enumerations,
                           List<EnumerationValue> values, List<ControlField> fields,
                           List<ContractField> contracts) {
    }

    /**
     * The seeded version of a selector in a scope with everything it declares:
     * the one named, or the active one where none is named. Empty where the
     * scope holds no such selector, the selector no such version, or the
     * version is not seeded; the three are not told apart.
     *
     * @param version the number of the version; absent for the active one
     */
    @Transactional
    public Optional<Declared> seeded(UUID scopeId, String selector, @Nullable Integer version) {
        return seededVersion(scopeId, selector, version).map(found -> declared(scopeId, found));
    }

    /**
     * The version an instance is bound to, with everything it declares, by
     * its surrogate; empty where the scope holds none. The binding of an
     * instance names a version of its own selector, which the store holds by
     * its key.
     */
    @Transactional
    public Optional<Declared> bound(UUID scopeId, long versionId) {
        return version(scopeId, versionId).map(found -> declared(scopeId, found));
    }

    /** A version by its surrogate, without what it declares; empty where the scope holds none. */
    @Transactional
    public Optional<DefinitionVersion> version(UUID scopeId, long versionId) {
        return em.createQuery("""
                SELECT v FROM DefinitionVersion v WHERE v.scopeId = :scope AND v.id = :version
                """, DefinitionVersion.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_VERSION, versionId)
            .getResultList()
            .stream()
            .findFirst();
    }

    private Declared declared(UUID scopeId, DefinitionVersion found) {
        return new Declared(found,
            activities(scopeId, found.id()),
            labels(scopeId, found.id()),
            enumerations(scopeId, found.id()),
            values(scopeId, found.id()),
            fields(scopeId, found.id()),
            contracts(scopeId, found.id()));
    }

    private Optional<DefinitionVersion> seededVersion(UUID scopeId, String selector,
                                                      @Nullable Integer version) {
        TypedQuery<DefinitionVersion> query = version == null
            ? em.createQuery(ACTIVE, DefinitionVersion.class)
            : em.createQuery(NUMBERED, DefinitionVersion.class).setParameter(P_VERSION, version);
        return query
            .setParameter(P_SCOPE, scopeId)
            .setParameter("selector", selector)
            .getResultList()
            .stream()
            .findFirst();
    }

    private List<Activity> activities(UUID scopeId, long versionId) {
        return em.createQuery("""
                SELECT a FROM Activity a
                WHERE a.scopeId = :scope AND a.definitionVersionId = :version
                ORDER BY a.id
                """, Activity.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_VERSION, versionId)
            .getResultList();
    }

    private List<OutcomeLabel> labels(UUID scopeId, long versionId) {
        return em.createQuery("""
                SELECT l FROM OutcomeLabel l
                WHERE l.scopeId = :scope
                  AND (l.definitionVersionId = :version
                       OR l.activityId IN (SELECT a.id FROM Activity a
                                           WHERE a.scopeId = :scope
                                             AND a.definitionVersionId = :version))
                ORDER BY l.ordinal, l.id
                """, OutcomeLabel.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_VERSION, versionId)
            .getResultList();
    }

    private List<Enumeration> enumerations(UUID scopeId, long versionId) {
        return em.createQuery("""
                SELECT e FROM Enumeration e
                WHERE e.scopeId = :scope AND e.definitionVersionId = :version
                ORDER BY e.id
                """, Enumeration.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_VERSION, versionId)
            .getResultList();
    }

    private List<EnumerationValue> values(UUID scopeId, long versionId) {
        return em.createQuery("""
                SELECT ev FROM EnumerationValue ev
                WHERE ev.scopeId = :scope
                  AND ev.enumerationId IN (SELECT e.id FROM Enumeration e
                                           WHERE e.scopeId = :scope
                                             AND e.definitionVersionId = :version)
                ORDER BY ev.enumerationId, ev.ordinal
                """, EnumerationValue.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_VERSION, versionId)
            .getResultList();
    }

    private List<ControlField> fields(UUID scopeId, long versionId) {
        return em.createQuery("""
                SELECT f FROM ControlField f
                WHERE f.scopeId = :scope AND f.definitionVersionId = :version
                ORDER BY f.id
                """, ControlField.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_VERSION, versionId)
            .getResultList();
    }

    private List<ContractField> contracts(UUID scopeId, long versionId) {
        return em.createQuery("""
                SELECT c FROM ContractField c
                WHERE c.scopeId = :scope AND c.definitionVersionId = :version
                ORDER BY c.id
                """, ContractField.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_VERSION, versionId)
            .getResultList();
    }
}
