package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.repository.SpentKeyRepository;
import ai.kumbuka.dispatch.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What an idempotency key means, and the only place it means it.
 *
 * <p>Section 3 of the contract states the rule in one sentence and it has three
 * outcomes, not two:
 *
 * <ul>
 *   <li>no key, or a key nobody has spent in this scope in twenty-four hours —
 *       the call runs;</li>
 *   <li>the same key, the same call, the same arguments — the call does not
 *       run, and the answer is the first call's exchange as it stands now;</li>
 *   <li>the same key, a different call or different arguments — {@code
 *       IDEMPOTENCY_KEY_REUSED}, and nothing runs.</li>
 * </ul>
 *
 * <p>The third is the one that is easy to leave out and expensive to leave
 * out. A caller that reuses a key by accident — a loop variable that did not
 * advance — would otherwise be told its second, different commission succeeded
 * and handed the first one's address, and would discover the loss much later
 * by reading what it thought it had written.
 *
 * <h2>Why this is in the domain and not in an adapter</h2>
 *
 * Because it decides whether a write happens, and it has to decide it inside
 * the transaction that would do the writing. An adapter-side check would read
 * the ledger in one transaction and commission in another, so two retries a
 * few milliseconds apart would both find no row. The lock in {@link
 * SpentKeyRepository#lockByKey} is what closes that window, and a lock is only
 * worth holding inside the transaction it protects.
 */
@ApplicationScoped
@TenantBound
public class IdempotencyService {

    /**
     * What this logger may say: the call and the scope. Never the key itself —
     * a key is the caller's own choice and may well be derived from something
     * it considers private, and a log shipper carrying it out of the container
     * would be carrying an identifier of the caller's own making.
     */
    private static final Logger LOG = Logger.getLogger(IdempotencyService.class);

    @Inject SpentKeyRepository spent;

    private final Clock clock;

    IdempotencyService() {
        this(Clock.systemUTC());
    }

    IdempotencyService(Clock clock) {
        this.clock = clock;
    }

    /**
     * The exchange a repeat of this call should answer with, if it is one.
     *
     * <p>Empty means "run the call". Present means "do not run it, and answer
     * with this identity". The refusal case throws rather than returning, for
     * the reason every refusal in this service throws: an outcome the caller
     * must not confuse with either of the other two.
     *
     * @param key      what the caller chose, or that it chose nothing
     * @param call     the call's own name, on the caller's surface
     * @param digest   {@link #digestOf} over the arguments that decide what the
     *                 call writes
     * @throws DispatchException {@code IDEMPOTENCY_KEY_REUSED} when the key was
     *         spent on a different call within the window
     */
    @Transactional
    public Optional<Long> firstAnswerFor(UUID scopeId, Actor actor, IdempotencyKey key,
                                         String call, String digest) {
        if (!(key instanceof IdempotencyKey.Given given)) {
            return Optional.empty();
        }

        Optional<SpentKey> held = spent.lockByKey(scopeId, actor.subject(), given.value());
        if (held.isEmpty()) {
            return Optional.empty();
        }

        SpentKey row = held.get();
        if (!row.stillStandsAt(Instant.now(clock))) {
            // Past the window. The key is the caller's to spend again, and the
            // row is overwritten by `remember` rather than removed — there is
            // no delete verb here, and an expiry job whose absence silently
            // extended the window is exactly what asking at read time avoids.
            return Optional.empty();
        }
        if (!row.records(call, digest)) {
            LOG.debugf("idempotency key reused on %s in scope %s", call, scopeId);
            throw new DispatchException(DispatchException.Reason.IDEMPOTENCY_KEY_REUSED,
                "the key was already spent on a different call in this scope");
        }

        LOG.debugf("idempotent repeat of %s in scope %s", call, scopeId);
        return Optional.of(row.exchangeId);
    }

    /**
     * Remembers what this call answered with, so a retry of it does not act
     * again.
     *
     * <p>Called after the act and inside its transaction, so a call that rolls
     * back leaves no memory of having happened — which is the honest state: it
     * did not.
     */
    @Transactional
    public void remember(UUID scopeId, Actor actor, IdempotencyKey key, String call,
                         String digest, Exchange produced) {
        if (!(key instanceof IdempotencyKey.Given given)) {
            return;
        }

        Instant now = Instant.now(clock);
        Optional<SpentKey> held = spent.lockByKey(scopeId, actor.subject(), given.value());

        SpentKey row = held.orElseGet(SpentKey::new);
        row.scopeId = scopeId;
        row.callerSubject = actor.subject();
        row.key = given.value();
        row.callName = call;
        row.argumentDigest = digest;
        row.exchangeId = produced.id;
        row.firstSeenAt = now;

        if (held.isEmpty()) {
            spent.insert(row);
        }
        // An entry that was there is updated in place: it is the same key of
        // the same caller in the same scope, past its window, being spent
        // again. The unique constraint admits one row per triple, so replacing
        // the row's content IS the second spending.
    }

    /**
     * A digest over what the call writes, in a canonical form.
     *
     * <p>Order-bearing and separator-delimited: the values are joined with a
     * character that cannot occur in any of them, so {@code ["ab", "c"]} and
     * {@code ["a", "bc"]} cannot collide into one digest. A null is written as
     * an empty segment rather than skipped, because "absent" and "empty" are
     * different calls and a digest that could not tell them apart would treat
     * one as a repeat of the other.
     */
    public static String digestOf(List<String> values) {
        StringBuilder canonical = new StringBuilder();
        for (String value : values) {
            // An absent value and an empty one are different calls — a
            // commission with no parent is not one with an empty parent — so
            // the absent case gets a mark of its own instead of being written
            // as the empty string. The mark is a control character no value
            // can contain, which is what makes the distinction real rather
            // than merely unlikely.
            canonical.append(value == null ? "\u001E" : value).append('\u001F');
        }
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                sha256.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the platform", impossible);
        }
    }
}
