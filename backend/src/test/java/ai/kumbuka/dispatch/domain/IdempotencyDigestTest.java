package ai.kumbuka.dispatch.domain;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The digest that tells "the same call again" from "a different call under a
 * key that was already spent".
 *
 * <p>One bit of information, and the whole idempotency rule turns on it: a
 * digest that collided would answer a caller's second, different commission
 * with the first one's address and discard what it wrote. So the properties
 * below are the properties of a comparison and not of a hash — the hash is
 * incidental, the canonical form is the part that has to be right.
 *
 * <p>The unit half of the pair. {@code IdempotentCallsIT} exercises the same
 * rule against a running service and a real ledger; this one exercises the
 * cases a service-level probe would have to construct twenty calls to reach.
 */
class IdempotencyDigestTest {

    @Test
    void the_same_values_digest_the_same() {
        assertThat(IdempotencyService.digestOf(List.of("sprint", "a title", "a text")))
            .isEqualTo(IdempotencyService.digestOf(List.of("sprint", "a title", "a text")));
    }

    @Test
    void a_changed_value_changes_the_digest() {
        assertThat(IdempotencyService.digestOf(List.of("sprint", "a title", "a text")))
            .isNotEqualTo(
                IdempotencyService.digestOf(List.of("sprint", "a title", "another text")));
    }

    /**
     * Values cannot slide between fields.
     *
     * <p>A separator-free join would digest {@code ["ab", "c"]} and
     * {@code ["a", "bc"]} identically, so a commission whose title ended where
     * another's text began would be read as a repeat of it. The separator is a
     * character no value can contain, which is what makes the boundary real
     * rather than unlikely.
     */
    @Test
    void two_values_cannot_slide_into_one_another() {
        assertThat(IdempotencyService.digestOf(List.of("ab", "c")))
            .isNotEqualTo(IdempotencyService.digestOf(List.of("a", "bc")));
    }

    /**
     * An absent value and an empty one are different calls.
     *
     * <p>A commission with no parent and one with an empty parent are not the
     * same call, and a digest that skipped nulls would read the second as a
     * repeat of the first.
     */
    @Test
    void an_absent_value_is_not_an_empty_one() {
        assertThat(IdempotencyService.digestOf(Arrays.asList("sprint", null, "a text")))
            .isNotEqualTo(IdempotencyService.digestOf(List.of("sprint", "", "a text")));
    }

    /** Order is part of the call: two values swapped are two different calls. */
    @Test
    void the_order_of_the_values_is_part_of_the_call() {
        assertThat(IdempotencyService.digestOf(List.of("a title", "a text")))
            .isNotEqualTo(IdempotencyService.digestOf(List.of("a text", "a title")));
    }

    /** Nothing readable travels: the digest carries no value it was built from. */
    @Test
    void the_digest_carries_nothing_readable() {
        String digest = IdempotencyService.digestOf(
            List.of("a title nobody else should read", "and its text"));

        assertThat(digest)
            .as("a digest in a second table is a second place the ops boundary would "
                + "have to withhold a commission's text from")
            .doesNotContain("title")
            .doesNotContain("text")
            .matches("[0-9a-f]{64}");
    }
}
