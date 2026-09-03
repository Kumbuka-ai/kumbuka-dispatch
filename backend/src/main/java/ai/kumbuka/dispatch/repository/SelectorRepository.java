package ai.kumbuka.dispatch.repository;

import ai.kumbuka.dispatch.domain.Selector;
import ai.kumbuka.dispatch.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every statement issued against the selector table.
 *
 * <p>Separate from {@link ExchangeRepository} because the registry above it is
 * separate: a selector is declared deliberately and never as a side effect of
 * use, and folding its three statements into the exchange repository would put
 * them next to the ones that DO run on every ordinary write.
 *
 * <p>As there, refusals stay above. "Not declared" and "withdrawn" are two
 * different things a caller is told, and telling them apart needs the reason a
 * selector was looked up — which this layer does not have.
 */
@ApplicationScoped
@TenantBound
public class SelectorRepository {

    /** The query parameter every lookup binds the scope to. */
    private static final String P_SCOPE = "scope";

    @Inject EntityManager em;

    /** The selector of that name in this scope, declared or withdrawn, if it exists. */
    @Transactional
    public Optional<Selector> find(UUID scopeId, String name) {
        List<Selector> found = em.createQuery("""
                SELECT s FROM Selector s WHERE s.scopeId = :scope AND s.name = :name
                """, Selector.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter("name", name)
            .getResultList();
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /** Every selector of a scope, withdrawn ones included, by name. */
    @Transactional
    public List<Selector> declared(UUID scopeId) {
        return em.createQuery("""
                SELECT s FROM Selector s WHERE s.scopeId = :scope ORDER BY s.name
                """, Selector.class)
            .setParameter(P_SCOPE, scopeId)
            .getResultList();
    }

    /** How many exchanges carry a selector — the number a withdrawal turns on. */
    @Transactional
    public long exchangesUnder(UUID scopeId, String name) {
        return em.createQuery("""
                SELECT COUNT(e) FROM Exchange e
                WHERE e.scopeId = :scope AND e.selector = :sel
                """, Long.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter("sel", name)
            .getSingleResult();
    }

    /** Flushes a status change so the table's constraints answer at the call site. */
    @Transactional
    public void flush() {
        em.flush();
    }
}
