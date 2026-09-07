package ai.kumbuka.dispatch.domain;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * The rule that keeps the caller's free field from becoming a junk drawer.
 *
 * <blockquote>Metadata carry an address or an identifier, never an assertion.
 * </blockquote>
 *
 * <p>A pull-request URL is an address. A case number is an identifier. "The
 * review found structural problems" is an assertion, and assertions are
 * exactly what the freeze protects — so they belong in the body, where the
 * gate can hold them.
 *
 * <p>A machine cannot tell an assertion from an identifier in general, and
 * this does not pretend to. What it refuses is the two shapes that are
 * unambiguously wrong: a credential riding in a URL, and a value long enough
 * that it is prose rather than a pointer. The rule itself is carried by
 * review; these are the parts of it that can be enforced.
 *
 * <h2>Cardinality, not typefreedom</h2>
 *
 * <p>A value is either a single identifier or a list of them — exactly one
 * level deep, and nothing else. An exchange references several tracks and
 * several tasks; that is more of the same shape and it is admissible under
 * the same rule. A JSON tree of arbitrary depth would be a different rule
 * entirely, and it is refused with a typed error rather than flattened or
 * quietly stored. Everything a list value carries goes through the same
 * length and credential checks as a single value — the list widens what is
 * allowed to arrive, not what is allowed to sit there.
 */
public final class Metadata {

    /**
     * Longer than this is prose, not a pointer.
     *
     * <p>The bound is a judgement and is written down as one. Real addresses
     * and identifiers are far shorter; a value approaching it is a sentence
     * somebody put in the wrong place, and refusing it points them at the body
     * while they still remember what they meant.
     *
     * <p>The bound applies <strong>per element</strong>. A list of ten short
     * identifiers is not one long value; the concatenation would be, and
     * measuring it that way would refuse a list on grounds that have nothing
     * to do with any of the identifiers it holds.
     */
    static final int MAX_VALUE_LENGTH = 512;

    private Metadata() {
    }

    /**
     * Refuses metadata that cannot be a pointer.
     *
     * <p>A stored URL is <strong>never fetched</strong> anywhere in this
     * service — it is held and its target is unknown. That is precisely why a
     * credential inside one is refused rather than tolerated: nothing here
     * would ever use it, so its only possible future is to be read by a human
     * or shipped somewhere in a copy of the row.
     *
     * <p>A value is a {@code String} or a {@code List} of them. Anything else
     * — a number, a boolean, a nested object, a list of lists, a list with a
     * null in it — is refused here. Coercion would be an aperture in the
     * doctrine that is worse than a blanket ban, because the value would look
     * accepted and then read back as something the caller did not send.
     */
    public static void validate(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : metadata.entrySet()) {
            checkValue(entry.getKey(), entry.getValue());
        }
    }

    private static void checkValue(String key, Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof String s) {
            checkElement(key, s);
            return;
        }
        if (value instanceof List<?> list) {
            checkList(key, list);
            return;
        }
        throw new DispatchException(DispatchException.Reason.METADATA_REFUSED,
            "metadata '" + key + "' is a " + value.getClass().getSimpleName()
                + ". A value is a String or a list of Strings; metadata carry an address "
                + "or an identifier, and a nested object, number or boolean is neither.");
    }

    private static void checkList(String key, List<?> list) {
        int index = 0;
        for (Object element : list) {
            if (element == null) {
                throw new DispatchException(DispatchException.Reason.METADATA_REFUSED,
                    "metadata '" + key + "'[" + index + "] is null. A list of identifiers "
                        + "does not carry gaps; a missing entry is one fewer entry.");
            }
            if (!(element instanceof String s)) {
                throw new DispatchException(DispatchException.Reason.METADATA_REFUSED,
                    "metadata '" + key + "'[" + index + "] is a "
                        + element.getClass().getSimpleName()
                        + ". A list value carries Strings; a list of lists or of numbers "
                        + "would be a second level of structure, and metadata are one "
                        + "level deep.");
            }
            checkElement(key + "[" + index + "]", s);
            index++;
        }
    }

    private static void checkElement(String label, String value) {
        if (value.length() > MAX_VALUE_LENGTH) {
            throw new DispatchException(DispatchException.Reason.METADATA_REFUSED,
                "metadata '" + label + "' is " + value.length() + " characters. Metadata "
                    + "carry an address or an identifier; anything this long is prose, "
                    + "and prose belongs in the body where the freeze protects it.");
        }
        if (carriesCredentials(value)) {
            throw new DispatchException(DispatchException.Reason.METADATA_REFUSED,
                "metadata '" + label + "' is a URL carrying credentials. This service "
                    + "holds pointers and never follows them, so a credential stored "
                    + "here can only ever be read by somebody — never used.");
        }
    }

    /** Userinfo in a URL: the {@code user:password@host} form. */
    private static boolean carriesCredentials(String value) {
        try {
            URI uri = URI.create(value);
            return uri.getUserInfo() != null && !uri.getUserInfo().isBlank();
        } catch (IllegalArgumentException notAUri) {
            // Not a URI at all, so not a URI carrying credentials. Whether it
            // is a sensible identifier is a question for review, not for this.
            return false;
        }
    }
}
