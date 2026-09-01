package ai.kumbuka.dispatch.api;

import ai.kumbuka.dispatch.domain.ExchangeAddress;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the path parts of {@code {scope}/{selector}/{id}} into an address, and
 * refuses everything that is not one.
 *
 * <h2>Validation precedes parsing</h2>
 *
 * The production is applied to the raw string before anything is taken apart.
 * That order is the whole design: a parser that splits first and validates the
 * pieces afterwards has already decided what the pieces are, and its refusal
 * then describes the split rather than the input. Here a form error is a typed
 * rejection with nothing resolved, and a well-formed address of a thing that is
 * not there is a not-found from the store. <strong>The two classes never
 * merge.</strong> They are produced in different places by different code, and
 * this class is structurally unable to produce the second one — it never looks
 * anything up.
 *
 * <h2>What is tolerated and what is not</h2>
 *
 * What does not change identity is tolerated; what changes it is rejected. A
 * trailing slash changes nothing about which object is addressed, because the
 * occupied parts decide that, so it is tolerated on the way in and never
 * generated on the way out. Upper case is rejected and <strong>never
 * folded</strong>: folding would make two distinct strings resolve to one
 * identity, and an identity statement must not arise from leniency.
 *
 * <h2>Why the scope is a DNS label</h2>
 *
 * The address is a URI with the scope in the authority position, so its
 * grammar is the authority's and not a free slug. Checking it here rather than
 * at the directory keeps a malformed scope in stage 1, where the answer leaks
 * nothing — the directory's refusal necessarily reveals that a lookup happened.
 */
public final class AddressParser {

    /**
     * The scope, as a DNS label: lower case, digits and inner hyphens, at most
     * sixty-three characters.
     */
    private static final Pattern SCOPE = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");

    /**
     * The selector, as it is declared. Lower case, digits and inner hyphens —
     * the same shape as a scope, minus the length ceiling a DNS label carries.
     */
    private static final Pattern SELECTOR = Pattern.compile("[a-z0-9]([a-z0-9-]*[a-z0-9])?");

    /**
     * The id: {@code <number>.<sub>}, optionally one lower-case letter.
     *
     * <p>No leading zeroes on either part, and that is form rather than taste:
     * {@code 07.1} and {@code 7.1} would be two strings for one exchange, which
     * is the same identity-by-leniency the case rule refuses.
     */
    private static final Pattern ID = Pattern.compile("(0|[1-9]\\d*)\\.(0|[1-9]\\d*)([a-z])?");

    private AddressParser() {
    }

    /**
     * The scope name, checked against the authority production.
     *
     * @return the same string, unchanged. Nothing is normalised here: a value
     *         this method altered would be a second identity for the one that
     *         arrived.
     */
    public static String scope(String raw) {
        String candidate = requirePresent(raw, "scope");
        if (!SCOPE.matcher(candidate).matches()) {
            throw malformed("the scope '" + candidate + "' is not a DNS label. Lower case, "
                + "digits and inner hyphens, at most 63 characters. Upper case is rejected "
                + "rather than folded: folding would make two strings resolve to one scope.");
        }
        return candidate;
    }

    /** The selector name, checked against the production and not resolved. */
    public static String selector(String raw) {
        String candidate = requirePresent(raw, "selector");
        if (!SELECTOR.matcher(candidate).matches()) {
            throw malformed("the selector '" + candidate + "' is not a declared name's shape. "
                + "Lower case, digits and inner hyphens. Whether it is declared at all is a "
                + "different question, answered further in and against a scope.");
        }
        return candidate;
    }

    /**
     * The item part of a complete address.
     *
     * <p>Takes the selector as well because an address is four parts and a
     * three-part one has no meaning here — the reservation on a truncated
     * address is what makes {@code scope/selector} a collection rather than
     * "all the objects of this scope".
     */
    public static ExchangeAddress item(String rawSelector, String rawId) {
        String selector = selector(rawSelector);
        String candidate = requirePresent(rawId, "id");

        Matcher m = ID.matcher(candidate);
        if (!m.matches()) {
            throw malformed("the id '" + candidate + "' is not an exchange address. The form "
                + "is <number>.<sub>, with one optional lower-case letter for an addendum — "
                + "'149.2' or '149.0a'. A regular sub-number in place of the letter would "
                + "make an addendum an ordinary child of the bracket.");
        }

        return new ExchangeAddress(
            selector,
            Integer.parseInt(m.group(1)),
            Integer.parseInt(m.group(2)),
            m.group(3));
    }

    /**
     * Renders an address back into the id part of a path.
     *
     * <p>The canonical form is generated here and what arrived is never passed
     * through, so a tolerated trailing slash does not survive into a
     * {@code Location} header.
     */
    public static String render(ExchangeAddress address) {
        return address.number() + "." + address.sub()
            + (address.suffix() == null ? "" : address.suffix());
    }

    /**
     * The scheme this service answers for, in leading position of an address.
     *
     * <p>Present in the MCP form and absent from the REST path, and that is
     * not an inconsistency: the scheme is the routing decision. Inbound over
     * MCP there is no request line for an address to travel in, so it arrives
     * complete in the body and the scheme comes with it; inbound over REST the
     * path is what routed, and a scheme repeated there would be a second place
     * for the routing to be decided.
     */
    public static final String SCHEME = "dispatch";

    /**
     * The three occupied parts of a complete address, as they arrive over MCP.
     *
     * @param scope    the scope name, in the authority position
     * @param selector the declared bracket name
     * @param id       the id part, still raw
     */
    public record Parts(String scope, String selector, String id) {
    }

    /**
     * Splits {@code dispatch://scope/selector/id} into its parts, and refuses
     * everything that is not one.
     *
     * <p>An address is a URI with the scheme leading and the scope as a DNS
     * label in the authority position. It is split here by the production
     * rather than by a URI library, for the same reason validation precedes
     * parsing everywhere else: a library normalises, and a normalisation is an
     * identity statement made by somebody who was not asked.
     *
     * <p>A trailing slash is tolerated because it changes nothing about which
     * object is addressed — the occupied parts decide that. Upper case is not
     * folded.
     */
    public static Parts uri(String raw) {
        String candidate = requirePresent(raw, "address");
        String prefix = SCHEME + "://";

        if (!candidate.startsWith(prefix)) {
            throw malformed("the address '" + candidate + "' does not name the dispatch "
                + "scheme. A complete address is '" + prefix + "<scope>/<selector>/<id>', "
                + "with the scheme leading and the scope in the authority position.");
        }

        String rest = candidate.substring(prefix.length());
        if (rest.endsWith("/")) {
            rest = rest.substring(0, rest.length() - 1);
        }

        String[] parts = rest.split("/", -1);
        if (parts.length != 3) {
            throw malformed("the address '" + candidate + "' occupies " + parts.length
                + " part(s) after the scheme, and a complete address occupies three: scope, "
                + "selector and id. Truncation is recognised by which parts are occupied, "
                + "and a verb acting on an existing object takes a complete address.");
        }

        // Each part is validated by its own production, and the whole address
        // is rejected if any of them fails. Validating here rather than at the
        // call site is what keeps the MCP and the REST entrance on one grammar.
        scope(parts[0]);
        item(parts[1], parts[2]);
        return new Parts(parts[0], parts[1], parts[2]);
    }

    private static String requirePresent(String raw, String part) {
        if (raw == null || raw.isBlank()) {
            throw malformed("the " + part + " part of the address is empty. An address is "
                + "recognised by which of its parts are occupied, so an empty one is not a "
                + "shorter address but a broken one.");
        }
        return raw;
    }

    private static SurfaceException malformed(String message) {
        return new SurfaceException(SurfaceException.Reason.ADDRESS_MALFORMED, message);
    }
}
