package ai.kumbuka.dispatch.repository;

import ai.kumbuka.dispatch.domain.Selector;
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
 * Every statement issued against the selector table.
 *
 * <p>Separate from {@link TaskRepository} because the registry above it is
 * separate: a selector is declared deliberately and never as a side effect of
 * use, and folding its statements into the task repository would put
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

    /**
     * The selector of that name in this scope, locked for the caller's
     * transaction, as the row stands under the lock.
     *
     * <p>The same row lock the numbering of a bracket root takes, so a
     * withdrawal and a root's creation under one selector decide one after the
     * other. Refreshed for the reason {@code TaskRepository.underLock} gives:
     * where the transaction read the selector before, the persistence context
     * would answer that earlier object.
     */
    @Transactional
    public Optional<Selector> lock(UUID scopeId, String name) {
        return em.createQuery("""
                SELECT s FROM Selector s WHERE s.scopeId = :scope AND s.name = :name
                """, Selector.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter("name", name)
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .getResultList()
            .stream()
            .findFirst()
            .map(locked -> {
                em.refresh(locked);
                return locked;
            });
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

        /**
     * How many tasks are filed under a selector in a scope.
     *
     * <p>What decides whether a selector may still be withdrawn: a selector
     * that carries a task carries addresses somebody depends on.
     */
    @Transactional
    public long tasksUnder(UUID scopeId, String name) {
        return em.createQuery("""
                SELECT COUNT(t) FROM Task t
                WHERE t.scopeId = :scope AND t.selector.name = :sel
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
