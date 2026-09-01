package ai.kumbuka.dispatch.api;

import ai.kumbuka.dispatch.api.payload.Payloads;
import ai.kumbuka.dispatch.domain.Actor;
import ai.kumbuka.dispatch.domain.DispatchException;
import ai.kumbuka.dispatch.domain.Exchange;
import ai.kumbuka.dispatch.domain.ExchangeAddress;
import ai.kumbuka.dispatch.domain.ExchangeService;
import ai.kumbuka.dispatch.domain.ExchangeStatus;
import ai.kumbuka.dispatch.domain.ExchangeView;
import ai.kumbuka.dispatch.platform.ScopeDirectory;
import ai.kumbuka.dispatch.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * The thirteen verbs, once.
 *
 * <p>Both expositions call this and neither reimplements it. That is what
 * makes "REST is the complete surface and MCP only omits" a property of the
 * construction rather than a claim about it: an omission is a tool the MCP
 * adapter does not declare, and an addition is impossible because there is
 * nothing here for one adapter to reach that the other cannot.
 *
 * <p>Two things deliberately do <strong>not</strong> live here, because they
 * are expression rather than offering. The HTTP form of a verb — colon
 * notation, 201 with {@code Location}, 405 with {@code Allow} — is the REST
 * adapter's. The shape of a JSON-RPC tool call is the MCP adapter's. What is
 * shared is the act, its checks and their order.
 *
 * <h2>The order of the checks, and why it is here</h2>
 *
 * Grammar (stage 1) then scope visibility (stage 2) then vocabulary (stage 3)
 * then resolution (stage 4). It is in this class rather than in each adapter
 * because a check order is exactly the thing that drifts between two copies,
 * and the one that would drift first is the one whose whole purpose is that a
 * scope the caller cannot see answers 404 however broken the rest of the call
 * is.
 */
@ApplicationScoped
@TenantBound
public class VerbSurface {

    /**
     * The convention this service's loggers keep: address, selector, number,
     * transition, status, typed reason, duration, scope id — and never a
     * title, a body, metadata text, a token, a receipt or the actor. A guard
     * enforces it over this tree.
     */
    private static final Logger LOG = Logger.getLogger(VerbSurface.class);

    @Inject ExchangeService exchanges;
    @Inject ScopeDirectory scopes;

    // ======================================================================
    // create — two address forms, chosen by the form
    // ======================================================================

    /** Opens a bracket. The collection form. */
    @Transactional
    public Result create(Actor actor, String rawScope, String rawSelector,
                         Payloads.CreateRequest request) {
        Entry in = collection(actor, rawScope, rawSelector);
        Payloads.CreateRequest body = required(request);

        Exchange created = exchanges.openBracket(in.scopeId(), in.selector(), body.title(),
            body.apparatus(), body.date(), actor);

        LOG.infof("create %s in scope %s", created.address(), in.scopeId());
        return at(in, addressOf(created));
    }

    /** Adds a child to an open bracket. The sub-collection form. */
    @Transactional
    public Result createChild(Actor actor, String rawScope, String rawSelector, String rawId,
                              Payloads.CreateRequest request) {
        Entry in = item(actor, rawScope, rawSelector, rawId);
        requireBracketRoot(in.address(), "children");
        Payloads.CreateRequest body = required(request);

        Exchange created = exchanges.addChild(in.scopeId(), in.selector(),
            in.address().number(), body.title(), body.apparatus(), body.date(), actor);

        LOG.infof("create %s in scope %s", created.address(), in.scopeId());
        return at(in, addressOf(created));
    }

    // ======================================================================
    // read — onto view, and onto nothing else
    // ======================================================================

    /**
     * One exchange, as this caller may see it.
     *
     * <p>Onto {@link ExchangeService#view} and never onto
     * {@link ExchangeService#read}. The domain carries both: {@code read}
     * takes no actor and hands back the raw entity, and the actor-dependent
     * projection lives only in {@code view}. Mapping this verb onto the
     * same-named method would hand out the body ungated and break a ratified
     * bolt without touching a line of security code — which is precisely why
     * it carries a red probe rather than a comment.
     */
    @Transactional
    public Result read(Actor actor, String rawScope, String rawSelector, String rawId) {
        Entry in = item(actor, rawScope, rawSelector, rawId);
        return at(in, in.address());
    }

    // ======================================================================
    // update — the field write
    // ======================================================================

    /**
     * Replaces the handover draft, against the conflict token.
     *
     * <p>The token is checked in the same transaction as the write and is
     * <strong>not enforced atomically</strong>: the entity carries no version
     * column and adding one would change the domain. The window is one
     * transaction rather than zero. That is strictly better than the
     * predecessor, which takes no token on any writing verb, and it is
     * reported rather than presented as finished.
     */
    @Transactional
    public Result update(Actor actor, String rawScope, String rawSelector, String rawId,
                         String conflictToken, Payloads.UpdateRequest request) {
        Entry in = item(actor, rawScope, rawSelector, rawId);
        Payloads.UpdateRequest body = required(request);
        requireConflictToken(in, conflictToken);

        exchanges.writeHandoverDraft(in.scopeId(), in.address(), actor,
            body.receipt(), body.draft(), body.metadata());

        LOG.infof("update %s in scope %s", in.address(), in.scopeId());
        return at(in, in.address());
    }

    // ======================================================================
    // append — additive, and never removable afterwards
    // ======================================================================

    @Transactional
    public Result append(Actor actor, String rawScope, String rawSelector, String rawId,
                         Payloads.AppendRequest request) {
        Entry in = item(actor, rawScope, rawSelector, rawId);
        Payloads.AppendRequest body = required(request);

        Exchange addendum = exchanges.addAddendum(in.scopeId(), in.address(), body.title(),
            body.apparatus(), body.date(), actor);

        LOG.infof("append %s in scope %s", addendum.address(), in.scopeId());
        return at(in, addressOf(addendum));
    }

    // ======================================================================
    // The transitions
    // ======================================================================

    @Transactional
    public Result send(Actor actor, String rawScope, String rawSelector, String rawId,
                       Payloads.SendRequest request) {
        Entry in = item(actor, rawScope, rawSelector, rawId);
        exchanges.send(in.scopeId(), in.address(), actor,
            request == null ? null : request.metadata());
        return at(in, in.address());
    }

    /**
     * Ratifies the handover.
     *
     * <p>The executing apparatus cannot reach this, and the refusal is the
     * core's. Nothing here substitutes an identity on the way past — that is
     * the whole of the second red probe, and the reason this method is three
     * lines rather than four.
     */
    @Transactional
    public Result accept(Actor actor, String rawScope, String rawSelector, String rawId) {
        Entry in = item(actor, rawScope, rawSelector, rawId);
        exchanges.ratify(in.scopeId(), in.address(), actor);
        return at(in, in.address());
    }

    /** Claims the exchange and returns the receipt, which is the only copy. */
    @Transactional
    public ClaimOutcome claim(Actor actor, String rawScope, String rawSelector, String rawId,
                              Payloads.ClaimRequest request) {
        Entry in = item(actor, rawScope, rawSelector, rawId);
        ExchangeService.ClaimResult claimed = exchanges.takeup(in.scopeId(), in.address(),
            actor, required(request).parsed());
        return new ClaimOutcome(at(in, in.address()), claimed.receipt());
    }

    @Transactional
    public Result release(Actor actor, String rawScope, String rawSelector, String rawId) {
        Entry in = item(actor, rawScope, rawSelector, rawId);
        exchanges.revert(in.scopeId(), in.address(), actor);
        return at(in, in.address());
    }

    /**
     * The executor does not deliver. One verb over two domain methods, chosen
     * by the prior state inside one transaction.
     *
     * <p>Where the exchange is in neither prior state the refusal comes from
     * the domain and names the states that would have worked. A refusal
     * invented here would have to guess which of the two the caller meant.
     */
    @Transactional
    public Result abandon(Actor actor, String rawScope, String rawSelector, String rawId) {
        Entry in = item(actor, rawScope, rawSelector, rawId);
        ExchangeStatus before = exchanges.view(in.scopeId(), in.address(), actor).status();

        if (before == ExchangeStatus.ACTIVE) {
            exchanges.fail(in.scopeId(), in.address(), actor);
        } else {
            exchanges.reject(in.scopeId(), in.address(), actor);
        }
        return at(in, in.address());
    }

    @Transactional
    public Result block(Actor actor, String rawScope, String rawSelector, String rawId) {
        Entry in = item(actor, rawScope, rawSelector, rawId);
        exchanges.block(in.scopeId(), in.address(), actor);
        return at(in, in.address());
    }

    @Transactional
    public Result resume(Actor actor, String rawScope, String rawSelector, String rawId) {
        Entry in = item(actor, rawScope, rawSelector, rawId);
        exchanges.resume(in.scopeId(), in.address(), actor);
        return at(in, in.address());
    }

    @Transactional
    public Result close(Actor actor, String rawScope, String rawSelector, String rawId) {
        Entry in = item(actor, rawScope, rawSelector, rawId);
        exchanges.close(in.scopeId(), in.address(), actor);
        return at(in, in.address());
    }

    @Transactional
    public Result consume(Actor actor, String rawScope, String rawSelector, String rawId) {
        Entry in = item(actor, rawScope, rawSelector, rawId);
        exchanges.consume(in.scopeId(), in.address(), actor);
        return at(in, in.address());
    }

    // ======================================================================
    // The four the scheme does not carry
    //
    // Each is a typed category error naming the reason -- never a 404, never
    // an unimplemented path, never a silent absence. A caller has to be able
    // to tell "this will never work, and here is why" from "not there right
    // now" and from "not built yet"; only the first stops it retrying.
    //
    // All four sit BEHIND scope visibility, because capability is declared
    // per scope and a category error is therefore in principle a statement
    // about a scope. Today the declaration is identical for every scope so
    // nothing leaks either way; the order is the ratified one and a per-scope
    // declaration is the direction of travel.
    // ======================================================================

    /**
     * Granted to the scheme in the capability matrix, unmapped in the mapping
     * table. Reported, not decided.
     */
    @Transactional
    public void query(Actor actor, String rawScope, String rawSelector) {
        collection(actor, rawScope, rawSelector);
        throw notCarried("query",
            "It is granted to the scheme in the capability matrix and left unmapped in the "
                + "mapping table, and the two have not been reconciled. A category error "
                + "with an open specification behind it, not a route waiting to be built.");
    }

    /**
     * The atomic draw. It has its own draw semantics, its own filter model and
     * a tombstone decision, and it is not smuggled in as a loop over a read.
     */
    @Transactional
    public void claimNext(Actor actor, String rawScope, String rawSelector) {
        collection(actor, rawScope, rawSelector);
        throw notCarried("claim_next",
            "The atomic draw carries its own draw semantics and its own filter model. "
                + "Building it as a loop over a read here would be a second construction "
                + "of it, in the place least able to make it atomic.");
    }

    /** Refused on the machine surface: withdrawal is a ratchet. */
    @Transactional
    public void withdraw(Actor actor, String rawScope, String rawSelector, String rawId) {
        item(actor, rawScope, rawSelector, rawId);
        throw new SurfaceException(SurfaceException.Reason.WITHDRAWAL_VIA_CONSOLE_ONLY,
            "'withdraw' is not offered on the machine surface. Withdrawal is a ratchet and "
                + "is restorable only through the console, so the act has an address and "
                + "this is not it. A typed refusal naming where it lives, not an absence.");
    }

    /** No declared address depth, so fail-closed leaves it unbuildable. */
    @Transactional
    public void validate(Actor actor, String rawScope, String rawSelector, String rawId) {
        item(actor, rawScope, rawSelector, rawId);
        throw new SurfaceException(SurfaceException.Reason.VERB_DEPTH_UNDECLARED,
            "'validate' declares no address depth. Undeclared means complete address only, "
                + "and a consistency check over one scope cannot act at that depth — so the "
                + "verb is unbuildable fail-closed rather than unbuilt. Declaring a depth "
                + "here would be deciding a specification gap in an adapter.");
    }

    private static SurfaceException notCarried(String verb, String why) {
        return new SurfaceException(SurfaceException.Reason.VERB_NOT_CARRIED,
            "the dispatch scheme does not carry '" + verb + "' on this surface. " + why);
    }

    // ======================================================================
    // Stage 1 and stage 2
    // ======================================================================

    /**
     * Grammar, then scope visibility, for a collection address.
     *
     * <p>The grammar runs against the raw strings before anything is resolved.
     * Stage 1 is decidable without knowing a scope, so its refusal leaks
     * nothing; every later refusal necessarily reveals that a lookup happened.
     */
    private Entry collection(Actor actor, String rawScope, String rawSelector) {
        String slug = AddressParser.scope(rawScope);
        String selector = AddressParser.selector(rawSelector);
        return new Entry(actor, resolve(actor, slug), selector, null);
    }

    /** Grammar, then scope visibility, for a complete address. */
    private Entry item(Actor actor, String rawScope, String rawSelector, String rawId) {
        String slug = AddressParser.scope(rawScope);
        ExchangeAddress address = AddressParser.item(rawSelector, rawId);
        return new Entry(actor, resolve(actor, slug), address.selector(), address);
    }

    private UUID resolve(Actor actor, String slug) {
        return scopes.resolve(actor.subject(), slug).scopeId();
    }

    // ======================================================================
    // Answers
    // ======================================================================

    /**
     * The answer to any verb: the exchange as this caller may see it, plus its
     * conflict token.
     *
     * <p>Always through {@code view}, never through the entity a transition
     * returned. An {@link Exchange} carries the body, so serialising one would
     * hand out exactly what the projection exists to withhold — on eleven
     * routes at once, and without anybody having decided to.
     */
    private Result at(Entry in, ExchangeAddress address) {
        ExchangeView view = exchanges.view(in.scopeId(), address, in.actor());
        return new Result(address, Payloads.ExchangeResponse.of(view), conflictToken(in, address));
    }

    /**
     * The exchange's last write, truncated to the resolution the column
     * stores.
     *
     * <p>An untruncated nanosecond value would be handed out on the write and
     * never match on the read back, and a token that never matches is a token
     * that turns every second write into a 412.
     *
     * <p><strong>This is the one place the entity is touched, and only its
     * timestamp is taken.</strong> It is not the verb mapping in disguise:
     * every payload above is built from a view, and what is taken here is a
     * field no view carries and no projection would gate — a version marker.
     */
    private String conflictToken(Entry in, ExchangeAddress address) {
        try {
            Instant written = exchanges.read(in.scopeId(), address).updatedAt;
            return written == null ? null : written.truncatedTo(ChronoUnit.MICROS).toString();
        } catch (DispatchException e) {
            if (e.reason() == DispatchException.Reason.ADDENDUM_NOT_DRAWABLE) {
                // An addendum is not independently drawable, so there is no
                // version marker for one. It takes no field write either, so
                // the token it would carry has nothing to protect.
                return null;
            }
            throw e;
        }
    }

    private void requireConflictToken(Entry in, String presented) {
        if (presented == null || presented.isBlank()) {
            throw new SurfaceException(SurfaceException.Reason.CONFLICT_TOKEN_MISSING,
                "a field write declares conflict-token repetition, so it carries one. The "
                    + "token is the one handed out with the last read. Without it a retry "
                    + "across a network cannot be told from a second, different write.");
        }

        String held = conflictToken(in, in.address());
        if (held == null || !held.equals(unquote(presented.trim()))) {
            throw new SurfaceException(SurfaceException.Reason.CONFLICT_TOKEN_STALE,
                "the conflict token is not the one " + in.address() + " holds. Somebody "
                    + "else wrote it since this caller last read it, and overwriting on a "
                    + "stale token is the lost update the token exists to prevent.");
        }
    }

    /** Tolerates the quoted form an HTTP entity tag arrives in. */
    private static String unquote(String raw) {
        String value = raw.startsWith("W/") ? raw.substring(2) : raw;
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
            ? value.substring(1, value.length() - 1)
            : value;
    }

    private static ExchangeAddress addressOf(Exchange e) {
        return new ExchangeAddress(e.selector, e.number, e.sub, e.addendumSuffix);
    }

    private static <T> T required(T body) {
        if (body == null) {
            throw new SurfaceException(SurfaceException.Reason.PAYLOAD_MALFORMED,
                "this verb takes arguments and none arrived.");
        }
        return body;
    }

    /**
     * The {@code children} sub-collection exists at a bracket root and nowhere
     * else.
     *
     * <p>A form refusal rather than a lookup: a child numbers within the
     * bracket instance, so {@code 149.2/children} would silently mean "a child
     * of bracket 149" — one address with two readings, which is the ambiguity
     * the address space exists to exclude.
     */
    private static void requireBracketRoot(ExchangeAddress address, String subCollection) {
        if (address.sub() != 0 || address.isAddendum()) {
            throw new SurfaceException(SurfaceException.Reason.ADDRESS_MALFORMED,
                "the '" + subCollection + "' sub-collection exists at a bracket root — "
                    + "'<number>.0' — and " + address + " is not one. A child numbers "
                    + "within the bracket instance, so addressing it anywhere else would "
                    + "give one address two readings.");
        }
    }

    /** What a verb answers with, before either adapter dresses it. */
    public record Result(ExchangeAddress address, Payloads.ExchangeResponse exchange,
                         String conflictToken) {
    }

    /** A claim, and the receipt that is its only copy. */
    public record ClaimOutcome(Result result, String receipt) {
    }

    /** Everything one call needs once the first two stages have held. */
    private record Entry(Actor actor, UUID scopeId, String selector, ExchangeAddress address) {
    }
}
