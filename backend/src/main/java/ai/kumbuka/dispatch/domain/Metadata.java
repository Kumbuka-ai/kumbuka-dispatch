package ai.kumbuka.dispatch.domain;

import java.net.URI;
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
 */
public final class Metadata {

    /**
     * Longer than this is prose, not a pointer.
     *
     * <p>The bound is a judgement and is written down as one. Real addresses
     * and identifiers are far shorter; a value approaching it is a sentence
     * somebody put in the wrong place, and refusing it points them at the body
     * while they still remember what they meant.
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
     */
    public static void validate(Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return;
        }
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();

            if (value == null) {
                continue;
            }
            if (value.length() > MAX_VALUE_LENGTH) {
                throw new DispatchException(DispatchException.Reason.METADATA_REFUSED,
                    "metadata '" + key + "' is " + value.length() + " characters. Metadata "
                        + "carry an address or an identifier; anything this long is prose, "
                        + "and prose belongs in the body where the freeze protects it.");
            }
            if (carriesCredentials(value)) {
                throw new DispatchException(DispatchException.Reason.METADATA_REFUSED,
                    "metadata '" + key + "' is a URL carrying credentials. This service "
                        + "holds pointers and never follows them, so a credential stored "
                        + "here can only ever be read by somebody — never used.");
            }
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
