package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.domain.Actor;
import ai.kumbuka.dispatch.domain.ExchangeAddress;
import ai.kumbuka.dispatch.domain.IdempotencyKey;
import ai.kumbuka.dispatch.domain.SelectorRegistry;
import ai.kumbuka.dispatch.domain.TaskCall;
import ai.kumbuka.dispatch.domain.TaskClaim;
import ai.kumbuka.dispatch.domain.TaskFilter;
import ai.kumbuka.dispatch.domain.TaskListing;
import ai.kumbuka.dispatch.domain.TaskService;
import ai.kumbuka.dispatch.domain.TaskState;
import ai.kumbuka.dispatch.domain.TaskTextView;
import ai.kumbuka.dispatch.domain.TaskVerb;
import ai.kumbuka.dispatch.domain.TaskView;
import ai.kumbuka.dispatch.domain.TextPart;
import ai.kumbuka.dispatch.domain.TextType;
import ai.kumbuka.dispatch.platform.Access;
import ai.kumbuka.dispatch.platform.ScopeDirectory;
import ai.kumbuka.dispatch.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;

/**
 * The twenty-five calls, each bound to the kernel in one transaction.
 *
 * <p>Both surfaces reach the kernel through here and through nothing else:
 * {@link CallRouter} reads the call, checks its arguments against the
 * declaration and calls one method of this class. Each method resolves the
 * scope the caller names — so a scope the caller may not see answers {@code
 * NOT_FOUND} however the rest of the call looks — and then makes exactly one
 * call of {@link TaskService}, which decides. Nothing here decides whether a
 * transition is permitted, and nothing here composes two kernel calls into
 * one act.
 *
 * <p>What comes back is the kernel's {@link TaskView}, which has no component
 * that could carry a text, and the scope's slug the complete address is
 * rendered with. Only {@link #readText} answers a text.
 */
@ApplicationScoped
@TenantBound
public class VerbSurface {

    /**
     * Says call, address and scope id; never a title, a text, metadata, a
     * token, a receipt or the actor. A guard enforces it over this tree.
     */
    private static final Logger LOG = Logger.getLogger(VerbSurface.class);

    @Inject TaskService tasks;
    @Inject ScopeDirectory scopes;
    @Inject SelectorRegistry selectors;

    /** A task the caller addressed: the scope as the caller named it, and the address in it. */
    public record Item(String scope, ExchangeAddress address) {

        /** The complete address, as every answer writes it. */
        public String complete() {
            return address.complete(scope);
        }
    }

    /** What a call on one task answers: its head, and the scope it is addressed in. */
    public record Result(String scope, TaskView view) {

        public String address() {
            return view.address().complete(scope);
        }
    }

    /** A claim: the head and the receipt, which is the only copy there is. */
    public record Claimed(Result result, String receipt) {
    }

    /** A listing: the heads, and whether the page bound cut it. */
    public record Listing(String scope, TaskListing listing) {
    }

    // ======================================================================
    // The calls that are not transitions
    // ======================================================================

    @Transactional
    public Result create(Actor actor, String scope, String selector, Integer parentNumber,
                         TaskService.Draft draft, IdempotencyKey key) {
        UUID scopeId = resolve(actor, scope, Access.WRITE);
        TaskView created = tasks.create(scopeId, selector, parentNumber, draft, actor, key);
        LOG.infof("create %s in scope %s", created.address(), scopeId);
        return new Result(scope, created);
    }

    @Transactional
    public Result update(Actor actor, Item at, TaskService.Draft changes, String conflictToken) {
        UUID scopeId = resolve(actor, at.scope(), Access.WRITE);
        return new Result(at.scope(),
            tasks.update(scopeId, at.address(), changes, conflictToken, actor));
    }

    /** Deletes a draft and answers the complete address it had. */
    @Transactional
    public String delete(Actor actor, Item at, String conflictToken) {
        UUID scopeId = resolve(actor, at.scope(), Access.WRITE);
        return tasks.delete(scopeId, at.address(), conflictToken, actor).complete(at.scope());
    }

    @Transactional
    public Result read(Actor actor, Item at) {
        UUID scopeId = resolve(actor, at.scope(), Access.READ);
        return new Result(at.scope(), tasks.read(scopeId, at.address(), actor));
    }

    /** One part of a task's text: the only answer of this class that carries text. */
    @Transactional
    public TaskTextView readText(Actor actor, Item at, TextPart part) {
        UUID scopeId = resolve(actor, at.scope(), Access.READ);
        return tasks.readText(scopeId, at.address(), part, actor);
    }

    @Transactional
    public Listing query(Actor actor, String scope, String selector, TaskFilter filter,
                         int limit) {
        UUID scopeId = resolve(actor, scope, Access.READ);
        TaskListing listing = tasks.query(scopeId, selector, filter, limit, actor);
        LOG.debugf("query %s in scope %s: %d head(s)", selector, scopeId,
            listing.tasks().size());
        return new Listing(scope, listing);
    }

    /**
     * Adds an addendum to a text of a sent task.
     *
     * <p>Refuses a part a sent task has no text of, by name, before the kernel
     * is called: the kernel answers that case as not found, and an answer that
     * says "nothing is addressed here" about a task the caller just read sends
     * it looking for a typo it does not have. A draft is left to the kernel,
     * which refuses it by its state.
     */
    @Transactional
    public Result annotate(Actor actor, Item at, TextType part, String text, String call,
                           IdempotencyKey key) {
        UUID scopeId = resolve(actor, at.scope(), Access.WRITE);
        TaskView head = tasks.read(scopeId, at.address(), actor);
        boolean written = head.texts().stream()
            .anyMatch(x -> x.type() == part && x.addendumSuffix() == null);
        if (!written && head.state() != TaskState.DRAFT) {
            throw Refused.argumentInvalid(call, "part", part.wireName(),
                at.complete() + " has no " + part.wireName() + " text for an addendum to "
                    + "supplement");
        }
        return new Result(at.scope(),
            tasks.annotate(scopeId, at.address(), part, text, actor, key));
    }

    /**
     * Records what a closed task was curated into.
     *
     * <p>The target's scope is resolved for reading only: the relation is
     * written on the task in its own scope and nothing in the target's, so a
     * target in a scope the caller may read and not write is admissible.
     */
    @Transactional
    public Result relate(Actor actor, Item at, Item target, String conflictToken) {
        UUID scopeId = resolve(actor, at.scope(), Access.WRITE);
        UUID targetScopeId = resolve(actor, target.scope(), Access.READ);
        return new Result(at.scope(), tasks.relate(scopeId, at.address(), targetScopeId,
            target.address(), conflictToken, actor));
    }

    @Transactional
    public Result unrelate(Actor actor, Item at, String conflictToken) {
        UUID scopeId = resolve(actor, at.scope(), Access.WRITE);
        return new Result(at.scope(),
            tasks.unrelate(scopeId, at.address(), conflictToken, actor));
    }

    // ======================================================================
    // The transitions
    // ======================================================================

    /** One transition other than a take-up, as its row of the table decides it. */
    @Transactional
    public Result act(Item at, TaskVerb verb, TaskCall call) {
        UUID scopeId = resolve(call.caller(), at.scope(), Access.WRITE);
        TaskView after = tasks.act(scopeId, at.address(), verb, call);
        LOG.infof("%s %s in scope %s", verb.wireName(), at.address(), scopeId);
        return new Result(at.scope(), after);
    }

    @Transactional
    public Claimed claim(Item at, TaskCall call, IdempotencyKey key) {
        UUID scopeId = resolve(call.caller(), at.scope(), Access.WRITE);
        TaskClaim claimed = tasks.claim(scopeId, at.address(), call, key);
        return new Claimed(new Result(at.scope(), claimed.task()), claimed.receipt());
    }

    @Transactional
    public Claimed claimNext(String scope, String selector, List<String> patterns,
                             TaskCall call, IdempotencyKey key) {
        UUID scopeId = resolve(call.caller(), scope, Access.WRITE);
        TaskClaim claimed = tasks.claimNext(scopeId, selector, patterns, call, key);
        LOG.infof("claim_next %s in scope %s", claimed.task().address(), scopeId);
        return new Claimed(new Result(scope, claimed.task()), claimed.receipt());
    }

    // ======================================================================
    // What a refusal needs to name
    // ======================================================================

    /**
     * The bracket kinds a scope declares, for the refusal that names them.
     * Behind scope visibility like everything else.
     */
    @Transactional
    public List<String> declaredSelectors(Actor actor, String scope) {
        UUID scopeId = resolve(actor, scope, Access.READ);
        return selectors.declared(scopeId).stream().map(s -> s.name).toList();
    }

    /**
     * The one way to a scope: the platform's directory decides whether the
     * caller may see it and, for a write, whether it may write to it.
     */
    private UUID resolve(Actor actor, String scope, Access access) {
        return scopes.resolve(actor.subject(), scope, access).scopeId();
    }
}
