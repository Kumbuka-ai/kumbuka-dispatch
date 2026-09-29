package ai.kumbuka.dispatch.repository;

import ai.kumbuka.dispatch.domain.Exchange;
import ai.kumbuka.dispatch.domain.ExchangeAddress;
import ai.kumbuka.dispatch.domain.ExchangeStatus;
import ai.kumbuka.dispatch.domain.QueryFilter;
import ai.kumbuka.dispatch.domain.Selector;
import ai.kumbuka.dispatch.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
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

    /**
     * The prefix of the draw's apparatus parameters, numbered from zero.
     *
     * <p>A prefix and an index rather than one parameter holding a list: the
     * comparison is {@code LIKE} and there is no list form of it. The number of
     * conjuncts therefore comes from the size of the caller's list, and that
     * size is the only caller-derived thing that reaches the query text —
     * every pattern itself travels as a bound parameter.
     */
    private static final String P_APPARATUS = "ap";

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
                    WHERE e.scopeId = :scope AND e.selector.name = :sel
                      AND e.number = :num AND e.sub = :sub
                      AND e.addendumSuffix = :suffix
                    """, Exchange.class).setParameter("suffix", address.suffix())
            : em.createQuery("""
                    SELECT e FROM Exchange e
                    WHERE e.scopeId = :scope AND e.selector.name = :sel
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

    /**
     * The exchange with this durable identity, in whatever scope of this
     * tenant holds it.
     *
     * <p>Scope-free on purpose, and tenant-bound all the same: the {@code
     * @TenantId} filter and the row-level policy both still apply, so this
     * reaches no further than the caller's own tenant. It exists because a
     * curated answer's target may be in another scope (section 5.1) and the
     * projection that renders its address has only the identity to go on.
     */
    @Transactional
    public Optional<Exchange> findByIdentityAnywhere(Long id) {
        if (id == null) {
            return Optional.empty();
        }
        List<Exchange> found = em.createQuery("""
                SELECT e FROM Exchange e WHERE e.id = :id
                """, Exchange.class)
            .setParameter("id", id)
            .getResultList();
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /**
     * The same, narrowed to one scope.
     *
     * <p>The scope is bound in the query rather than checked afterwards — a
     * target in another scope must read as absent, not as a row the caller can
     * then infer the existence of from a refusal.
     */
    @Transactional
    public Optional<Exchange> findByIdentity(UUID scopeId, Long id) {
        if (id == null) {
            return Optional.empty();
        }
        List<Exchange> found = em.createQuery("""
                SELECT e FROM Exchange e
                WHERE e.id = :id AND e.scopeId = :scope
                """, Exchange.class)
            .setParameter("id", id)
            .setParameter(P_SCOPE, scopeId)
            .getResultList();
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    /** The addenda hanging from one exchange, in suffix order. */
    @Transactional
    public List<Exchange> addenda(UUID scopeId, ExchangeAddress base) {
        return em.createQuery("""
                SELECT e FROM Exchange e
                WHERE e.scopeId = :scope AND e.selector.name = :sel
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
                WHERE e.scopeId = :scope AND e.selector.name = :sel AND e.number = :num
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
            WHERE e.scopeId = :scope AND e.selector.name = :sel
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
     *
     * @param apparatusPatterns the patterns the drawn exchange's apparatus must
     *                          match, at least one, as alternatives. In the
     *                          surface's pattern language, where {@code *} is
     *                          the wildcard; the translation to the comparison's
     *                          own wildcard happens here and nowhere else
     */
    @Transactional
    public Optional<Exchange> lockNextClaimable(UUID scopeId, String selector,
                                               List<String> apparatusPatterns, Instant now) {
        if (apparatusPatterns.isEmpty()) {
            // Not a refusal: the caller's refusal happened at the surface. This
            // is the guard that keeps a defect from reaching the database as
            // `AND ()`, which is a syntax error answered as an unexpected
            // failure rather than as the empty draw it would look like.
            throw new IllegalArgumentException(
                "a draw filters on at least one apparatus pattern");
        }

        StringBuilder sql = new StringBuilder("""
            SELECT e.id FROM dispatch.exchange e
            JOIN dispatch.selector s ON s.id = e.selector_id
            WHERE e.scope_id = :scope
              AND s.name = :sel
              AND e.addendum_suffix IS NULL
              AND (e.status = 'open'
                   OR (e.status = 'active' AND e.claim_expires_at <= :now))
            """);
        sql.append("  AND (");
        for (int i = 0; i < apparatusPatterns.size(); i++) {
            sql.append(i == 0 ? "" : " OR ")
                .append("e.apparatus LIKE :").append(P_APPARATUS).append(i);
        }
        sql.append(")\n");
        sql.append("""
            ORDER BY e.number, e.sub
            LIMIT 1
            FOR UPDATE OF e SKIP LOCKED
            """);

        var query = em.createNativeQuery(sql.toString())
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_SELECTOR, selector)
            .setParameter("now", java.sql.Timestamp.from(now));
        for (int i = 0; i < apparatusPatterns.size(); i++) {
            query.setParameter(P_APPARATUS + i, likeExpression(apparatusPatterns.get(i)));
        }

        List<?> ids = query.getResultList();
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(
            em.find(Exchange.class, ((Number) ids.get(0)).longValue()));
    }

    /**
     * One apparatus pattern as the comparison reads it.
     *
     * <p>{@code *} becomes {@code %} and nothing else changes. Nothing is
     * escaped, and that is admissible only because of the character rule the
     * surface enforces: a pattern is letters, digits, {@code +}, {@code -} and
     * {@code *}, so neither {@code %} nor {@code _} — the two characters
     * {@code LIKE} reads as wildcards — can be in one. The rule and this
     * translation are two halves of one decision, and the refusal of
     * {@code agent-%} and {@code agent_code} at the entrance is what backs it.
     *
     * <p>{@code LIKE} rather than {@code ILIKE}: an apparatus value is an
     * identifier the scope's operator gave out, and matching {@code Agent-Code}
     * against {@code agent-*} would make two distinct values one.
     */
    private static String likeExpression(String pattern) {
        return pattern.replace('*', '%');
    }

    // ----------------------------------------------------------------------
    // Numbering
    // ----------------------------------------------------------------------

    /**
     * The selector row, locked for the caller's transaction so its
     * {@code next_number} can be read-and-bumped atomically. Empty when no
     * row of that id is visible under the current tenant.
     *
     * <p>The lock is what makes two concurrent creations serialise rather
     * than collide, and taking it in the creating transaction is what makes
     * a rolled-back creation give its number back — the counter lives on
     * this row, so a rollback of the row rolls the counter back too.
     *
     * <p>The absence is returned rather than thrown for the reason given at
     * the top of this class: what a missing selector row means is a
     * statement the domain owns.
     */
    @Transactional
    public Optional<Selector> lockSelectorForNumbering(Long selectorId) {
        return em.createQuery("""
                SELECT s FROM Selector s WHERE s.id = :id
                """, Selector.class)
            .setParameter("id", selectorId)
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .getResultList()
            .stream()
            .findFirst();
    }

    /** The highest sub-number in a bracket, addenda excluded, or null when it is empty. */
    @Transactional
    public Integer highestSub(UUID scopeId, String selector, int number) {
        return em.createQuery("""
                SELECT MAX(e.sub) FROM Exchange e
                WHERE e.scopeId = :scope AND e.selector.name = :sel AND e.number = :num
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
                WHERE e.scopeId = :scope AND e.selector.name = :sel
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
