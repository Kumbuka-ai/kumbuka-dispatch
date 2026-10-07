package ai.kumbuka.dispatch.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * What an idempotency key compares: a digest of the call's arguments.
 *
 * <p>The rule a key carries has three outcomes, and {@link TaskService} keeps
 * it on the task's own ledger: no key, or one nobody spent in this scope in
 * {@link SpentTaskKey#REMEMBERED_FOR}, and the call runs; the same key on the
 * same call with the same arguments, and the call answers what the first one
 * produced; the same key on another call or other arguments, and {@code
 * IDEMPOTENCY_KEY_REUSED}. What is left here is the comparison those outcomes
 * turn on. The ledger of the exchange went with the exchange.
 */
public final class IdempotencyService {

    private IdempotencyService() {
    }

    /**
     * A digest over what the call writes, in a canonical form.
     *
     * <p>Order-bearing and separator-delimited: the values are joined with a
     * character that cannot occur in any of them, so {@code ["ab", "c"]} and
     * {@code ["a", "bc"]} cannot collide into one digest. A null is written as
     * an empty segment rather than skipped, because "absent" and "empty" are
     * different calls and a digest that could not tell them apart would treat
     * one as a repeat of the other.
     */
    public static String digestOf(List<String> values) {
        StringBuilder canonical = new StringBuilder();
        for (String value : values) {
            // An absent value and an empty one are different calls — a
            // commission with no parent is not one with an empty parent — so
            // the absent case gets a mark of its own instead of being written
            // as the empty string. The mark is a control character no value
            // can contain, which is what makes the distinction real rather
            // than merely unlikely.
            canonical.append(value == null ? "\u001E" : value).append('\u001F');
        }
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                sha256.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the platform", impossible);
        }
    }
}
