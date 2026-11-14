/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.payload;

import ai.kumbuka.dispatch.domain.Actor;
import ai.kumbuka.dispatch.domain.IdempotencyKey;
import ai.kumbuka.dispatch.domain.PageRequest;
import ai.kumbuka.dispatch.domain.TaskCall;
import ai.kumbuka.dispatch.domain.TaskFilter;
import ai.kumbuka.dispatch.domain.TaskService;
import ai.kumbuka.dispatch.domain.TaskTextView;
import ai.kumbuka.dispatch.domain.TaskVerb;
import ai.kumbuka.dispatch.domain.TaskView;
import ai.kumbuka.dispatch.domain.TextPart;
import ai.kumbuka.dispatch.domain.TextType;
import ai.kumbuka.dispatch.surface.NextList;
import ai.kumbuka.dispatch.surface.ProcessVerb;
import ai.kumbuka.dispatch.surface.Surface;
import ai.kumbuka.dispatch.surface.VerbSurface;
import org.jspecify.annotations.Nullable;

import java.time.Clock;
import java.util.List;
import java.util.function.Function;

/**
 * One checked call, made: each of the twenty-seven becomes exactly one call of
 * {@link VerbSurface}, which resolves the scope, runs the check order and
 * opens the transaction (C1.1).
 *
 * <p>The part of the adapter both surfaces share after the form check, in two
 * steps. {@link #prepare} reads the arguments {@link CallPayload} has admitted
 * into what the verb surface takes, and refuses by name what it cannot read;
 * {@link Prepared#make} makes the call. Between the two the adapter checks the
 * one permission the call names ({@link DeclaredCalls}, {@link #through}), so
 * that a call refused for its permission has been read as completely as one
 * that is made, and a fault of its form answers before the permission (C1.3).
 * It decides nothing — no refusal is caught here, none is raised but by the
 * form readers of the payload — and it composes no two calls into one.
 *
 * <p>What comes back is one of four outcomes, which the adapter puts into its
 * protocol: one task, a page of them, a part of a text, or the address a
 * deleted draft had.
 */
public final class CallBinding {

    private CallBinding() {
    }

    /** What a call answered. */
    public sealed interface Outcome permits Answered, Listed, TextRead, Deleted {
    }

    /**
     * One task, as a call on it answers it.
     *
     * @param head    whether the answer is the full head ({@code read}, {@code
     *                query}) or the lean one (every writing call)
     * @param waitingFor what the task waits for; absent where a call is open to the caller
     * @param receipt the receipt of a claim; absent on the answer to every other call
     */
    public record Answered(String address, TaskView view, boolean head,
                           List<NextList.Step> next, @Nullable String waitingFor,
                           @Nullable String receipt)
        implements Outcome {
    }

    /**
     * One page of full heads, and the cursor of the next page.
     *
     * @param nextCursor the cursor of the next page; absent on the last
     */
    public record Listed(List<Answered> heads, @Nullable String nextCursor) implements Outcome {
    }

    /** One part of a task's text. */
    public record TextRead(String address, TaskTextView text) implements Outcome {
    }

    /** A deleted draft: the address it had, and nothing else. */
    public record Deleted(String address) implements Outcome {
    }

    /**
     * A scope a call names, and whether the call writes in it.
     *
     * @param writes whether the call writes in the scope or only reads it
     */
    private record Named(String scope, boolean writes) {
    }

    /**
     * One call, read and not yet made.
     *
     * <p>It holds the call and the scopes it names, so that a call refused
     * for its permission can still be answered after its scopes, as the check
     * order wants it ({@link #resolveScopes}).
     */
    public static final class Prepared {

        private final ProcessVerb verb;
        private final Actor actor;
        private final List<Named> scopes;
        private final Function<VerbSurface, Outcome> call;

        private Prepared(ProcessVerb verb, Actor actor, List<Named> scopes,
                         Function<VerbSurface, Outcome> call) {
            this.verb = verb;
            this.actor = actor;
            this.scopes = List.copyOf(scopes);
            this.call = call;
        }

        public ProcessVerb verb() {
            return verb;
        }

        /** Makes the call: one call of the verb surface. */
        public Outcome make(VerbSurface verbs) {
            return call.apply(verbs);
        }

        /**
         * Resolves the scopes the call names, with the access the call takes
         * in each, and makes no call; refuses as resolving them refuses. For a
         * call the caller's permission does not reach, which is answered after
         * its scopes like every other refusal (C1.3).
         */
        public void resolveScopes(VerbSurface verbs) {
            for (Named named : scopes) {
                verbs.resolveScope(actor, named.scope(), named.writes());
            }
        }
    }

    /**
     * Reads one call.
     *
     * @param surface the surface the call arrived on, which names the calls in
     *                {@code next}
     * @param actor   who calls
     * @param in      the call's arguments, checked
     * @param clock   the service's clock, which a lease and {@code waiting_for}
     *                are read against
     */
    public static Prepared prepare(Surface surface, Actor actor, CallPayload in, Clock clock) {
        ProcessVerb verb = in.verb();
        boolean writes = !verb.reads();
        return switch (verb) {
            case CREATE -> {
                String scope = in.scope();
                String selector = in.selector();
                @Nullable Integer parent = in.parentNumber(scope, selector).orElse(null);
                TaskService.Draft draft = in.draft();
                IdempotencyKey key = IdempotencyKey.of(in.idempotencyKey().orElse(null));
                yield new Prepared(verb, actor, List.of(new Named(scope, true)),
                    verbs -> answered(surface, actor, verb,
                        verbs.create(actor, scope, selector, parent, draft, key), false, null,
                        clock));
            }
            case QUERY -> {
                String scope = in.scope();
                String selector = in.selector();
                TaskFilter filter = in.filter(scope, selector);
                PageRequest page = in.page();
                yield new Prepared(verb, actor, List.of(new Named(scope, false)),
                    verbs -> query(verbs.query(actor, scope, selector, filter, page), surface,
                        actor, clock));
            }
            default -> onATask(surface, actor, in, clock, in.address(), writes);
        };
    }

    /** Reads a call on one task. */
    private static Prepared onATask(Surface surface, Actor actor, CallPayload in, Clock clock,
                                    VerbSurface.Item at, boolean writes) {
        ProcessVerb verb = in.verb();
        List<Named> scopes = List.of(new Named(at.scope(), writes));
        return switch (verb) {
            case UPDATE -> {
                TaskService.Draft changes = in.changes();
                @Nullable String token = in.conflictToken().orElse(null);
                yield new Prepared(verb, actor, scopes, verbs -> answered(surface, actor, verb,
                    verbs.update(actor, at, changes, token), false, null, clock));
            }
            case DELETE -> {
                @Nullable String token = in.conflictToken().orElse(null);
                yield new Prepared(verb, actor, scopes,
                    verbs -> new Deleted(verbs.delete(actor, at, token)));
            }
            case READ -> new Prepared(verb, actor, scopes, verbs -> answered(surface, actor, verb,
                verbs.read(actor, at), true, null, clock));
            case READ_TEXT -> {
                TextPart part = TextPart.valueOf(in.textPart());
                yield new Prepared(verb, actor, scopes,
                    verbs -> new TextRead(at.complete(), verbs.readText(actor, at, part)));
            }
            case ANNOTATE -> {
                TextType part = TextType.fromWireName(in.addendumPart());
                String text = in.text();
                IdempotencyKey key = IdempotencyKey.of(in.idempotencyKey().orElse(null));
                yield new Prepared(verb, actor, scopes, verbs -> answered(surface, actor, verb,
                    verbs.annotate(actor, at, part, text, in.call(), in.addendumPartPath(), key),
                    false, null, clock));
            }
            case RELATE -> {
                VerbSurface.Item target = in.curatedIn();
                @Nullable String token = in.conflictToken().orElse(null);
                yield new Prepared(verb, actor,
                    List.of(new Named(at.scope(), true), new Named(target.scope(), false)),
                    verbs -> answered(surface, actor, verb,
                        verbs.relate(actor, at, target, token), false, null, clock));
            }
            case UNRELATE -> {
                @Nullable String token = in.conflictToken().orElse(null);
                yield new Prepared(verb, actor, scopes, verbs -> answered(surface, actor, verb,
                    verbs.unrelate(actor, at, token), false, null, clock));
            }
            case TAG_ATTACH -> {
                String tag = in.tag();
                yield new Prepared(verb, actor, scopes, verbs -> answered(surface, actor, verb,
                    verbs.tagAttach(actor, at, tag), false, null, clock));
            }
            case TAG_DETACH -> {
                String tag = in.tag();
                yield new Prepared(verb, actor, scopes, verbs -> answered(surface, actor, verb,
                    verbs.tagDetach(actor, at, tag), false, null, clock));
            }
            case CLAIM -> {
                TaskCall call = in.transition(actor, TaskVerb.CLAIM, clock);
                IdempotencyKey key = IdempotencyKey.of(in.idempotencyKey().orElse(null));
                yield new Prepared(verb, actor, scopes, verbs -> {
                    VerbSurface.Claimed claimed = verbs.claim(at, call, key);
                    return answered(surface, actor, verb, claimed.result(), false,
                        claimed.receipt(), clock);
                });
            }
            default -> {
                TaskVerb transition = verb.transition().orElseThrow(() ->
                    new IllegalStateException(verb.call() + " is no call on one task"));
                TaskCall call = in.transition(actor, transition, clock);
                yield new Prepared(verb, actor, scopes, verbs -> answered(surface, actor, verb,
                    verbs.act(at, transition, call), false, null, clock));
            }
        };
    }

    /**
     * Makes a prepared call through the method of {@code calls} named for it,
     * which checks the one permission the call names before it makes it.
     */
    public static Outcome through(DeclaredCalls calls, Prepared call) {
        return switch (call.verb()) {
            case SEND -> calls.send(call);
            case RECALL -> calls.recall(call);
            case CLAIM -> calls.claim(call);
            case RELEASE -> calls.release(call);
            case DEFER -> calls.defer(call);
            case RENEW -> calls.renew(call);
            case ASK -> calls.ask(call);
            case ANSWER -> calls.answer(call);
            case HOLD -> calls.hold(call);
            case RESUME -> calls.resume(call);
            case DELIVER -> calls.deliver(call);
            case REWORK -> calls.rework(call);
            case ACCEPT -> calls.accept(call);
            case REJECT -> calls.reject(call);
            case FAIL -> calls.fail(call);
            case WITHDRAW -> calls.withdraw(call);
            case CREATE -> calls.create(call);
            case UPDATE -> calls.update(call);
            case DELETE -> calls.delete(call);
            case READ -> calls.read(call);
            case READ_TEXT -> calls.readText(call);
            case QUERY -> calls.query(call);
            case ANNOTATE -> calls.annotate(call);
            case RELATE -> calls.relate(call);
            case UNRELATE -> calls.unrelate(call);
            case TAG_ATTACH -> calls.tagAttach(call);
            case TAG_DETACH -> calls.tagDetach(call);
        };
    }

    private static Outcome query(VerbSurface.Listing listing, Surface surface, Actor actor,
                                 Clock clock) {
        List<Answered> heads = listing.page().tasks().stream()
            .map(view -> answered(surface, actor, ProcessVerb.QUERY,
                new VerbSurface.Result(listing.scope(), view), true, null, clock))
            .toList();
        return new Listed(heads, listing.page().nextCursor().orElse(null));
    }

    private static Answered answered(Surface surface, Actor actor, ProcessVerb call,
                                     VerbSurface.Result result, boolean head,
                                     @Nullable String receipt,
                                     Clock clock) {
        boolean afterClaim = call == ProcessVerb.CLAIM;
        List<NextList.Step> next = NextList.of(surface, result.view(), actor, afterClaim);
        return new Answered(result.address(), result.view(), head, next,
            NextList.waitingFor(result.view(), next, clock.instant()).orElse(null), receipt);
    }
}
