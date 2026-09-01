package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.NoResultException;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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

    /**
     * What this logger may say is fixed by convention and enforced by a guard:
     * address, selector, number, transition, status, typed reason, duration,
     * scope id. Never a title, a body, metadata text, a token or a receipt —
     * the operator boundary is built as a missing GRANT, and a log shipper
     * carrying a title out of the container walks around it. The actor is
     * absent too: that belongs in the audit log, whose collection is governed,
     * and a second aggregatable stream of the same fact would be the
     * circumvention of not collecting behavioural data.
     */
    private static final Logger LOG = Logger.getLogger(ExchangeService.class);

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
                                String apparatus, LocalDate date, Actor actor) {
        selectors.requireDeclared(scopeId, selector);
        int number = allocateNumber(scopeId, selector);
        return insert(new NewExchange(scopeId, selector, number, 0, null, title,
            apparatus, date, actor.subject()));
    }

    /**
     * Adds a child to an open bracket. Children number within the bracket
     * instance, which is a property of the bracket rather than a declared
     * circle of its own.
     */
    @Transactional
    public Exchange addChild(UUID scopeId, String selector, int number, String title,
                             String apparatus, LocalDate date, Actor actor) {
        selectors.requireDeclared(scopeId, selector);
        requireBracketExists(scopeId, selector, number);
        int sub = nextSub(scopeId, selector, number);
        return insert(new NewExchange(scopeId, selector, number, sub, null, title,
            apparatus, date, actor.subject()));
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
                                String apparatus, LocalDate date, Actor actor) {
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
        return insertAddendum(scopeId, base, suffix, title, apparatus, date, actor.subject());
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

    /**
     * The exchanges of one selector, narrowed by the declared filter, in the
     * order of the address space.
     *
     * <p><strong>Returns views, never entities.</strong> That is the
     * projection bolt and not a convenience: {@link ExchangeView} withholds
     * the body from an executing apparatus that has not claimed the exchange,
     * and a listing built on the raw read would hand out every body in the
     * selector to a caller that has claimed nothing — breaking a ratified bolt
     * without touching a line of security code. There is deliberately no
     * overload of this returning {@code List<Exchange>}.
     *
     * <p><strong>The order is the address space's own.</strong> Number, then
     * sub. No second ordering is invented: the addresses are already ordered
     * and readers already know that order, so an ordering by creation time
     * would be a second one nobody reads and the two would disagree the first
     * time an object was created out of sequence.
     *
     * <p>Addenda are excluded, for the same reason {@code read} refuses them:
     * an addendum has no standing of its own and is not independently
     * drawable. A listing that returned addresses the read verb refuses would
     * be handing out addresses that do not work.
     */
    @Transactional
    public List<ExchangeView> query(UUID scopeId, String selector, QueryFilter filter,
                                    Actor actor) {
        selectors.requireDeclared(scopeId, selector);

        StringBuilder jpql = new StringBuilder("""
            SELECT e FROM Exchange e
            WHERE e.scopeId = :scope AND e.selector = :sel
              AND e.addendumSuffix IS NULL
            """);
        // Each declared field that the caller named becomes one conjunct, and
        // its values become the disjunction inside it. Built by appending
        // rather than by string-formatting a predicate: the only things that
        // reach the query text are constants from this file, and every caller
        // value travels as a bound parameter.
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
            .setParameter("sel", selector);
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

        Instant now = Instant.now(clock);
        List<ExchangeView> found = query.getResultList().stream()
            .map(e -> ExchangeView.of(e, actor, now))
            .toList();

        LOG.debugf("query %s: %d hit(s)", selector, found.size());
        return found;
    }

    /**
     * Takes up the next claimable exchange of a selector, and mints its
     * receipt.
     *
     * <p>This is the one writing verb besides {@code create} that acts on a
     * truncated address, and it may because its set semantics is DECLARED and
     * is exactly one. There is no other declarable set semantics — "all" is
     * not one — so every other transition on a collection stays a 405.
     *
     * <h2>The selection, and why it is not a new ordering</h2>
     *
     * The next one by position within the selector: number, then sub. The
     * address space is already ordered and that order is the one readers
     * carry in their heads. Terminal exchanges are skipped, and so is one
     * whose claim is still effective — a claimed exchange is not free, and a
     * draw that returned it would be handing the same work to two executors.
     *
     * <p>An exchange whose claim has LAPSED is claimable again, and is
     * reclaimed here exactly as {@link #takeup} reclaims one: in this
     * claimant's transaction, with this claimant as the actor. That is not a
     * new rule — it is the same rule, and writing a second one for the drawn
     * case is how the two would come to disagree.
     *
     * <h2>Why the row is locked</h2>
     *
     * {@code FOR UPDATE SKIP LOCKED} is the whole verb. Without it two
     * concurrent draws read the same row, both pass the status check, and both
     * award a claim — the second silently overwriting the first, with two
     * executors holding what each believes is an exclusive lease. With it, a
     * row another transaction is claiming is invisible to this one, so each
     * draw either gets an exchange nobody else is taking or gets none.
     *
     * @return the claimed exchange and the receipt, which is the only copy
     * @throws DispatchException with {@code NOTHING_TO_CLAIM} when the
     *         selector holds nothing claimable, which is a different statement
     *         from an address that does not exist
     */
    @Transactional
    public ClaimResult claimNext(UUID scopeId, String selector, Actor actor,
                                 Duration duration) {
        requirePositive(duration);
        selectors.requireDeclared(scopeId, selector);
        Instant now = Instant.now(clock);

        // Native rather than JPQL: the pessimistic lock this needs is
        // SKIP LOCKED, and JPA's LockModeType has no expression for it —
        // PESSIMISTIC_WRITE waits for the other transaction instead of
        // stepping over it, which would serialise every concurrent draw and
        // hand the second caller the row the first just took.
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
            .setParameter("sel", selector)
            .setParameter("now", java.sql.Timestamp.from(now))
            .getResultList();

        if (ids.isEmpty()) {
            throw new DispatchException(DispatchException.Reason.NOTHING_TO_CLAIM,
                "nothing in '" + selector + "' is claimable. Every exchange there is "
                    + "terminal, still a draft, awaiting its commissioner, or effectively "
                    + "held by somebody else. The selector exists — this is an empty draw, "
                    + "not a missing address.");
        }

        // Round-tripped through its text form rather than cast: the driver may
        // hand back a UUID or the string of one depending on how the column is
        // read, and a cast that is right today is a ClassCastException the day
        // that changes.
        Exchange e = em.find(Exchange.class, UUID.fromString(ids.get(0).toString()));
        if (e.status() == ExchangeStatus.ACTIVE) {
            reclaim(e);
        }

        e.apply(Transition.TAKEUP);
        String receipt = Receipt.mint();
        e.award(actor.subject(), receipt, now.plus(duration));
        touch(e, actor.subject());

        LOG.infof("claim_next %s -> %s", e.address(), e.status().wireName());
        return new ClaimResult(e, receipt);
    }

    // ----------------------------------------------------------------------
    // The verbs. One per transition.
    // ----------------------------------------------------------------------

    /** Freezes the dispatch and opens it to an executor. */
    @Transactional
    public Exchange send(UUID scopeId, ExchangeAddress address, Actor actor) {
        return send(scopeId, address, actor, null);
    }

    /**
     * Freezes the dispatch, opens it to an executor, and freezes its metadata
     * at the same gate.
     */
    @Transactional
    public Exchange send(UUID scopeId, ExchangeAddress address, Actor actor,
                         Map<String, String> metadata) {
        Exchange e = require(scopeId, address);
        Metadata.validate(metadata);
        if (metadata != null) {
            e.dispatchMetadata = metadata;
        }
        if (e.apply(Transition.SEND)) {
            e.freezeDispatch(Instant.now(clock));
        }
        touch(e, actor.subject());
        LOG.infof("send %s -> %s", e.address(), e.status().wireName());
        return e;
    }

    /**
     * Shows an open commission without claiming it.
     *
     * <p>Writes nothing. That is the entire reason it is a separate verb: the
     * predecessor merged showing and taking up, and described itself as "the
     * only query in the service that writes" — an honest description of a
     * construction fault. Looking at the queue should not commit anybody to
     * anything.
     *
     * <p>What comes back carries enough to REFUSE and not enough to WORK.
     * There is no body field on {@link ExchangeView} — not an empty one, none
     * — when the caller is an executing apparatus. Withholding it is what makes
     * "no body without a claim" a property rather than an intention: a loser
     * of the race cannot have started, because it never had anything to start
     * from.
     */
    @Transactional
    public ExchangeView view(UUID scopeId, ExchangeAddress address, Actor actor) {
        Exchange e = require(scopeId, address);
        LOG.debugf("view %s", e.address());
        return ExchangeView.of(e, actor, Instant.now(clock));
    }

    /**
     * Claims an open commission and mints the receipt that proves it.
     *
     * <p>The receipt is returned to the winner and stored only as a hash. It
     * is not derived from anything the caller knows or supplies: several
     * executor instances on one machine reach this service through one channel
     * and would collide on any name they could derive for themselves, leaving
     * the service seeing one holder where there are two.
     *
     * @param duration how long the claim stands. Positive is form, not taste:
     *                 a zero or negative duration would award a claim that has
     *                 already lapsed, and every later reader would have to
     *                 decide what that meant.
     * @return the claimed exchange and the receipt, which is the only copy
     */
    @Transactional
    public ClaimResult takeup(UUID scopeId, ExchangeAddress address, Actor actor,
                              Duration duration) {
        requirePositive(duration);
        Exchange e = require(scopeId, address);
        Instant now = Instant.now(clock);

        // An expired claim is reclaimed HERE, in this claimant's transaction,
        // with this claimant as the actor. Nothing else writes it — there is
        // no reaper and no expiry event, so the rule that every audit entry
        // has a verb call and an actor holds without an exception.
        if (e.status() == ExchangeStatus.ACTIVE && !e.claimEffective(now)) {
            reclaim(e);
        }

        e.apply(Transition.TAKEUP);
        String receipt = Receipt.mint();
        e.award(actor.subject(), receipt, now.plus(duration));
        touch(e, actor.subject());

        LOG.infof("takeup %s -> %s", e.address(), e.status().wireName());
        return new ClaimResult(e, receipt);
    }

    @Transactional
    public Exchange reject(UUID scopeId, ExchangeAddress address, Actor actor) {
        return transition(scopeId, address, Transition.REJECT, actor);
    }

    @Transactional
    public Exchange fail(UUID scopeId, ExchangeAddress address, Actor actor) {
        return transition(scopeId, address, Transition.FAIL, actor);
    }

    @Transactional
    public Exchange block(UUID scopeId, ExchangeAddress address, Actor actor) {
        return transition(scopeId, address, Transition.BLOCK, actor);
    }

    @Transactional
    public Exchange resume(UUID scopeId, ExchangeAddress address, Actor actor) {
        return transition(scopeId, address, Transition.RESUME, actor);
    }

    /**
     * Writes or overwrites the handover draft, while the exchange stays active.
     *
     * <p>The draft is replaced wholesale and there is no verb that appends to
     * one. Rework is the normal case, not the exception: the operator reads,
     * the answer does not fit, and the executor writes it again. No new
     * object, no addendum, no reopening — and the intermediate rounds do not
     * survive in the document, which is the deliberate trade. They land in the
     * audit log a level down, and nobody needs a wrong handover text kept.
     *
     * <p>Three bolts stand between a race and a corrupted answer, and they
     * defend different axes rather than repeating each other:
     * no body without a claim, so a loser cannot begin; only the receipt
     * holder writes, plus a console identity; and a ratified exchange takes no
     * further handover at all — a state precondition that does not depend on
     * who is asking, which is the cover for several runs sharing one service
     * identity.
     */
    @Transactional
    public Exchange writeHandoverDraft(UUID scopeId, ExchangeAddress address, Actor actor,
                                       String receipt, String draft,
                                       Map<String, String> metadata) {
        Exchange e = require(scopeId, address);
        Instant now = Instant.now(clock);

        // Bolt three. Deliberately first, and deliberately independent of
        // identity: two runs sharing a service identity would both pass a
        // holder check, and a receipt is a bearer token that instances on one
        // machine can read from a shared filesystem.
        if (e.ratifiedAt() != null) {
            throw new DispatchException(DispatchException.Reason.HANDOVER_ALREADY_RATIFIED,
                e.address() + " carries a ratified handover and takes no further one. "
                    + "A correction to something ratified attaches as an addendum.");
        }

        requireMayWriteHandover(e, actor, now);
        if (actor.isExecutor()) {
            // Bolt two. The subject alone is not enough: several runs can share
            // one service identity, and the receipt is what distinguishes the
            // run that won the award from one that merely looks like it.
            requireReceipt(e, receipt);
        }
        Metadata.validate(metadata);
        e.writeDraft(draft, metadata);
        touch(e, actor.subject());

        LOG.infof("handover draft written on %s", e.address());
        return e;
    }

    /**
     * Ratifies the handover that is already there, and freezes it.
     *
     * <p>Takes no answer text. Ratification is the operator's own act on
     * something somebody else wrote, and a signature that accepted the text
     * would let one call be both the writing and the approving of it — which
     * is not a review.
     *
     * <p>The executing apparatus cannot call this. That is a permission in the
     * core bound to the actor, not an omission in an adapter: anything that
     * must hold has to hold for every caller that reaches the core.
     */
    @Transactional
    public Exchange ratify(UUID scopeId, ExchangeAddress address, Actor actor) {
        if (actor.isExecutor()) {
            LOG.warnf("ratify refused on %s: %s", address,
                DispatchException.Reason.RATIFICATION_NOT_PERMITTED);
            throw new DispatchException(DispatchException.Reason.RATIFICATION_NOT_PERMITTED,
                "the executing apparatus cannot ratify " + address + ". Ratification is "
                    + "the operator's own act; an executor that could approve its own "
                    + "answer would be reviewing itself.");
        }

        Exchange e = require(scopeId, address);
        if (e.handoverBody() == null) {
            throw new DispatchException(DispatchException.Reason.TRANSITION_NOT_PERMITTED,
                e.address() + " has no handover draft to ratify. Ratification freezes an "
                    + "answer that is already there; it does not create one.");
        }

        if (e.apply(Transition.RATIFY)) {
            e.freezeHandover(Instant.now(clock));
        }
        touch(e, actor.subject());

        LOG.infof("ratify %s -> %s", e.address(), e.status().wireName());
        return e;
    }

    @Transactional
    public Exchange close(UUID scopeId, ExchangeAddress address, Actor actor) {
        return transition(scopeId, address, Transition.CLOSE, actor);
    }

    @Transactional
    public Exchange consume(UUID scopeId, ExchangeAddress address, Actor actor) {
        return transition(scopeId, address, Transition.CONSUME, actor);
    }

    /**
     * The deliberate human way back: an active exchange returns to open.
     *
     * <p>Drops the claim and discards any unratified draft, in this
     * transaction. A draft that was never ratified never happened, and leaving
     * one for the next executor to inherit would be worse than deleting it —
     * the inherited text looks plausible and reads as somebody's answer.
     */
    @Transactional
    public Exchange revert(UUID scopeId, ExchangeAddress address, Actor actor) {
        Exchange e = require(scopeId, address);
        e.apply(Transition.REVERT);
        e.releaseClaim();
        e.discardDraft();
        touch(e, actor.subject());
        LOG.infof("revert %s -> %s", e.address(), e.status().wireName());
        return e;
    }

    // ----------------------------------------------------------------------
    // The machinery behind the verbs
    // ----------------------------------------------------------------------

    private Exchange transition(UUID scopeId, ExchangeAddress address,
                                Transition t, Actor actor) {
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
            cascadeToAddenda(scopeId, e, actor.subject());
        }
        touch(e, actor.subject());
        if (moved) {
            LOG.infof("%s %s -> %s", t.verb(), e.address(), e.status().wireName());
        }
        return e;
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

    /**
     * Reclaims an exchange whose claim has lapsed, as part of the next
     * claimant's transaction.
     *
     * <p>This is the only place expiry causes a write, and it is not expiry
     * that causes it — it is the new claim. The orphaned draft goes with the
     * old claim, for the same reason it goes on revert.
     *
     * <p>Takes no actor, which is not an oversight. The attribution happens in
     * the caller, where the new claim is awarded and {@code updated_by} is
     * stamped; and the log line deliberately does not name who reclaimed —
     * that belongs in the audit log under its own rules. A parameter here
     * would suggest this method does something with an actor, and the only
     * honest thing it could do with one is put it somewhere it must not go.
     */
    private void reclaim(Exchange e) {
        LOG.infof("reclaiming %s: previous claim lapsed", e.address());
        e.apply(Transition.REVERT);
        e.releaseClaim();
        e.discardDraft();
    }

    /**
     * Who may write a handover draft: the effective receipt holder, or a
     * console identity.
     *
     * <p>The console exception is not a loophole. Operators edit handovers as
     * a matter of course, and requiring them to hold the claim would mean
     * taking work away from the executor in order to correct its wording.
     */
    private void requireMayWriteHandover(Exchange e, Actor actor, Instant now) {
        if (actor.isConsole()) {
            return;
        }
        if (!e.claimEffective(now)) {
            throw new DispatchException(DispatchException.Reason.CLAIM_REQUIRED,
                e.address() + " carries no effective claim. A handover is written by "
                    + "whoever holds the exchange; a lapsed claim holds nothing.");
        }
        if (!actor.subject().equals(e.effectiveHolder(now))) {
            throw new DispatchException(DispatchException.Reason.CLAIM_REQUIRED,
                e.address() + " is held by somebody else.");
        }
    }

    /**
     * Refuses a receipt supplied where one was minted, and checks the one that
     * was presented.
     */
    private void requireReceipt(Exchange e, String presented) {
        if (presented == null || presented.isBlank()) {
            throw new DispatchException(DispatchException.Reason.CLAIM_REQUIRED,
                e.address() + " needs the receipt that was issued at takeup.");
        }
        if (!e.receiptMatches(presented)) {
            throw new DispatchException(DispatchException.Reason.RECEIPT_MISMATCH,
                "the receipt presented for " + e.address() + " is not the one it holds.");
        }
    }

    private static void requirePositive(Duration duration) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new DispatchException(DispatchException.Reason.CLAIM_DURATION_NOT_POSITIVE,
                "a claim duration must be positive, was " + duration + ". A claim that has "
                    + "already lapsed when it is awarded leaves every later reader to "
                    + "decide what that was supposed to mean.");
        }
    }

    /** A claimed exchange and the receipt that proves it. The receipt is not stored. */
    public record ClaimResult(Exchange exchange, String receipt) {
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
