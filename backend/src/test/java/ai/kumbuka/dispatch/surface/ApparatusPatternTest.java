package ai.kumbuka.dispatch.surface;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pattern rule of the draw's apparatus filter, checked where it is
 * enforced.
 *
 * <p>A plain unit test, and that is the point of checking it here as well as
 * over HTTP: the rule is a property of the input shape and needs no database,
 * no scope and no exchange to be wrong. The HTTP probes assert that the refusal
 * reaches a caller with its code and that nothing was claimed; this one asserts
 * which strings the rule admits, which is the part that would otherwise only be
 * readable from a regular expression.
 *
 * <h2>Why the excluded characters are named one by one</h2>
 *
 * {@code _} and {@code %} are the two wildcards of the comparison the draw
 * runs, and the whole licence for passing a pattern into that comparison
 * unescaped is that neither can be in one. A test over "some invalid
 * characters" would leave that licence resting on the regular expression being
 * read correctly. These two cases are the backing for it.
 */
class ApparatusPatternTest {

    private static final String ADMITTED = "ADMITTED";
    private static final String MALFORMED = "MALFORMED";
    private static final String UNBOUNDED = "UNBOUNDED";

    // =======================================================================
    // What the rule admits
    // =======================================================================

    @ParameterizedTest
    @ValueSource(strings = {
        "code", "agent-code", "agent-review", "Agent-Code", "x-code-y",
        "agent-*", "*-code", "*-code-*", "a*b*c", "v2", "code+review", "A1", "*a"})
    void a_well_formed_pattern_is_admitted_unchanged(String pattern) {
        assertThat(admitted(pattern))
            .as("the pattern language is letters, digits, '+', '-' and '*'. A value the "
                + "rule admits travels to the comparison as it was written: the "
                + "translation of '*' happens in the persistence layer, and a pattern "
                + "rewritten here would be refused or admitted on one spelling and "
                + "compared on another")
            .containsExactly(pattern);
    }

    @Test
    void several_patterns_are_admitted_together_and_in_order() {
        assertThat(admitted("agent-review", "agent-code"))
            .as("the order the caller wrote them in is kept. It decides nothing about the "
                + "draw — the address space does that — but a filter that reordered its "
                + "own alternatives would make a refusal name a pattern the caller did "
                + "not send")
            .containsExactly("agent-review", "agent-code");
    }

    // =======================================================================
    // The absent argument
    // =======================================================================

    @Test
    void a_draw_with_no_apparatus_argument_is_refused() {
        assertThat(verdict(null))
            .as("there is no default. A draw with no pattern takes the next task of "
                + "any apparatus, which is the blind draw this argument exists to close")
            .isEqualTo("ARGUMENT_MISSING");
    }

    @Test
    void an_empty_apparatus_list_is_refused_the_same_way_as_an_absent_one() {
        assertThat(verdict(List.of()))
            .as("an empty list is the absent argument written out. Admitting it would "
                + "leave the required argument satisfiable by a value that narrows "
                + "nothing, which is the one call the requirement exists to refuse")
            .isEqualTo("ARGUMENT_MISSING");
    }

    // =======================================================================
    // The character rule
    // =======================================================================

    /**
     * The two characters the comparison reads as wildcards.
     *
     * <p>Their own case, separate from the malformed set below, because these
     * two are the reason the rule exists at all: a pattern carrying one would
     * be passed into the comparison unescaped and would match more than it
     * says, and {@code agent_code} in particular reads exactly like a
     * well-formed apparatus name.
     */
    @ParameterizedTest
    @ValueSource(strings = {"agent_code", "agent-%", "%", "_", "a_b", "100%-code"})
    void a_pattern_carrying_a_comparison_wildcard_is_refused(String pattern) {
        assertThat(verdict(List.of(pattern)))
            .as("'_' and '%%' are the comparison's own wildcards. The draw passes a "
                + "pattern into that comparison without escaping it, and this refusal is "
                + "the whole reason that is admissible: %s", pattern)
            .isEqualTo(MALFORMED);
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "agent code", "agent.code", "agent/code", "agent:code", "agent?code",
        "agent[code]", "", " ", "agent\\code", "'agent'"})
    void a_pattern_outside_the_character_rule_is_refused(String pattern) {
        assertThat(verdict(List.of(pattern))).isEqualTo(MALFORMED);
    }

    @Test
    void a_null_entry_in_the_list_is_refused_as_malformed_and_not_as_absent() {
        assertThat(verdict(Arrays.asList("agent-code", null)))
            .as("the list arrived and carries a pattern; one entry of it is not a "
                + "pattern. Answering MISSING would send the caller to supply an "
                + "argument it already sent")
            .isEqualTo("ARGUMENT_INVALID");
    }

    // =======================================================================
    // The pattern that is well formed and says everything
    // =======================================================================

    @ParameterizedTest
    @ValueSource(strings = {"*", "**", "***"})
    void a_pattern_of_nothing_but_wildcards_is_refused_as_unbounded(String pattern) {
        assertThat(verdict(List.of(pattern)))
            .as("'%s' is spelled correctly and matches everything, which is the blind "
                + "draw in one character. Its own verdict and not MALFORMED: a caller told "
                + "to correct a character would go looking for a typo it does not have",
                pattern)
            .isEqualTo(UNBOUNDED);
    }

    @Test
    void one_unbounded_pattern_among_bounded_ones_refuses_the_whole_draw() {
        assertThat(verdict(List.of("agent-code", "*")))
            .as("the patterns are alternatives, so one that matches everything makes the "
                + "whole filter match everything. Admitting the call because another "
                + "pattern was narrow would be the blind draw with a witness beside it")
            .isEqualTo(UNBOUNDED);
    }

    // =======================================================================
    // The published rule and the enforced rule are one string
    // =======================================================================

    @Test
    void the_declared_character_rule_is_the_one_the_check_applies() {
        assertThat("agent-*".matches(ApparatusPatterns.CHARACTER_RULE))
            .as("the constant is what the input schema publishes. A schema advertising a "
                + "rule the service does not apply is worse than none: a caller that "
                + "obeys it is still refused")
            .isTrue();
        assertThat("agent_code".matches(ApparatusPatterns.CHARACTER_RULE)).isFalse();
    }

    /** The patterns, where the whole chain admits them; the verdict otherwise. */
    private static List<String> admitted(String... patterns) {
        String verdict = verdict(List.of(patterns));
        return ADMITTED.equals(verdict) ? List.of(patterns) : List.of(verdict);
    }

    /**
     * What the surface says to a draw with these patterns: the argument's
     * presence and shape are the declaration's ({@link CallArguments}), the
     * character rule and the unbounded pattern are {@link ApparatusPatterns}'s,
     * in that order.
     */
    private static String verdict(List<String> patterns) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("scope", "probe");
        arguments.put("selector", "sprint");
        if (patterns != null) {
            arguments.put("apparatus", patterns);
        }
        try {
            List<String> given = new CallArguments(ProcessVerb.CLAIM_NEXT,
                "dispatch_claim_next", arguments).list("apparatus", Argument.Placement.TOP);
            if (ApparatusPatterns.firstMalformed(given) != null) {
                return MALFORMED;
            }
            return ApparatusPatterns.firstUnbounded(given) != null ? UNBOUNDED : ADMITTED;
        } catch (Refused refused) {
            return refused.code().name();
        }
    }
}
