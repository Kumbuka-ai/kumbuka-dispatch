package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The declared bracket names of a scope.
 *
 * <p>There is no method here that creates a selector as a side effect of using
 * one, and that absence is the class's main content. A selector is declared
 * deliberately — through the interface, and for our own estate through a
 * migration — because a name that comes into existence by being typed cannot
 * carry what a selector later has to carry, and because a typo would otherwise
 * open a namespace nobody meant to open.
 */
@ApplicationScoped
@TenantBound
public class SelectorRegistry {

    /** The query parameter every lookup binds the scope to. */
    private static final String P_SCOPE = "scope";

    @Inject EntityManager em;

    /**
     * Refuses unless the selector is declared in this scope and not withdrawn.
     *
     * <p>The two refusals are told apart on purpose. "Never declared" is a
     * caller naming something that does not exist; "withdrawn" is a caller
     * naming something that did, whose addresses are still valid and readable.
     * Collapsing them into "unknown selector" would make a withdrawal look
     * like a typo.
     */
    @Transactional
    public Selector requireDeclared(UUID scopeId, String name) {
        Selector selector = find(scopeId, name).orElseThrow(() -> new DispatchException(
            DispatchException.Reason.SELECTOR_NOT_DECLARED,
            "selector '" + name + "' is not declared in this scope. Bracket names are "
                + "declared before use, never by first use: a typo must not silently "
                + "open a namespace."));

        if (Boolean.TRUE.equals(selector.withdrawn)) {
            throw new DispatchException(DispatchException.Reason.SELECTOR_WITHDRAWN,
                "selector '" + name + "' is withdrawn. Addresses already issued under it "
                    + "remain readable; no new exchange is numbered under it.");
        }
        return selector;
    }

    @Transactional
    public List<Selector> declared(UUID scopeId) {
        return em.createQuery("""
                SELECT s FROM Selector s WHERE s.scopeId = :scope ORDER BY s.name
                """, Selector.class)
            .setParameter(P_SCOPE, scopeId)
            .getResultList();
    }

    /**
     * Withdraws a selector that was never used.
     *
     * <p>Withdrawal is a status and never a deletion, because every address
     * ever issued under the name depends on it. A selector that HAS been used
     * cannot be withdrawn at all — the exchanges under it would be left
     * pointing at a name the registry disowns.
     */
    @Transactional
    public Selector withdraw(UUID scopeId, String name) {
        Selector selector = requireDeclared(scopeId, name);
        long used = em.createQuery("""
                SELECT COUNT(e) FROM Exchange e
                WHERE e.scopeId = :scope AND e.selector = :sel
                """, Long.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter("sel", name)
            .getSingleResult();

        if (used > 0) {
            throw new DispatchException(DispatchException.Reason.SELECTOR_IN_USE,
                "selector '" + name + "' carries " + used + " exchange(s) and cannot be "
                    + "withdrawn. Only a never-used selector may be; the addresses under "
                    + "a used one depend on the name continuing to mean what it meant.");
        }
        selector.withdrawn = Boolean.TRUE;
        em.flush();
        return selector;
    }

    private Optional<Selector> find(UUID scopeId, String name) {
        List<Selector> found = em.createQuery("""
                SELECT s FROM Selector s WHERE s.scopeId = :scope AND s.name = :name
                """, Selector.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter("name", name)
            .getResultList();
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }
}
