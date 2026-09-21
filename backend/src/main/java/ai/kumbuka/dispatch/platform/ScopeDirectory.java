package ai.kumbuka.dispatch.platform;

import ai.kumbuka.dispatch.domain.DispatchException;
import ai.kumbuka.dispatch.repository.ScopeAccessRepository;
import ai.kumbuka.dispatch.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves a scope name against the platform's published read contract.
 *
 * <p>This service holds no scope table of its own and never reads the
 * platform's base tables. It holds {@code SELECT} on exactly one view and
 * nothing else, and the view answers one question — may this subject enter
 * this scope — without publishing the membership that produces the answer.
 * Existence in the result IS the permission.
 *
 * <h2>The session contract</h2>
 *
 * Two settings, both bound <strong>transaction-locally</strong>:
 * {@code app.tenant_id} and {@code app.subject}. Transaction-local is not a
 * detail. A session-wide {@code SET} survives the connection's return to the
 * pool, so the next caller on that connection inherits the previous caller's
 * subject — a leak that appears under load, on a warm pool, and never in a
 * test.
 *
 * <h2>Why an empty result is an error here</h2>
 *
 * Under row-level security a missing transaction boundary produces zero rows,
 * and zero rows reads exactly like "no such scope". That resemblance is the
 * trap: the plausible repair for "no such scope" is to widen a privilege or
 * to fall back on a local table, and both would be repairs to a symptom whose
 * cause was a forgotten binding. So the binding is checked first and
 * separately, and its absence is a different typed error from an
 * unresolvable scope. Neither is ever an empty return.
 */
@ApplicationScoped
@TenantBound
public class ScopeDirectory {

    /** Bound by the same convention as every logger here: no title, no body, no
     *  metadata text, no token, and no actor. A slug is a scope name and an
     *  address; the subject that asked for it is the audit log's business. */
    private static final Logger LOG = Logger.getLogger(ScopeDirectory.class);

    /**
     * The one kind this service refuses, spelled as the platform publishes it.
     *
     * <p>Written as the value rather than as "not project and not global": the
     * platform may add a kind, and a service that refused everything it did
     * not recognise would refuse the new one before anybody decided that is
     * what should happen. Refusing the one that is decided leaves an unknown
     * kind to arrive as a scope that works, which is the failure direction a
     * later reader can see.
     */
    private static final String KIND_PRIVATE = "private";

    @Inject ScopeAccessRepository scopes;

    /**
     * The scope a caller named, or a typed refusal.
     *
     * @param subject the calling subject, as derived from the token
     * @param slug    the scope name the caller used
     * @param access  what the call is about to do in that scope
     */
    @Transactional
    public ScopeAccess resolve(String subject, String slug, Access access) {
        bindSubject(subject);
        requireSessionBound();

        Optional<ScopeAccessRepository.ScopeAccessRow> row = scopes.findBySlug(slug);

        if (row.isEmpty()) {
            // Reached only with both settings bound, so this genuinely means
            // "no such scope for this subject" and not "nothing was bound".
            LOG.warnf("scope '%s' unresolved: %s", slug,
                DispatchException.Reason.SCOPE_UNRESOLVED);
            throw new DispatchException(DispatchException.Reason.SCOPE_UNRESOLVED,
                "no scope '" + slug + "' is open to this subject. The directory answers "
                    + "for the bound subject only, and existence in its answer is the "
                    + "permission — so this is a refusal, not a missing row to be "
                    + "worked around.");
        }

        ScopeAccessRepository.ScopeAccessRow found = row.get();
        ScopeAccess resolved = new ScopeAccess(
            found.scopeId(),
            found.tenantId(),
            found.slug(),
            found.archived(),
            found.kind(),
            found.locked(),
            found.canWrite());

        requireServedKind(resolved);
        requireWritable(resolved, access);

        LOG.debugf("resolved scope '%s'", slug);
        return resolved;
    }

    /**
     * Refuses a scope of a kind this service does not serve.
     *
     * <p>Only one kind is refused and it is the private one. A private scope
     * is a per-tenant container for memory content, and the platform publishes
     * it here because one contract answers for every service — not because
     * every service answers for it. An exchange in a private scope is not a
     * thing this service has a meaning for, so the refusal is a category
     * statement and not a permission one: the caller is not told to come back
     * with a better token, it is told the address names something this scheme
     * does not carry.
     *
     * <p>Before the write check rather than after, and on a read as well as on
     * a write. Reading an exchange out of a private scope is as meaningless as
     * writing one into it, and a service that refused only the write would be
     * saying the read was fine — which is a statement about memory content it
     * has no standing to make.
     */
    private void requireServedKind(ScopeAccess scope) {
        if (KIND_PRIVATE.equals(scope.kind())) {
            LOG.warnf("scope of kind '%s' refused: %s", scope.kind(),
                DispatchException.Reason.SCOPE_KIND_UNSUPPORTED);
            throw new DispatchException(DispatchException.Reason.SCOPE_KIND_UNSUPPORTED,
                "a private scope is not served by this service. Private scopes are "
                    + "per-tenant containers for memory content, and an exchange has no "
                    + "meaning in one — so this is what the scheme carries, not what "
                    + "this caller may reach. Name a project or a global scope.");
        }
    }

    /**
     * Refuses a write the platform does not permit, and keeps the two reasons
     * for that apart.
     *
     * <p><strong>The lock is checked first, and the order is load-bearing.</strong>
     * The view derives {@code can_write} as {@code NOT locked AND …}, so a
     * locked scope always arrives with the write right already false. Checking
     * the write right first would therefore answer every locked scope with
     * {@code SCOPE_READ_ONLY} and leave {@code SCOPE_LOCKED} unreachable — a
     * code that exists, is declared, and can never be produced. The two are
     * different things to a caller: a lock is lifted by whoever locked the
     * scope, a missing write right is lifted by whoever administers the
     * membership, and telling somebody to go to the wrong one of those is
     * worse than telling them nothing.
     *
     * <p>Reading stays permitted in both cases. Neither condition is about
     * seeing the scope — visibility is the directory's answer, and this
     * caller already has it.
     */
    private void requireWritable(ScopeAccess scope, Access access) {
        if (access != Access.WRITE) {
            return;
        }

        if (scope.locked()) {
            LOG.warnf("write into a locked scope refused: %s",
                DispatchException.Reason.SCOPE_LOCKED);
            throw new DispatchException(DispatchException.Reason.SCOPE_LOCKED,
                "this scope is locked, so it refuses every write over a service channel "
                    + "whatever the caller's role. Reading it is unaffected. The lock is "
                    + "the scope's own state and is lifted where it was set, not by "
                    + "presenting a different token here.");
        }

        if (!scope.canWrite()) {
            LOG.warnf("write without the write right refused: %s",
                DispatchException.Reason.SCOPE_READ_ONLY);
            throw new DispatchException(DispatchException.Reason.SCOPE_READ_ONLY,
                "this caller may read this scope but not write to it over a service "
                    + "channel. The write right is the platform's answer about the "
                    + "membership, not this service's about the exchange — so no verb "
                    + "here reaches the effect, and the remedy is the membership.");
        }
    }

    /**
     * Binds the calling subject for this transaction.
     *
     * <p>{@code is_local = true} is the whole safety property: the value resets
     * at commit or rollback and cannot ride a pooled connection into the next
     * caller's transaction.
     */
    private void bindSubject(String subject) {
        if (subject == null || subject.isBlank()) {
            throw new DispatchException(DispatchException.Reason.SESSION_NOT_BOUND,
                "there is no subject to bind to app.subject. The directory answers for a "
                    + "subject, so resolving without one would be asking a question with "
                    + "no asker — and the answer would be zero rows, which reads as "
                    + "'no such scope'.");
        }
        scopes.bindSubject(subject);
    }

    /**
     * Fails loudly when either setting is unbound.
     *
     * <p>This runs BEFORE the query rather than interpreting its result,
     * because after the fact the two cases are indistinguishable: both produce
     * zero rows. Checking first is what lets the refusal name the actual cause,
     * and naming the cause is what stops the next person from repairing the
     * wrong thing.
     */
    private void requireSessionBound() {
        Object tenant = scopes.boundTenant();
        Object subject = scopes.boundSubject();

        if (tenant == null || subject == null) {
            LOG.warnf("directory call with unbound session: %s",
                DispatchException.Reason.SESSION_NOT_BOUND);
            throw new DispatchException(DispatchException.Reason.SESSION_NOT_BOUND,
                ("the session contract is not bound (app.tenant_id=%s, app.subject=%s), so "
                    + "the directory would return zero rows for every scope. That reads as "
                    + "'no such scope' and invites a repair to the privileges — which is "
                    + "why this fails here instead of returning nothing.")
                    .formatted(tenant == null ? "unset" : "set",
                               subject == null ? "unset" : "set"));
        }
    }

    /**
     * One row of the read contract: the access answer, never the membership
     * behind it.
     *
     * <p>{@code archived} is published rather than filtered, deliberately: a
     * write into a retired scope must be refusable with a specific error
     * rather than with "not found", and a directory that hid archived scopes
     * could not tell the two apart.
     */
    public record ScopeAccess(UUID scopeId, UUID tenantId, String slug, boolean archived,
                             String kind, boolean locked, boolean canWrite) {
    }
}
