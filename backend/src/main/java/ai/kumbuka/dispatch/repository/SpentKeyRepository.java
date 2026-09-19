package ai.kumbuka.dispatch.repository;

import ai.kumbuka.dispatch.domain.SpentKey;
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
 * Every statement this service issues against the idempotency ledger.
 *
 * <p>Two of them: find the row a caller's key already has in a scope, and
 * insert a new one. There is no delete — an entry past its window is
 * overwritten in place by the next call under that key, which is why the
 * grant in V14 is the domain's usual SELECT, INSERT, UPDATE and not a fourth.
 *
 * <p>Refusals are not here, as everywhere in this package: an absent row is an
 * empty {@link Optional}, and whether that means "first call" or "the window
 * has passed" is the domain's to decide.
 */
@ApplicationScoped
@TenantBound
public class SpentKeyRepository {

    @Inject EntityManager em;

    /**
     * The row this caller's key holds in this scope, locked for update.
     *
     * <p>Locked, because the whole point of the key is a retry that may arrive
     * while the first call is still running. Two concurrent calls under one key
     * that both read "no row" would both commission, which is precisely the
     * double write the key exists to prevent. The lock makes the second wait
     * for the first's transaction and then see its row.
     *
     * <p>{@code PESSIMISTIC_WRITE} and not an optimistic check: there is no
     * version column here and the losing transaction should wait rather than
     * fail, because the caller asked for "at most once", not "at most once, and
     * you may get an error instead".
     */
    @Transactional
    public Optional<SpentKey> lockByKey(UUID scopeId, String callerSubject, String key) {
        List<SpentKey> found = em.createQuery("""
                SELECT k FROM SpentKey k
                WHERE k.scopeId = :scope
                  AND k.callerSubject = :caller
                  AND k.key = :key
                """, SpentKey.class)
            .setParameter("scope", scopeId)
            .setParameter("caller", callerSubject)
            .setParameter("key", key)
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .getResultList();
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /** Remembers a first call. */
    @Transactional
    public SpentKey insert(SpentKey spent) {
        em.persist(spent);
        em.flush();
        return spent;
    }
}
