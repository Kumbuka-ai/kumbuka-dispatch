package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.NoResultException;
import jakarta.transaction.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The verbs of the exchange, and the only way its state moves.
 *
 * <p>Every method here is one transition. There is no method that takes a
 * status, and the entity has no setter for one — so the freeze, the
 * precondition of each verb and the bracket's closability check cannot be
 * reached around. That is a structural statement rather than a convention: a
 * generic status write would not be a shortcut past one check but past all of
 * them at once.
 *
 * <p>Every method is {@link TenantBound} and transactional, which is what puts
 * the tenant binding inside the transaction the statements run in. Number
 * allocation depends on it too: the counter row is locked, incremented and
 * consumed in the same transaction that inserts the exchange, so a creation
 * that rolls back returns its number and the class "burned number" has nowhere
 * to occur.
 */
@ApplicationScoped
@TenantBound
public class ExchangeService {

    /** The last letter a suffix may take. Overflow beyond it is deferred, not wrapped. */
    private static final char LAST_SUFFIX = 'z';

    /** The query parameter every lookup binds the scope to. */
    private static final String P_SCOPE = "scope";

    @Inject EntityManager em;
    @Inject SelectorRegistry selectors;

    private final Clock clock;

    ExchangeService() {
        this(Clock.systemUTC());
    }

    ExchangeService(Clock clock) {
        this.clock = clock;
    }

    // ----------------------------------------------------------------------
    // Creation. The caller never supplies a number.
    // ----------------------------------------------------------------------

    /**
     * Opens a bracket: the exchange numbered {@code .0}.
     *
     * <p>A bracket is not an object. It opens with this exchange and closes
     * when this exchange terminates, and its state and metadata are this
     * exchange's — there is no second row and no second place to look.
     *
     * @param number is not a parameter, and that is the point. The caller
     *               never guesses a number and the service never accepts one.
     */
    @Transactional
    public Exchange openBracket(UUID scopeId, String selector, String title,
                                String apparatus, LocalDate date, String actor) {
        selectors.requireDeclared(scopeId, selector);
        int number = allocateNumber(scopeId, selector);
        return insert(new NewExchange(scopeId, selector, number, 0, null, title,
            apparatus, date, actor));
    }

    /**
     * Adds a child to an open bracket. Children number within the bracket
     * instance, which is a property of the bracket rather than a declared
     * circle of its own.
     */
    @Transactional
    public Exchange addChild(UUID scopeId, String selector, int number, String title,
                             String apparatus, LocalDate date, String actor) {
        selectors.requireDeclared(scopeId, selector);
        requireBracketExists(scopeId, selector, number);
        int sub = nextSub(scopeId, selector, number);
        return insert(new NewExchange(scopeId, selector, number, sub, null, title,
            apparatus, date, actor));
    }

    /**
     * Attaches an addendum to an exchange that has already been frozen.
     *
     * <p>The suffix is a letter on the exchange it corrects, never a regular
     * sub-number: a regular number would make the addendum an ordinary child
     * of the bracket, and an ordinary child carries the handover expectation
     * and counts in the terminality check that governs whether the bracket
     * may close. The correction stays attached to what it corrects without
     * manufacturing a second exchange.
     */
    @Transactional
    public Exchange addAddendum(UUID scopeId, ExchangeAddress base, String title,
                                String apparatus, LocalDate date, String actor) {
        if (base.isAddendum()) {
            throw new DispatchException(DispatchException.Reason.ADDENDUM_MALFORMED,
                "an addendum corrects an exchange, not another addendum: " + base);
        }
        Exchange corrected = require(scopeId, base);
        if (!corrected.frozen()) {
            throw new DispatchException(DispatchException.Reason.TRANSITION_NOT_PERMITTED,
                base + " is still a draft. An addendum exists for corrections after a "
                    + "commitment was acquired; before that the exchange is simply edited.");
        }
        String suffix = nextSuffix(scopeId, base);
        return insertAddendum(scopeId, base, suffix, title, apparatus, date, actor);
    }

    // ----------------------------------------------------------------------
    // Reading
    // ----------------------------------------------------------------------

    /**
     * The exchange at an address.
     *
     * <p>An addendum is <strong>never independently drawable</strong>: it is a
     * correction to something and has no standing without it, so asking for
     * one on its own is refused rather than served. Its content reaches a
     * caller through the exchange it corrects, via {@link #addenda}.
     */
    @Transactional
    public Exchange read(UUID scopeId, ExchangeAddress address) {
        if (address.isAddendum()) {
            throw new DispatchException(DispatchException.Reason.ADDENDUM_NOT_DRAWABLE,
                address + " is an addendum and is not independently drawable. Read "
                    + new ExchangeAddress(address.selector(), address.number(),
                        address.sub(), null) + " instead.");
        }
        return require(scopeId, address);
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
            .setParameter("sel", base.selector())
            .setParameter("num", base.number())
            .setParameter("sub", base.sub())
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
            .setParameter("sel", selector)
            .setParameter("num", number)
            .getResultList();
    }

    // ----------------------------------------------------------------------
    // The verbs. One per transition.
    // ----------------------------------------------------------------------

    /** Freezes the dispatch and opens it to an executor. */
    @Transactional
    public Exchange send(UUID scopeId, ExchangeAddress address, String actor) {
        Exchange e = require(scopeId, address);
        if (e.apply(Transition.SEND)) {
            e.freezeDispatch(Instant.now(clock));
        }
        return touch(e, actor);
    }

    @Transactional
    public Exchange takeup(UUID scopeId, ExchangeAddress address, String actor) {
        return transition(scopeId, address, Transition.TAKEUP, actor);
    }

    @Transactional
    public Exchange reject(UUID scopeId, ExchangeAddress address, String actor) {
        return transition(scopeId, address, Transition.REJECT, actor);
    }

    @Transactional
    public Exchange fail(UUID scopeId, ExchangeAddress address, String actor) {
        return transition(scopeId, address, Transition.FAIL, actor);
    }

    @Transactional
    public Exchange block(UUID scopeId, ExchangeAddress address, String actor) {
        return transition(scopeId, address, Transition.BLOCK, actor);
    }

    @Transactional
    public Exchange resume(UUID scopeId, ExchangeAddress address, String actor) {
        return transition(scopeId, address, Transition.RESUME, actor);
    }

    /**
     * Writes the answer and freezes it, in one transaction with the transition.
     *
     * <p>Two steps would leave a window in which an answer exists and is not
     * yet frozen, and that window is exactly where a correction would slip in
     * unrecorded.
     */
    @Transactional
    public Exchange ratify(UUID scopeId, ExchangeAddress address, String answer, String actor) {
        Exchange e = require(scopeId, address);
        if (e.apply(Transition.RATIFY)) {
            e.freezeHandover(answer, Instant.now(clock));
        }
        return touch(e, actor);
    }

    @Transactional
    public Exchange close(UUID scopeId, ExchangeAddress address, String actor) {
        return transition(scopeId, address, Transition.CLOSE, actor);
    }

    @Transactional
    public Exchange consume(UUID scopeId, ExchangeAddress address, String actor) {
        return transition(scopeId, address, Transition.CONSUME, actor);
    }

    @Transactional
    public Exchange revert(UUID scopeId, ExchangeAddress address, String actor) {
        return transition(scopeId, address, Transition.REVERT, actor);
    }

    // ----------------------------------------------------------------------
    // The machinery behind the verbs
    // ----------------------------------------------------------------------

    private Exchange transition(UUID scopeId, ExchangeAddress address,
                                Transition t, String actor) {
        Exchange e = require(scopeId, address);

        // The bracket closes through the termination of its .0, and the check
        // sits AT that transition rather than beside it. Beside it is where the
        // predecessor put its equivalent, and a check that runs somewhere else
        // can disagree with the state it is checking.
        if (t.to().terminal() && e.isBracketRoot()) {
            requireSiblingsTerminal(scopeId, e);
        }

        boolean moved = e.apply(t);

        // A terminal transition of a base object cascades onto its addenda, in
        // this transaction. An addendum has no standing of its own, so leaving
        // one non-terminal behind a terminated base would create an object
        // nobody can reach and nothing can close.
        if (moved && t.to().terminal() && !e.isAddendum()) {
            cascadeToAddenda(scopeId, e, actor);
        }
        return touch(e, actor);
    }

    /**
     * Refuses to terminate a bracket while a sibling is still running, and
     * names the ones that are.
     *
     * <p>Naming them is not politeness. The rule alone sends the reader back
     * to the store to work out which object it meant, and the entire reason
     * for checking here rather than in a separate closure verb is that the
     * answer is available at the moment the check runs.
     */
    private void requireSiblingsTerminal(UUID scopeId, Exchange bracketRoot) {
        List<String> blocking = children(scopeId, bracketRoot.selector, bracketRoot.number)
            .stream()
            .filter(child -> !child.status().terminal())
            .map(child -> child.address() + " (" + child.status().wireName() + ")")
            .toList();

        if (!blocking.isEmpty()) {
            throw new DispatchException(
                DispatchException.Reason.SIBLINGS_NON_TERMINAL,
                "%s cannot terminate while %d sibling(s) are non-terminal: %s".formatted(
                    bracketRoot.address(), blocking.size(), String.join(", ", blocking)),
                blocking);
        }
    }

    /**
     * Closes every addendum hanging from a terminated base, in this transaction.
     *
     * <p>Always CLOSE, and deliberately NOT the base's own verb — which is why
     * this takes no transition. An addendum was never rejected on its own terms
     * and never failed on its own terms, and consuming one separately would
     * claim it had been curated forward by itself. It has no standing of its
     * own; administrative closure is the only honest thing to say about it.
     */
    private void cascadeToAddenda(UUID scopeId, Exchange base, String actor) {
        for (Exchange addendum : addenda(scopeId,
                new ExchangeAddress(base.selector, base.number, base.sub, null))) {
            if (!addendum.status().terminal()) {
                addendum.apply(Transition.CLOSE);
                touch(addendum, actor);
            }
        }
    }

    private Exchange touch(Exchange e, String actor) {
        e.updatedBy = actor;
        em.flush();
        return e;
    }

    // ----------------------------------------------------------------------
    // Numbering
    // ----------------------------------------------------------------------

    /**
     * Takes the next bracket number, under a row lock, in this transaction.
     *
     * <p>The lock is what makes two concurrent creations serialise rather than
     * collide, and doing it in the creating transaction is what makes a
     * rolled-back creation give its number back.
     */
    private int allocateNumber(UUID scopeId, String selector) {
        NumberCircle circle;
        try {
            circle = em.createQuery("""
                    SELECT c FROM NumberCircle c
                    WHERE c.scopeId = :scope AND c.selector = :sel
                    """, NumberCircle.class)
                .setParameter(P_SCOPE, scopeId)
                .setParameter("sel", selector)
                .setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getSingleResult();
        } catch (NoResultException absent) {
            throw new DispatchException(DispatchException.Reason.SELECTOR_NOT_DECLARED,
                "no number circle for selector '" + selector + "' in this scope. A circle "
                    + "is created with the selector's declaration, not on first use — a "
                    + "counter that springs into existence starts wherever the first "
                    + "caller happened to be.");
        }
        int allocated = circle.nextNumber;
        circle.nextNumber = allocated + 1;
        return allocated;
    }

    private int nextSub(UUID scopeId, String selector, int number) {
        Integer highest = em.createQuery("""
                SELECT MAX(e.sub) FROM Exchange e
                WHERE e.scopeId = :scope AND e.selector = :sel AND e.number = :num
                  AND e.addendumSuffix IS NULL
                """, Integer.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter("sel", selector)
            .setParameter("num", number)
            .getSingleResult();
        return highest == null ? 1 : highest + 1;
    }

    /**
     * The next free letter on the exchange being corrected.
     *
     * <p>Past {@code z} this refuses rather than wrapping. Wrapping would
     * reissue an address that already exists, and an address, once issued, is
     * the one thing in this design that cannot be taken back.
     */
    private String nextSuffix(UUID scopeId, ExchangeAddress base) {
        String highest = em.createQuery("""
                SELECT MAX(e.addendumSuffix) FROM Exchange e
                WHERE e.scopeId = :scope AND e.selector = :sel
                  AND e.number = :num AND e.sub = :sub
                  AND e.addendumSuffix IS NOT NULL
                """, String.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter("sel", base.selector())
            .setParameter("num", base.number())
            .setParameter("sub", base.sub())
            .getSingleResult();

        if (highest == null) {
            return "a";
        }
        char next = (char) (highest.charAt(0) + 1);
        if (next > LAST_SUFFIX) {
            throw new DispatchException(DispatchException.Reason.ADDENDUM_SUFFIX_EXHAUSTED,
                base + " already carries addenda through 'z'. Overflow past that is "
                    + "deferred as hypothetical and is refused rather than wrapped: a "
                    + "wrapped suffix would reissue an address that already exists.");
        }
        return String.valueOf(next);
    }

    // ----------------------------------------------------------------------
    // Lookups
    // ----------------------------------------------------------------------

    /**
     * The fields a new exchange is built from.
     *
     * <p>A record rather than a parameter list: four of these are strings and
     * two are ints, so a transposed pair would compile and land the title in
     * the apparatus column. Naming them at the call site is what makes that
     * mistake visible while it is being made.
     */
    private record NewExchange(UUID scopeId, String selector, int number, int sub,
                               String suffix, String title, String apparatus,
                               LocalDate date, String actor) {
    }

    private Exchange insert(NewExchange spec) {
        Exchange e = build(spec);
        em.persist(e);
        em.flush();
        return e;
    }

    private Exchange build(NewExchange spec) {
        Exchange e = new Exchange();
        e.scopeId = spec.scopeId();
        e.selector = spec.selector();
        e.number = spec.number();
        e.sub = spec.sub();
        e.addendumSuffix = spec.suffix();
        e.title = spec.title();
        e.apparatus = spec.apparatus();
        e.dispatchDate = spec.date();
        e.createdBy = spec.actor();
        e.updatedBy = spec.actor();
        return e;
    }

    /**
     * An addendum is inserted already sent.
     *
     * <p>It corrects something that was frozen, so there is nothing for it to
     * be a draft of: the correction is the commitment. The table refuses a
     * draft addendum for the same reason.
     */
    private Exchange insertAddendum(UUID scopeId, ExchangeAddress base, String suffix,
                                    String title, String apparatus, LocalDate date,
                                    String actor) {
        Exchange e = build(new NewExchange(scopeId, base.selector(), base.number(),
            base.sub(), suffix, title, apparatus, date, actor));

        // Sent BEFORE the insert, not after it. An addendum corrects something
        // that was already frozen, so there is no moment at which it is a
        // draft — the table says so with a constraint, and building the object
        // in two steps would try to pass through a state it is not allowed to
        // be in.
        e.apply(Transition.SEND);
        e.freezeDispatch(Instant.now(clock));

        em.persist(e);
        em.flush();
        return e;
    }

    private Exchange require(UUID scopeId, ExchangeAddress address) {
        return find(scopeId, address).orElseThrow(() -> new DispatchException(
            DispatchException.Reason.NOT_FOUND, "no exchange at " + address));
    }

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
    private Optional<Exchange> find(UUID scopeId, ExchangeAddress address) {
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
            .setParameter("sel", address.selector())
            .setParameter("num", address.number())
            .setParameter("sub", address.sub())
            .getResultList();
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    private void requireBracketExists(UUID scopeId, String selector, int number) {
        if (find(scopeId, ExchangeAddress.bracket(selector, number)).isEmpty()) {
            throw new DispatchException(DispatchException.Reason.NOT_FOUND,
                "no bracket " + selector + "/" + number + ". A bracket opens with its .0 "
                    + "exchange; an empty bracket cannot exist.");
        }
    }
}
