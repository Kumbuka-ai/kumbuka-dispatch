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
import ai.kumbuka.dispatch.domain.TaskFilter;
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

/**
 * One checked call, made: each of the twenty-six becomes exactly one call of
 * {@link VerbSurface}, which resolves the scope, runs the check order and
 * opens the transaction (C1.1).
 *
 * <p>The part of the adapter both surfaces share after the form check: the
 * arguments {@link CallPayload} has admitted, read into what the verb surface
 * takes. It decides nothing — no refusal is caught here, none is raised but by
 * the form readers of the payload — and it composes no two calls into one.
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
     * Makes one call.
     *
     * @param surface the surface the call arrived on, which names the calls in
     *                {@code next}
     * @param actor   who calls
     * @param in      the call's arguments, checked
     * @param clock   the service's clock, which a lease and {@code waiting_for}
     *                are read against
     */
    public static Outcome make(VerbSurface verbs, Surface surface, Actor actor, CallPayload in,
                               Clock clock) {
        ProcessVerb verb = in.verb();
        return switch (verb) {
            case CREATE -> {
                String scope = in.scope();
                String selector = in.selector();
                @Nullable Integer parent = in.parentNumber(scope, selector).orElse(null);
                yield answered(surface, actor, verb, verbs.create(actor, scope, selector, parent,
                    in.draft(), IdempotencyKey.of(in.idempotencyKey().orElse(null))), false, null, clock);
            }
            case UPDATE -> answered(surface, actor, verb, verbs.update(actor, in.address(),
                in.changes(), in.conflictToken().orElse(null)), false, null, clock);
            case DELETE -> new Deleted(verbs.delete(actor, in.address(), in.conflictToken().orElse(null)));
            case READ -> answered(surface, actor, verb, verbs.read(actor, in.address()), true,
                null, clock);
            case READ_TEXT -> {
                VerbSurface.Item at = in.address();
                yield new TextRead(at.complete(),
                    verbs.readText(actor, at, TextPart.valueOf(in.textPart())));
            }
            case QUERY -> query(verbs, surface, actor, in, clock);
            case ANNOTATE -> answered(surface, actor, verb, verbs.annotate(actor, in.address(),
                TextType.fromWireName(in.addendumPart()), in.text(), in.call(),
                in.addendumPartPath(), IdempotencyKey.of(in.idempotencyKey().orElse(null))), false, null,
                clock);
            case RELATE -> answered(surface, actor, verb, verbs.relate(actor, in.address(),
                in.curatedIn(), in.conflictToken().orElse(null)), false, null, clock);
            case UNRELATE -> answered(surface, actor, verb, verbs.unrelate(actor, in.address(),
                in.conflictToken().orElse(null)), false, null, clock);
            case CLAIM -> {
                VerbSurface.Item at = in.address();
                VerbSurface.Claimed claimed = verbs.claim(at,
                    in.transition(actor, verb.transition().orElseThrow(), clock),
                    IdempotencyKey.of(in.idempotencyKey().orElse(null)));
                yield answered(surface, actor, verb, claimed.result(), false, claimed.receipt(),
                    clock);
            }
            case CLAIM_NEXT -> {
                List<String> patterns = in.apparatusPatterns();
                String scope = in.scope();
                String selector = in.selector();
                VerbSurface.Claimed claimed = verbs.claimNext(scope, selector, patterns,
                    in.transition(actor, verb.transition().orElseThrow(), clock),
                    IdempotencyKey.of(in.idempotencyKey().orElse(null)));
                yield answered(surface, actor, verb, claimed.result(), false, claimed.receipt(),
                    clock);
            }
            default -> {
                VerbSurface.Item at = in.address();
                TaskVerb transition = verb.transition().orElseThrow();
                yield answered(surface, actor, verb, verbs.act(at, transition,
                    in.transition(actor, transition, clock)), false, null, clock);
            }
        };
    }

    private static Outcome query(VerbSurface verbs, Surface surface, Actor actor, CallPayload in,
                                 Clock clock) {
        String scope = in.scope();
        String selector = in.selector();
        TaskFilter filter = in.filter(scope, selector);
        PageRequest page = in.page();
        VerbSurface.Listing listing = verbs.query(actor, scope, selector, filter, page);
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
        boolean afterClaim = call == ProcessVerb.CLAIM || call == ProcessVerb.CLAIM_NEXT;
        List<NextList.Step> next = NextList.of(surface, result.view(), actor, afterClaim);
        return new Answered(result.address(), result.view(), head, next,
            NextList.waitingFor(result.view(), next, clock.instant()).orElse(null), receipt);
    }
}
