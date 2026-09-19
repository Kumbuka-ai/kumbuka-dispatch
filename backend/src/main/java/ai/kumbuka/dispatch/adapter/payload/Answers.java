package ai.kumbuka.dispatch.adapter.payload;

import ai.kumbuka.dispatch.surface.NextCalculator;
import ai.kumbuka.dispatch.surface.Surface;
import ai.kumbuka.dispatch.domain.ExchangeView;
import ai.kumbuka.dispatch.surface.VerbSurface;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The answer of section 3, built from a result and the surface that asked.
 *
 * <p>One builder for both expositions. What differs between them is the
 * vocabulary {@code next} speaks, and that is a parameter here rather than two
 * implementations — the shape of an answer is the contract's, and two builders
 * would be two places for it to be decided.
 *
 * <h2>Two projections of {@code fields}, and why the choice is not a flag</h2>
 *
 * A transition answers "the exchange is here now"; a read answers "here is the
 * exchange". The first does not need the bodies, and serialising them costs a
 * caller reading through a language model a large part of its context window
 * for no information — measured on 2026-09-07, a {@code send} followed by a
 * {@code close} handed back roughly 50 000 characters to say "status is now
 * closed".
 *
 * <p>So the projection is a property of the CALL, chosen at the call site, and
 * deliberately not a boolean the caller passes. A caller-settable flag gets set
 * the first time somebody wants a body out of a transition, which is exactly
 * what this exists to prevent.
 */
public final class Answers {

    private Answers() {
    }

    /** Every field a caller may see, bodies included. For the two readers. */
    public static Payloads.Answer full(VerbSurface.Result result, Surface surface) {
        return answer(result, surface, fields(result.exchange(), true));
    }

    /** The head fields only. For every transition and every listing entry. */
    public static Payloads.Answer compact(VerbSurface.Result result, Surface surface) {
        return answer(result, surface, fields(result.exchange(), false));
    }

    private static Payloads.Answer answer(VerbSurface.Result result, Surface surface,
                                          Map<String, Object> fields) {
        List<NextCalculator.Step> next = result.next(surface);
        return new Payloads.Answer(
            result.exchange().address(),
            fields,
            result.conflictToken(),
            next.stream().map(Answers::step).toList(),
            result.waitingFor(surface));
    }

    private static Map<String, String> step(NextCalculator.Step step) {
        Map<String, String> rendered = new LinkedHashMap<>();
        rendered.put("call", step.call());
        rendered.put("does", step.does());
        return Map.copyOf(rendered);
    }

    /**
     * The exchange as this caller may see it.
     *
     * <p>A map rather than a record, because {@code fields} is where the
     * exchange's own shape lives and that shape has withheld members: the
     * dispatch body is absent, not null, for an executor that has not claimed
     * it. A record with {@code NON_NULL} achieves the same thing and costs a
     * second class per projection; the map is built once, here, from the view
     * that already decided what is withheld.
     *
     * <p><strong>The address is NOT in here.</strong> It is the answer's own
     * top-level member. Carrying it in both places would be two spellings of
     * one fact, and the one that got updated would not be the one a caller
     * read.
     */
    private static Map<String, Object> fields(ExchangeView v, boolean withBodies) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("selector", v.selector());
        fields.put("number", v.number());
        fields.put("sub", v.sub());
        fields.put("title", v.title());
        fields.put("apparatus", v.apparatus());
        fields.put("dispatch_date", String.valueOf(v.dispatchDate()));
        fields.put("state", v.status().wireName());
        fields.put("effective_holder", v.effectiveHolder());

        putIfPresent(fields, "claim_expires_at",
            v.claimExpiresAt() == null ? null : v.claimExpiresAt().toString());
        putIfPresent(fields, "curated_into", v.curatedInto());
        putIfPresent(fields, "termination_reason", v.terminationReason());

        if (withBodies) {
            putIfPresent(fields, "dispatch_text", v.dispatchBody());
            putIfPresent(fields, "dispatch_metadata", v.dispatchMetadata());
            putIfPresent(fields, "return_text", v.returnBody());
            putIfPresent(fields, "return_metadata", v.returnMetadata());
            putIfPresent(fields, "commissioner_message", v.commissionerMessage());
            putIfPresent(fields, "executor_question", v.executorQuestion());
        }

        return Map.copyOf(fields);
    }

    /**
     * Absent rather than null.
     *
     * <p>The difference is the one {@link ExchangeView} is built around: a null
     * member is a field a caller reads and finds empty, and invites a later
     * change to populate it; an absent one cannot be read by accident. The
     * projection that decides WHICH are withheld is the view's; this only
     * declines to write a key for what it withheld.
     */
    private static void putIfPresent(Map<String, Object> fields, String name, Object value) {
        if (value != null) {
            fields.put(name, value);
        }
    }

    /** A listing, every entry carrying its own {@code next}. */
    public static Payloads.AnswerListing listing(List<VerbSurface.Result> results,
                                                 Surface surface) {
        List<Payloads.Answer> answers = new ArrayList<>();
        for (VerbSurface.Result result : results) {
            answers.add(compact(result, surface));
        }
        return new Payloads.AnswerListing(List.copyOf(answers));
    }
}
