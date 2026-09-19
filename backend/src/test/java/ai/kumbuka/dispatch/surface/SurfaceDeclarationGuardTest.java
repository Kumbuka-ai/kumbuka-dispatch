package ai.kumbuka.dispatch.surface;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The start-up guard, observed refusing.
 *
 * <p>Section 4.4's last sentence — "a reason not in this table cannot be
 * returned: the service refuses to start with an undeclared reason in its
 * catalogue" — is a statement about a mechanism, and a mechanism nobody has
 * watched fail is a description of one. So each check the guard makes is run
 * against a declaration that breaks it, and the refusal is read.
 *
 * <p>The fourth check is here because it was NOT here. The javadoc of {@code
 * requireServable} said three checks ran and two did: "a call with no
 * arguments cannot be schema'd" was named and never implemented, which is a
 * comment asserting a guarantee no mechanism enforced. It is implemented now,
 * and this file is what makes that sentence checkable rather than hopeful.
 */
class SurfaceDeclarationGuardTest {

    /** The real declaration comes up. Without this the rest proves nothing. */
    @Test
    void the_declaration_this_service_carries_is_servable() {
        SurfaceDeclaration.requireServable();
    }

    /**
     * A reason with no entry in the catalogue stops the service.
     *
     * <p>Run against a catalogue handed in, because the real one is a constant
     * that cannot be made incomplete at runtime — and a check that could only
     * ever see the complete one could never be observed refusing.
     */
    @Test
    void a_reason_with_no_declared_pattern_refuses_the_start() {
        Map<RefusalCode, ReasonCatalogue.Reason> incomplete =
            new LinkedHashMap<>(ReasonCatalogue.byCode());
        incomplete.remove(RefusalCode.NOT_THE_HOLDER);

        assertThatThrownBy(() -> SurfaceDeclaration.requireServable(incomplete))
            .as("RED STATE, observed: a catalogue missing one reason refuses the start, "
                + "and names the reason it is missing")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("NOT_THE_HOLDER");
    }

    /** A declared reason with a blank pattern is refused the same way. */
    @Test
    void a_reason_with_a_blank_pattern_refuses_the_start() {
        Map<RefusalCode, ReasonCatalogue.Reason> blanked =
            new LinkedHashMap<>(ReasonCatalogue.byCode());
        blanked.put(RefusalCode.NOT_FOUND,
            new ReasonCatalogue.Reason(RefusalCode.NOT_FOUND, "  ", "check scope"));

        assertThatThrownBy(() -> SurfaceDeclaration.requireServable(blanked))
            .as("RED STATE, observed: a reason declared with a blank pattern refuses the "
                + "start — a refusal nobody could word is not a declared refusal")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("without a pattern");
    }

    /**
     * A call with no arguments is refused — the check the javadoc named and
     * the code did not make.
     *
     * <p>Its input schema would be an empty closed object, and the check that
     * names an undeclared argument would have no declaration to name instead.
     */
    @Test
    void a_call_with_no_arguments_refuses_the_start() {
        assertThatThrownBy(() ->
            SurfaceDeclaration.requireDescribedArguments("dispatch_nothing", List.of()))
            .as("RED STATE, observed: the check the javadoc named and the code did not "
                + "make now refuses a call with no arguments")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no arguments");
    }

    /**
     * An argument with no description is refused, because the description is
     * what a refusal says the value IS.
     *
     * <p>{@code ARGUMENT_MISSING} renders "{@code <call> needs <name>: <what>}"
     * and the {@code what} is this text. Without it the refusal stops exactly
     * where the caller needed it to start.
     */
    @Test
    void an_argument_with_no_description_refuses_the_start() {
        assertThatThrownBy(() -> SurfaceDeclaration.requireDescribedArguments(
            "dispatch_something", List.of(Argument.top("address", "string", true, "  "))))
            .as("RED STATE, observed: an argument with no description refuses the start, "
                + "and names the argument")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("address");
    }

    /**
     * Every declared argument of every call is described, which is what makes
     * the check above more than a possibility.
     */
    @Test
    void every_call_this_service_carries_describes_every_argument() {
        for (ProcessVerb verb : ProcessVerb.values()) {
            assertThat(verb.arguments())
                .as("%s declares arguments at all", verb.call())
                .isNotEmpty();
            for (Argument argument : verb.arguments()) {
                assertThat(argument.description())
                    .as("%s's argument '%s' is what an ARGUMENT_MISSING refusal reads out",
                        verb.call(), argument.name())
                    .isNotBlank();
            }
        }
    }
}
