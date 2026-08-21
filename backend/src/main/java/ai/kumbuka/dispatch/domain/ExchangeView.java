package ai.kumbuka.dispatch.domain;

import java.time.Instant;
import java.time.LocalDate;

/**
 * What a caller sees of an exchange without holding it.
 *
 * <p><strong>There is no body field.</strong> Not an empty one, not a null one
 * — the record does not declare it. That is deliberate and it is the
 * difference between a permission and a convention: a field that is sometimes
 * populated invites a caller to read it and invites a later change to populate
 * it always, whereas a field that does not exist cannot be read by accident.
 *
 * <p>The body is carried in {@link #body()} only when the caller is a console
 * identity, through a separate factory. An executing apparatus never receives
 * it for an exchange it has not claimed, and that is the first of three bolts
 * against the race: a loser cannot have started work, because it never had
 * anything to start from.
 *
 * <p>The holder is the EFFECTIVE holder. A lapsed claim reports none, whatever
 * the row still says — expiry writes nothing, so the stored value outlives the
 * claim by design, and a surface reporting it would show a free exchange as
 * taken with no error anywhere to notice it by.
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
    String body) {

    /**
     * The view for a caller, carrying the body only if the caller may have it.
     *
     * @param actor decides whether the body is included at all
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
            bodyFor(e, actor, now));
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
}
