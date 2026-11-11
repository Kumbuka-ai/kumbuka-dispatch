/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.domain.Actor;
import ai.kumbuka.dispatch.domain.ExchangeAddress;
import ai.kumbuka.dispatch.domain.IdempotencyKey;
import ai.kumbuka.dispatch.domain.SelectorRegistry;
import ai.kumbuka.dispatch.domain.Task;
import ai.kumbuka.dispatch.domain.TaskCall;
import ai.kumbuka.dispatch.domain.TaskClaim;
import ai.kumbuka.dispatch.domain.TaskFilter;
import ai.kumbuka.dispatch.domain.Page;
import ai.kumbuka.dispatch.domain.PageRequest;
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
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The twenty-eight calls, each bound to the kernel in one transaction.
 *
 * <p>Both surfaces reach the kernel through here and through nothing else
 * (C1.1): the adapter reads the call, checks its form against the declaration
 * and calls one method of this class. Each method resolves the
 * scope the caller names — so a scope the caller may not see answers {@code
 * NOT_FOUND} however the rest of the call looks — and then makes exactly one
 * call of {@link TaskService}, which decides. Nothing here decides whether a
 * transition is permitted, and nothing here composes two kernel calls into
 * one act.
 *
 * <p>What comes back is the kernel's {@link TaskView}, which has no component
 * that could carry a text, and the scope's slug the complete address is
 * rendered with. Only {@link #readText} answers a text.
 *
 * <p>The lines of a completed write are written here, through {@link
 * PlatformLog}, and not in the kernel, which logs nothing (DEC-0051, C4): the
 * call, the task by its technical address, the state it entered and the scope
 * by its id. Never the complete address, whose scope name and selector are the
 * caller's words. The kernel's {@link TaskService.Written} hands the written
 * task back for that, and is absent where the call wrote nothing.
 */
@ApplicationScoped
@TenantBound
public class VerbSurface {

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

    /** A listing: one page of heads, and where the listing goes on. */
    public record Listing(String scope, Page page) {
    }

    // ======================================================================
    // The calls that are not transitions
    // ======================================================================

    @Transactional
    public Result create(Actor actor, String scope, String selector,
                         @Nullable Integer parentNumber,
                         TaskService.Draft draft, IdempotencyKey key) {
        UUID scopeId = resolve(actor, scope, Access.WRITE);
        TaskService.Written<TaskView> created =
            tasks.create(scopeId, selector, parentNumber, draft, actor, key);
        Task stored = created.stored();
        if (stored != null) {
            PlatformLog.TechnicalAddress entry = PlatformLog.TechnicalAddress.of(stored);
            PlatformLog.created(entry, PlatformLog.Status.of(TaskState.DRAFT));
            PlatformLog.createdInScope(entry, PlatformLog.ScopeId.of(scopeId));
        }
        return new Result(scope, created.answer());
    }

    @Transactional
    public Result update(Actor actor, Item at, TaskService.Draft changes,
                         @Nullable String conflictToken) {
        UUID scopeId = resolve(actor, at.scope(), Access.WRITE);
        return new Result(at.scope(), logged(
            tasks.update(scopeId, at.address(), changes, conflictToken, actor),
            PlatformLog::updated));
    }

    /** Deletes a draft and answers the complete address it had. */
    @Transactional
    public String delete(Actor actor, Item at, @Nullable String conflictToken) {
        UUID scopeId = resolve(actor, at.scope(), Access.WRITE);
        return logged(tasks.delete(scopeId, at.address(), conflictToken, actor),
            PlatformLog::deleted).complete(at.scope());
    }

    /**
     * The answer of a write, after its INFO line (C13.3): the task by its
     * technical address, for a write that wrote something.
     */
    private static <T> T logged(TaskService.Written<T> written,
                                Consumer<PlatformLog.TechnicalAddress> line) {
        Task stored = written.stored();
        if (stored != null) {
            line.accept(PlatformLog.TechnicalAddress.of(stored));
        }
        return written.answer();
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
                         PageRequest page) {
        UUID scopeId = resolve(actor, scope, Access.READ);
        Page answered = tasks.query(scopeId, selector, filter, page, actor);
        PlatformLog.queried(PlatformLog.ScopeId.of(scopeId),
            PlatformLog.Count.of(answered.tasks()));
        return new Listing(scope, answered);
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
    public Result annotate(Actor actor, Item at, TextType part, String text, VerbName call,
                           ArgumentPath partPath, IdempotencyKey key) {
        UUID scopeId = resolve(actor, at.scope(), Access.WRITE);
        TaskView head = tasks.read(scopeId, at.address(), actor);
        boolean written = head.texts().stream()
            .anyMatch(x -> x.type() == part && x.addendumSuffix() == null);
        if (!written && head.state() != TaskState.DRAFT) {
            throw new SurfaceException(
                Refusal.argumentInvalid(call, partPath, Form.ADDENDUM_TARGET));
        }
        return new Result(at.scope(), logged(
            tasks.annotate(scopeId, at.address(), part, text, actor, key),
            PlatformLog::annotated));
    }

    /**
     * Records what a closed task was curated into.
     *
     * <p>The target's scope is resolved for reading only: the relation is
     * written on the task in its own scope and nothing in the target's, so a
     * target in a scope the caller may read and not write is admissible.
     */
    @Transactional
    public Result relate(Actor actor, Item at, Item target, @Nullable String conflictToken) {
        UUID scopeId = resolve(actor, at.scope(), Access.WRITE);
        UUID targetScopeId = resolve(actor, target.scope(), Access.READ);
        return new Result(at.scope(), logged(tasks.relate(scopeId, at.address(), targetScopeId,
            target.address(), conflictToken, actor), PlatformLog::related));
    }

    @Transactional
    public Result unrelate(Actor actor, Item at, @Nullable String conflictToken) {
        UUID scopeId = resolve(actor, at.scope(), Access.WRITE);
        return new Result(at.scope(), logged(
            tasks.unrelate(scopeId, at.address(), conflictToken, actor),
            PlatformLog::unrelated));
    }

    /**
     * Attaches a tag to a task. The scope is resolved for writing, which is
     * the whole of the right to it: the part the caller takes in the task is
     * not asked.
     */
    @Transactional
    public Result tagAttach(Actor actor, Item at, String tag) {
        UUID scopeId = resolve(actor, at.scope(), Access.WRITE);
        return new Result(at.scope(), logged(tasks.tagAttach(scopeId, at.address(), tag, actor),
            PlatformLog::tagAttached));
    }

    /** Detaches a tag from a task, under the scope's write right as above. */
    @Transactional
    public Result tagDetach(Actor actor, Item at, String tag) {
        UUID scopeId = resolve(actor, at.scope(), Access.WRITE);
        return new Result(at.scope(), logged(tasks.tagDetach(scopeId, at.address(), tag, actor),
            PlatformLog::tagDetached));
    }

    // ======================================================================
    // The transitions
    // ======================================================================

    /** One transition other than a take-up, as its row of the table decides it. */
    @Transactional
    public Result act(Item at, TaskVerb verb, TaskCall call) {
        UUID scopeId = resolve(call.caller(), at.scope(), Access.WRITE);
        TaskService.Written<TaskView> after = tasks.act(scopeId, at.address(), verb, call);
        for (Task child : after.withdrawn()) {
            PlatformLog.childWithdrawn(PlatformLog.TechnicalAddress.of(child),
                PlatformLog.Status.of(TaskState.CLOSED));
        }
        Task stored = after.stored();
        if (stored != null) {
            PlatformLog.TechnicalAddress entry = PlatformLog.TechnicalAddress.of(stored);
            VerbName named = callOf(verb);
            PlatformLog.transitioned(named, entry, PlatformLog.Status.of(verb.target()));
            PlatformLog.transitionedInScope(named, entry, PlatformLog.ScopeId.of(scopeId));
        }
        return new Result(at.scope(), after.answer());
    }

    @Transactional
    public Claimed claim(Item at, TaskCall call, IdempotencyKey key) {
        UUID scopeId = resolve(call.caller(), at.scope(), Access.WRITE);
        TaskClaim claimed = taken(TaskVerb.CLAIM,
            tasks.claim(scopeId, at.address(), call, key));
        return new Claimed(new Result(at.scope(), claimed.task()), claimed.receipt());
    }

    @Transactional
    public Claimed claimNext(String scope, String selector, List<String> patterns,
                             TaskCall call, IdempotencyKey key) {
        UUID scopeId = resolve(call.caller(), scope, Access.WRITE);
        TaskService.Written<TaskClaim> drawn =
            tasks.claimNext(scopeId, selector, patterns, call, key);
        TaskClaim claimed = taken(TaskVerb.CLAIM_NEXT, drawn);
        Task stored = drawn.stored();
        if (stored != null) {
            PlatformLog.drawnInScope(PlatformLog.TechnicalAddress.of(stored),
                PlatformLog.ScopeId.of(scopeId));
        }
        return new Claimed(new Result(scope, claimed.task()), claimed.receipt());
    }

    /**
     * The answer of a take-up, after its INFO line: the task it took up and
     * the state it entered, or, for a repeat under the key, the receipt it
     * reissued.
     */
    private static TaskClaim taken(TaskVerb verb, TaskService.Written<TaskClaim> written) {
        Task stored = written.stored();
        if (stored != null) {
            PlatformLog.TechnicalAddress entry = PlatformLog.TechnicalAddress.of(stored);
            if (written.reissued()) {
                PlatformLog.receiptReissued(callOf(verb), entry);
            } else {
                PlatformLog.transitioned(callOf(verb), entry,
                    PlatformLog.Status.of(TaskState.ACTIVE));
            }
        }
        return written.answer();
    }

    /**
     * The call a transition row is declared as, by the name TAR-0004 gives
     * it: the REST name, which is the row's own.
     */
    private static VerbName callOf(TaskVerb verb) {
        return VerbName.of(ProcessVerb.of(verb), Surface.REST);
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
