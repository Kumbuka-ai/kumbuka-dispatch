package ai.kumbuka.dispatch.surface;

import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * What the verb surface accepts, in a form that names no protocol.
 *
 * <p>These shapes exist because the surface must not import the adapter's wire
 * types. It did, until this was written, and the adapter payloads imported the
 * surface's refusal in return — an import cycle between a layer and the layer
 * it is supposed to be independent of. The cycle was not a lapse of care: the
 * surface was carved out of the adapter package and kept the adapter's types
 * on the way out.
 *
 * <p>They are deliberately NOT the wire shapes renamed. A wire shape answers
 * to a published contract and changes when that contract does; these answer to
 * the verbs. Today most fields coincide, and that is fine — what matters is
 * that a change to one is not automatically a change to the other.
 *
 * <h2>Why the null check is not here</h2>
 *
 * A missing body is refused by the surface, after the scope has been resolved,
 * because the ratified check order answers "not found" for a scope the caller
 * may not see before it says anything about the body. So an adapter hands over
 * {@code null} rather than refusing early, and every record here is nullable at
 * the call site by design.
 */
public final class VerbInput {

    private VerbInput() {
    }

    /**
     * What brings an exchange into being.
     *
     * <p>No number and no actor. Numbers are allocated inside the transaction
     * that inserts the row and authorship is derived from the token; a field
     * for either would be a field the server has to ignore.
     *
     * <p>Metadata values are {@code Object} because the domain accepts either a
     * single identifier or a list of them. The type is not open; the domain's
     * validator refuses anything else. The adapter must not coerce a value
     * across shapes here — flattening a list to a string would carry a real
     * list across the boundary as prose.
     */
    public record Draft(
        String title,
        String apparatus,
        LocalDate date,
        Map<String, Object> metadata) {
    }

    /**
     * What commissions work: the text included.
     *
     * <p>Deliberately NOT {@link Draft} with a field added. A draft is what
     * {@code create} takes, and {@code create} takes no text on purpose — the
     * initial-draft moment belongs to the author. A commission is the opposite
     * act: it is create, write and freeze together, so the text is not
     * optional here and there is no moment at which the exchange exists
     * without it.
     */
    public record Commission(
        String title,
        String apparatus,
        String text,
        LocalDate date,
        Map<String, Object> metadata) {
    }

    /** What attaches an addendum to an exchange that has already been frozen. */
    public record Addendum(
        String title,
        String apparatus,
        LocalDate date) {
    }

    /**
     * What {@code update} carries — for the dispatch role before send, and for
     * the return role after it. One shape for both, because the caller does
     * not choose which role is written: the state chooses.
     *
     * <p><strong>Before {@code send}:</strong> {@code draft} lands in {@code
     * body}, {@code metadata} in {@code dispatch_metadata}, and {@code title},
     * {@code apparatus} and {@code date} override the same-named fields.
     * A null argument leaves its field alone, so the caller can change one
     * property at a time. {@code receipt} is ignored — a draft has no holder.
     *
     * <p><strong>After {@code send}:</strong> {@code draft} lands in
     * {@code return_body} and {@code metadata} in {@code return_metadata},
     * exactly as the earlier return-only shape did. {@code title},
     * {@code apparatus} and {@code date} are refused when they arrive — a
     * frozen field is frozen. {@code receipt} is required of an executor.
     *
     * <p>What has not changed: {@code create} still takes no body argument. A
     * body on {@code create} would take the initial-draft moment away from the
     * author.
     */
    public record Update(
        String title,
        String apparatus,
        LocalDate date,
        String draft,
        String receipt,
        Map<String, Object> metadata) {
    }

    /**
     * How long a claim should stand, still in its text form.
     *
     * <p>The text is parsed here and not at the adapter, so that a malformed
     * duration is refused in the same position in the check order as every
     * other body fault. Parsing it one layer up would move that refusal in
     * front of the scope resolution, and the caller would learn that a scope
     * they may not see exists by the shape of the error they got.
     */
    public record Claim(String duration) {

        /** @throws SurfaceException when the value is absent or not a duration */
        public Duration parsed() {
            return parseDuration(duration);
        }
    }

    /**
     * What a draw takes: how long the claim should stand, and which apparatus
     * the draw is for.
     *
     * <p><strong>Its own shape and not {@link Claim} with a field added.</strong>
     * {@code claim} names one exchange and the apparatus is already decided by
     * the address it names; a draw names a set and has to say which part of that
     * set it is drawing from. One record for both would carry, on the call that
     * addresses a single exchange, an argument that call cannot mean — and an
     * argument a call cannot mean is one a caller will eventually send.
     *
     * <p>The patterns are validated here rather than at either adapter, so the
     * rule is enforced once for both surfaces and in the position the ratified
     * check order gives a body fault: after the scope has been resolved. An
     * adapter that checked them would answer before that resolution, and the
     * caller would learn from the shape of its refusal that a scope it may not
     * see exists.
     */
    public record ClaimNext(String duration, List<String> apparatus) {

        /**
         * The character rule an apparatus pattern obeys, as a regular
         * expression, declared here because this is where it is enforced.
         *
         * <p>The published input schema carries the same expression, so a
         * conforming client can refuse a pattern before sending it. The
         * hyphen sits last in the character class deliberately: written
         * {@code [A-Za-z0-9+-*]} it would be the range from {@code +} to
         * {@code *}, which is empty and would reject every pattern.
         *
         * <p>The underscore is excluded, and so is the percent sign — they are
         * the two wildcards of the comparison the draw runs, and a pattern
         * carrying one would match more than it says while reading as though
         * it named one apparatus. That exclusion is what lets the draw pass
         * the pattern into its comparison without escaping it.
         */
        public static final String CHARACTER_RULE = "^[A-Za-z0-9+*-]+$";

        private static final Pattern WELL_FORMED = Pattern.compile(CHARACTER_RULE);

        /** A pattern that is nothing but wildcards, which narrows nothing. */
        private static final Pattern ONLY_WILDCARDS = Pattern.compile("^\\*+$");

        /** @throws SurfaceException when the value is absent or not a duration */
        public Duration parsedDuration() {
            return parseDuration(duration);
        }

        /**
         * The patterns this draw is narrowed to, checked.
         *
         * <p>Returned rather than stored, so that a record built by an adapter
         * carries exactly what arrived and nothing is checked twice. The list
         * is copied on the way out: the draw reaches the database with it, and
         * a caller-owned list that changed under the query would be a filter
         * that is not the one that was refused or admitted.
         *
         * @throws SurfaceException when no pattern arrived, when one is spelled
         *         outside {@link #CHARACTER_RULE}, or when one is nothing but
         *         wildcards
         */
        public List<String> patterns() {
            if (apparatus == null || apparatus.isEmpty()) {
                throw new SurfaceException(
                    SurfaceException.Reason.APPARATUS_PATTERN_MISSING,
                    "a draw names which apparatus it draws for, as a list of one or more "
                        + "patterns such as ['agent-code'] or ['agent-*']. There is no "
                        + "default: a draw with no pattern takes the next exchange of any "
                        + "apparatus, which is how a controller polling for its own work "
                        + "claims a commission addressed to somebody else.");
            }
            for (String pattern : apparatus) {
                refuseMalformed(pattern);
                refuseUnbounded(pattern);
            }
            return List.copyOf(apparatus);
        }

        private static void refuseMalformed(String pattern) {
            if (pattern != null && WELL_FORMED.matcher(pattern).matches()) {
                return;
            }
            throw SurfaceException.about(
                SurfaceException.Reason.APPARATUS_PATTERN_MALFORMED, pattern,
                "'" + pattern + "' is not an apparatus pattern. A pattern is one or more "
                    + "of A-Z, a-z, 0-9, '+', '-' and '*', and '*' is the only wildcard: "
                    + "it stands for any run of characters, at any position. The "
                    + "underscore and the percent sign are excluded because they carry a "
                    + "meaning of their own in the comparison the draw runs.");
        }

        private static void refuseUnbounded(String pattern) {
            if (!ONLY_WILDCARDS.matcher(pattern).matches()) {
                return;
            }
            throw SurfaceException.about(
                SurfaceException.Reason.APPARATUS_PATTERN_UNBOUNDED, pattern,
                "'" + pattern + "' matches every apparatus there is, which is the blind "
                    + "draw this argument exists to refuse. Name the apparatus, or a part "
                    + "of it such as 'agent-*'.");
        }
    }

    /**
     * The lease length of a claim, parsed.
     *
     * <p>Shared by {@link Claim} and {@link ClaimNext} because it is one rule:
     * the two calls take the same argument and a second copy of the parse is a
     * second place the refusal wording and the accepted forms can drift.
     *
     * <p>Parsed at the surface and not at the adapter, so that a malformed
     * duration is refused in the same position in the check order as every
     * other body fault.
     *
     * @throws SurfaceException when the value is absent or not a duration
     */
    private static Duration parseDuration(String duration) {
        if (duration == null || duration.isBlank()) {
            throw new SurfaceException(
                SurfaceException.Reason.CLAIM_DURATION_MALFORMED,
                "a claim names how long it stands, as an ISO-8601 duration such as "
                    + "'PT1H'. There is no default: a lease length is a policy, and one "
                    + "invented here would be a policy nobody ratified.");
        }
        try {
            return Duration.parse(duration);
        } catch (java.time.format.DateTimeParseException e) {
            throw new SurfaceException(
                SurfaceException.Reason.CLAIM_DURATION_MALFORMED,
                "'" + duration + "' is not an ISO-8601 duration. 'PT1H', 'PT30M', 'P1D'.");
        }
    }
}
