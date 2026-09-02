package ai.kumbuka.dispatch.repository;

import ai.kumbuka.dispatch.domain.Exchange;
import ai.kumbuka.dispatch.domain.ExchangeAddress;
import ai.kumbuka.dispatch.domain.ExchangeStatus;
import ai.kumbuka.dispatch.domain.NumberCircle;
import ai.kumbuka.dispatch.domain.QueryFilter;
import ai.kumbuka.dispatch.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.NoResultException;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every statement this service issues against the exchange tables.
 *
 * <p>The class exists so that "JPA lives in one package" is a sentence a test
 * can check. Before it, the entity manager was reachable from the domain
 * service, the registry and the platform directory, and the boundary was
 * maintained by searching the tree — which is not a boundary.
 *
 * <h2>What is here and what is deliberately not</h2>
 *
 * Queries and writes are here. <strong>Refusals are not.</strong> A method
 * that finds nothing returns an empty {@link Optional} or an empty list, and
 * the caller decides whether that is a {@code NOT_FOUND}, an empty draw or an
 * ordinary absence — three different statements that only the domain can tell
 * apart. Moving the throw down here would have put the typed refusal model in
 * the layer that knows the least about what the caller asked.
 *
 * <p>The methods carry {@code @Transactional} and the class is
 * {@link TenantBound}, matching the callers rather than replacing them. Every
 * entry point is already inside a transaction, so the annotation joins that one
 * and starts none; what it buys is that the guard over tenant-bound classes
 * covers this one too, and a future caller that forgot its own transaction
 * fails loudly here instead of reading under no tenant at all.
 */
@ApplicationScoped
@TenantBound
public class ExchangeRepository {

    /** The query parameter every lookup binds the scope to. */
    private static final String P_SCOPE = "scope";

    private static final String P_SELECTOR = "sel";

    private static final String P_NUMBER = "num";

    private static final String P_SUB = "sub";

    @Inject EntityManager em;

    // ----------------------------------------------------------------------
    // Reading
    // ----------------------------------------------------------------------

    /**
     * The row at an address, if there is one.
     *
     * <p>The two cases are separate queries rather than one with a nullable
     * parameter. A single query would have to say {@code :suffix IS NULL},
     * and PostgreSQL cannot infer a parameter's type from that position —
     * it fails at execution with "could not determine data type". Casting
     * around it would work and would leave the query saying something less
     * clear than these two do.
     */
    @Transactional
    public Optional<Exchange> find(UUID scopeId, ExchangeAddress address) {
        var query = address.isAddendum()
            ? em.createQuery("""
                    SELECT e FROM Exchange e
                    WHERE e.scopeId = :scope AND e.selector = :sel
                      AND e.number = :num AND e.sub = :sub
                      AND e.addendumSuffix = :suffix
                    """, Exchange.class).setParameter("suffix", address.suffix())
            : em.createQuery("""
                    SELECT e FROM Exchange e
                    WHERE e.scopeId = :scope AND e.selector = :sel
                      AND e.number = :num AND e.sub = :sub
                      AND e.addendumSuffix IS NULL
                    """, Exchange.class);

        List<Exchange> found = query
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_SELECTOR, address.selector())
            .setParameter(P_NUMBER, address.number())
            .setParameter(P_SUB, address.sub())
            .getResultList();
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /** The addenda hanging from one exchange, in suffix order. */
    @Transactional
    public List<Exchange> addenda(UUID scopeId, ExchangeAddress base) {
        return em.createQuery("""
                SELECT e FROM Exchange e
                WHERE e.scopeId = :scope AND e.selector = :sel
                  AND e.number = :num AND e.sub = :sub
                  AND e.addendumSuffix IS NOT NULL
                ORDER BY e.addendumSuffix
                """, Exchange.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_SELECTOR, base.selector())
            .setParameter(P_NUMBER, base.number())
            .setParameter(P_SUB, base.sub())
            .getResultList();
    }

    /** The children of a bracket, addenda excluded. */
    @Transactional
    public List<Exchange> children(UUID scopeId, String selector, int number) {
        return em.createQuery("""
                SELECT e FROM Exchange e
                WHERE e.scopeId = :scope AND e.selector = :sel AND e.number = :num
                  AND e.sub > 0 AND e.addendumSuffix IS NULL
                ORDER BY e.sub
                """, Exchange.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_SELECTOR, selector)
            .setParameter(P_NUMBER, number)
            .getResultList();
    }

    /**
     * The exchanges of one selector narrowed by the declared filter, in the
     * order of the address space: number, then sub. Addenda are excluded.
     *
     * <p>Each declared field that the caller named becomes one conjunct, and
     * its values become the disjunction inside it. Built by appending rather
     * than by string-formatting a predicate: the only things that reach the
     * query text are constants from this file, and every caller value travels
     * as a bound parameter.
     *
     * <p>Entities, not views. The projection that withholds a body from an
     * unclaiming caller is the domain's, and is applied by the caller inside
     * the same transaction — see the note on {@code ExchangeService.query}
     * about why there is no overload returning entities to anyone above it.
     */
    @Transactional
    public List<Exchange> matching(UUID scopeId, String selector, QueryFilter filter) {
        StringBuilder jpql = new StringBuilder("""
            SELECT e FROM Exchange e
            WHERE e.scopeId = :scope AND e.selector = :sel
              AND e.addendumSuffix IS NULL
            """);
        if (!filter.statuses().isEmpty()) {
            jpql.append("  AND e.status IN :statuses\n");
        }
        if (!filter.apparatuses().isEmpty()) {
            jpql.append("  AND e.apparatus IN :apparatuses\n");
        }
        if (!filter.numbers().isEmpty()) {
            jpql.append("  AND e.number IN :numbers\n");
        }
        jpql.append("ORDER BY e.number, e.sub");

        var query = em.createQuery(jpql.toString(), Exchange.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_SELECTOR, selector);
        if (!filter.statuses().isEmpty()) {
            // The column holds the wire name, not the enum constant: the two
            // differ (`needs_input` against `NEEDS_INPUT`) and the wire name is
            // the one that is stored, so it is the one that is bound.
            query.setParameter("statuses",
                filter.statuses().stream().map(ExchangeStatus::wireName).toList());
        }
        if (!filter.apparatuses().isEmpty()) {
            query.setParameter("apparatuses", filter.apparatuses());
        }
        if (!filter.numbers().isEmpty()) {
            query.setParameter("numbers", filter.numbers());
        }
        return query.getResultList();
    }

    /**
     * The next claimable exchange of a selector, locked against every other
     * draw, or empty when the selector holds nothing claimable.
     *
     * <p>Native rather than JPQL: the pessimistic lock this needs is
     * {@code SKIP LOCKED}, and JPA's {@code LockModeType} has no expression
     * for it — {@code PESSIMISTIC_WRITE} waits for the other transaction
     * instead of stepping over it, which would serialise every concurrent draw
     * and hand the second caller the row the first just took.
     *
     * <p>The id is round-tripped through its text form rather than cast: the
     * driver may hand back a UUID or the string of one depending on how the
     * column is read, and a cast that is right today is a
     * {@code ClassCastException} the day that changes.
     */
    @Transactional
    public Optional<Exchange> lockNextClaimable(UUID scopeId, String selector, Instant now) {
        List<?> ids = em.createNativeQuery("""
                SELECT id FROM dispatch.exchange
                WHERE scope_id = :scope
                  AND selector = :sel
                  AND addendum_suffix IS NULL
                  AND (status = 'open'
                       OR (status = 'active' AND claim_expires_at <= :now))
                ORDER BY number, sub
                LIMIT 1
                FOR UPDATE SKIP LOCKED
                """)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_SELECTOR, selector)
            .setParameter("now", java.sql.Timestamp.from(now))
            .getResultList();

        if (ids.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(
            em.find(Exchange.class, UUID.fromString(ids.get(0).toString())));
    }

    // ----------------------------------------------------------------------
    // Numbering
    // ----------------------------------------------------------------------

    /**
     * The selector's number circle, locked for the caller's transaction, or
     * empty when the selector has none.
     *
     * <p>The lock is what makes two concurrent creations serialise rather than
     * collide, and taking it in the creating transaction is what makes a
     * rolled-back creation give its number back. The absence is returned
     * rather than thrown for the reason given at the top of this class: what a
     * missing circle means is a statement about selector declaration, and the
     * domain owns that statement.
     */
    @Transactional
    public Optional<NumberCircle> lockNumberCircle(UUID scopeId, String selector) {
        try {
            return Optional.of(em.createQuery("""
                    SELECT c FROM NumberCircle c
                    WHERE c.scopeId = :scope AND c.selector = :sel
                    """, NumberCircle.class)
                .setParameter(P_SCOPE, scopeId)
                .setParameter(P_SELECTOR, selector)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getSingleResult());
        } catch (NoResultException absent) {
            return Optional.empty();
        }
    }

    /** The highest sub-number in a bracket, addenda excluded, or null when it is empty. */
    @Transactional
    public Integer highestSub(UUID scopeId, String selector, int number) {
        return em.createQuery("""
                SELECT MAX(e.sub) FROM Exchange e
                WHERE e.scopeId = :scope AND e.selector = :sel AND e.number = :num
                  AND e.addendumSuffix IS NULL
                """, Integer.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_SELECTOR, selector)
            .setParameter(P_NUMBER, number)
            .getSingleResult();
    }

    /** The highest addendum letter on one exchange, or null when it carries none. */
    @Transactional
    public String highestSuffix(UUID scopeId, ExchangeAddress base) {
        return em.createQuery("""
                SELECT MAX(e.addendumSuffix) FROM Exchange e
                WHERE e.scopeId = :scope AND e.selector = :sel
                  AND e.number = :num AND e.sub = :sub
                  AND e.addendumSuffix IS NOT NULL
                """, String.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_SELECTOR, base.selector())
            .setParameter(P_NUMBER, base.number())
            .setParameter(P_SUB, base.sub())
            .getSingleResult();
    }

    // ----------------------------------------------------------------------
    // Writing
    // ----------------------------------------------------------------------

    /**
     * Inserts an exchange and flushes, so that a constraint the table holds is
     * reported at the call site rather than at commit — which is on the far
     * side of the typed refusal model.
     */
    @Transactional
    public Exchange insert(Exchange e) {
        em.persist(e);
        em.flush();
        return e;
    }

    /** Flushes pending changes for the same reason {@link #insert} does. */
    @Transactional
    public void flush() {
        em.flush();
    }
}
