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
 * <p>The dispatch role is carried in {@link #body()}. A console identity reads
 * it because operators read commissions as a matter of course; an executing
 * apparatus reads it only for an exchange it effectively holds — the first of
 * three bolts against the race: a loser cannot have started work, because it
 * never had anything to start from.
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
 * <p><strong>Note on the body's schema shape.</strong> {@code body} is a
 * {@code NOT NULL DEFAULT ''} column in the database; a draft that was never
 * written carries the empty string, not a null. So a caller reads "no body
 * yet" as {@code ""} on this projection, not as an absent key. That is a
 * finding of this repair, not something this class undoes: changing the
 * schema is out of scope, and the two callers who need the distinction
 * ({@code body} pre-send vs. an answer arriving late) can read it from the
 * status.
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
    String effectiveHolder,
    Instant claimExpiresAt,
    String body,
    String returnBody,
    Map<String, Object> returnMetadata,
    String conflictToken) {

    /**
     * The view for a caller, carrying each role only if the caller may have it.
     *
     * @param actor decides whether each role is included at all
     * @param now   the moment the claim is judged against
     */
    static ExchangeView of(Exchange e, Actor actor, Instant now) {
        return new ExchangeView(
            e.address(),
            e.selector,
            e.number,
            e.sub,
            e.title,
            e.apparatus,
            e.dispatchDate,
            e.status(),
            e.effectiveHolder(now),
            e.claimEffective(now) ? e.claimExpiresAt() : null,
            bodyFor(e, actor, now),
            returnBodyFor(e, actor, now),
            returnMetadataFor(e, actor, now),
            e.conflictToken());
    }

    /**
     * The body, or nothing.
     *
     * <p>A console identity reads it because operators read commissions as a
     * matter of course. An executing apparatus reads it only for an exchange
     * it effectively holds — which is what "enough to refuse, not enough to
     * work" means in practice: the title, the selector, the apparatus and the
     * date are enough to decide whether to take something up, and the body is
     * what taking it up buys.
     */
    private static String bodyFor(Exchange e, Actor actor, Instant now) {
        if (actor.isConsole()) {
            return e.body;
        }
        boolean holdsIt = e.claimEffective(now)
            && actor.subject().equals(e.effectiveHolder(now));
        return holdsIt ? e.body : null;
    }

    /**
     * The return text, or nothing.
     *
     * <p>Symmetric to {@link #bodyFor}. A console identity reads what somebody
     * answered because that is what a console is for. An executing apparatus
     * reads it only for an exchange it effectively holds — its own answer,
     * not a stranger's. That is the conservative rule the dispatch names as
     * default when the visibility question is not otherwise settled: closing
     * off cross-executor reads is the smaller and reversible variant.
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
