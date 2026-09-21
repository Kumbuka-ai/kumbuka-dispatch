package ai.kumbuka.dispatch.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

/**
 * What a caller sees of an exchange without holding it.
 *
 * <p><strong>The role fields are not always present.</strong> Not empty, not
 * null — the record declares them, and the factory either fills them or
 * leaves them null so {@code @JsonInclude(NON_NULL)} withholds the key
 * entirely. That is the difference between a permission and a convention: a
 * field that is sometimes populated invites a caller to read it and invites a
 * later change to populate it always, whereas a field that is absent from the
 * wire cannot be read by accident.
 *
 * <p>The dispatch role is carried in {@link #dispatchBody()} and
 * {@link #dispatchMetadata()}. A console identity reads them because operators
 * read commissions as a matter of course; an executing apparatus reads them
 * only for an exchange it effectively holds — the first of three bolts
 * against the race: a loser cannot have started work, because it never had
 * anything to start from.
 *
 * <p>The return role is carried in {@link #returnBody()} and
 * {@link #returnMetadata()}. The visibility rule is the mirror of the
 * dispatch role's — the conservative one that the dispatch specifies: a
 * console reads what somebody wrote, and an executor reads only its own,
 * i.e. only for an exchange it effectively holds. A finding, not a decision:
 * whether an executor may read another apparatus's answer text is not
 * settled, and settling it here would be a policy invented in the projection.
 *
 * <p>The holder is the EFFECTIVE holder. A lapsed claim reports none, whatever
 * the row still says — expiry writes nothing, so the stored value outlives the
 * claim by design, and a surface reporting it would show a free exchange as
 * taken with no error anywhere to notice it by.
 *
 * <p>The {@link #conflictToken} travels here because the token is a
 * per-exchange state marker rather than a transport artefact, so the two
 * expositions cannot be its two homes: MCP has no header the way REST has
 * {@code ETag}, so a token carried only there is a token half of the callers
 * cannot see. Absent for an addendum, which takes no field write and has
 * nothing for one to protect.
 *
 * <p><strong>Note on dispatchBody's schema shape.</strong> {@code dispatch_body}
 * is a {@code NOT NULL DEFAULT ''} column in the database; a draft that was
 * never written carries the empty string, not a null. So a caller reads "no
 * body yet" as {@code ""} on this projection, not as an absent key. That is a
 * finding of this repair, not something this class undoes: changing the
 * schema is out of scope, and the two callers who need the distinction can
 * read it from the status.
 */
public record ExchangeView(
    String address,
    String selector,
    int number,
    int sub,
    String title,
    String apparatus,
    LocalDate dispatchDate,
    ExchangeStatus status,
    HolderState effectiveHolder,
    Instant claimExpiresAt,
    String dispatchBody,
    Map<String, Object> dispatchMetadata,
    String returnBody,
    Map<String, Object> returnMetadata,
    String conflictToken,
    String curatedInto,
    String commissionerMessage,
    String executorQuestion,
    String terminationReason,
    boolean answerDelivered,
    boolean bracketRoot,
    boolean frozen,
    boolean childrenFinished,
    String effectiveHolderSubject) {

    /**
     * The view for a caller, carrying each role only if the caller may have it.
     *
     * <p><strong>The address is complete.</strong> It is built from the scope
     * slug the caller used, here, so that no projection anywhere can answer a
     * short one — the short form does not exist in this record at all, and a
     * caller of this factory that has no slug cannot construct a view.
     *
     * <p>The four booleans at the end are not fields of the exchange; they are
     * the preconditions {@code next} reads. They travel on the view rather
     * than being recomputed at the surface because the surface would then hold
     * a second reading of the same row — and the one that would be wrong is
     * the one used by whichever adapter is written next.
     *
     * @param actor            decides whether each role is included at all
     * @param now              the moment the claim is judged against
     * @param scopeSlug        the scope as the caller names it, for the address
     * @param curatedInto      the complete address of the curation target,
     *                         already resolved, or null
     * @param childrenFinished whether every exchange of the bracket is
     *                         terminal. Supplied rather than derived here,
     *                         because it is a fact about the bracket and this
     *                         factory sees one row — a projection that could
     *                         query would be a second service. True at a
     *                         child, where section 6 asks nothing about it.
     */
    static ExchangeView of(Exchange e, Actor actor, Instant now, String scopeSlug,
                           String curatedInto, boolean childrenFinished) {
        return new ExchangeView(
            new ExchangeAddress(e.selectorName(), e.number, e.sub, e.addendumSuffix)
                .complete(scopeSlug),
            e.selectorName(),
            e.number,
            e.sub,
            e.title,
            e.apparatus,
            e.dispatchDate,
            e.status(),
            HolderState.of(e.effectiveHolder(now), actor),
            e.claimEffective(now) ? e.claimExpiresAt() : null,
            dispatchBodyFor(e, actor, now),
            dispatchMetadataFor(e, actor, now),
            returnBodyFor(e, actor, now),
            returnMetadataFor(e, actor, now),
            e.conflictToken(),
            curatedInto,
            holdsExchange(e, actor, now) ? e.commissionerMessage() : null,
            holdsExchange(e, actor, now) ? e.executorQuestion() : null,
            e.terminationReason(),
            e.answerDelivered(),
            e.isBracketRoot(),
            e.frozen(),
            childrenFinished,
            e.effectiveHolder(now));
    }

    /**
     * Whether an executor's question is outstanding.
     *
     * <p>Read off the stored question rather than off the absence of an
     * answer, because both can be present: an exchange whose answer was sent
     * back for rework and whose executor then asked something carries a return
     * body AND a question. Section 6 puts the answer first, and so does
     * {@code next}.
     */
    public boolean questionAsked() {
        return executorQuestion != null && !executorQuestion.isBlank();
    }

    /**
     * The dispatch body, or nothing.
     *
     * <p>A console identity reads it because operators read commissions as a
     * matter of course. An executing apparatus reads it only for an exchange
     * it effectively holds — which is what "enough to refuse, not enough to
     * work" means in practice: the title, the selector, the apparatus and the
     * date are enough to decide whether to take something up, and the
     * dispatch body is what taking it up buys.
     */
    private static String dispatchBodyFor(Exchange e, Actor actor, Instant now) {
        if (actor.isConsole()) {
            return e.dispatchBody;
        }
        boolean holdsIt = e.claimEffective(now)
            && actor.subject().equals(e.effectiveHolder(now));
        return holdsIt ? e.dispatchBody : null;
    }

    /**
     * The dispatch metadata, or nothing. Same visibility rule as
     * {@link #dispatchBodyFor}: console reads always, executor only when it
     * holds the exchange.
     *
     * <p>Historically the projection did not carry dispatch metadata, so a
     * caller could not read it. That was a matter of what the record declared,
     * not of what the row held. Adding it here is the symmetric completion of
     * the two-roles-one-row model: what each role carries, the projection
     * exposes under the same visibility rule.
     */
    private static Map<String, Object> dispatchMetadataFor(Exchange e, Actor actor,
                                                           Instant now) {
        return holdsExchange(e, actor, now) ? e.dispatchMetadata : null;
    }

    /**
     * The return text, or nothing.
     *
     * <p>Symmetric to {@link #dispatchBodyFor}. A console identity reads what
     * somebody answered because that is what a console is for. An executing
     * apparatus reads it only for an exchange it effectively holds — its own
     * answer, not a stranger's. That is the conservative rule the dispatch
     * names as default when the visibility question is not otherwise settled:
     * closing off cross-executor reads is the smaller and reversible variant.
     */
    private static String returnBodyFor(Exchange e, Actor actor, Instant now) {
        return holdsExchange(e, actor, now) ? e.returnBody() : null;
    }

    private static Map<String, Object> returnMetadataFor(Exchange e, Actor actor,
                                                           Instant now) {
        return holdsExchange(e, actor, now) ? e.returnMetadata() : null;
    }

    private static boolean holdsExchange(Exchange e, Actor actor, Instant now) {
        if (actor.isConsole()) {
            return true;
        }
        return e.claimEffective(now)
            && actor.subject().equals(e.effectiveHolder(now));
    }
}
