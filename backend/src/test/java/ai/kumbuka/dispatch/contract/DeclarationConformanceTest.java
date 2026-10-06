package ai.kumbuka.dispatch.contract;

import ai.kumbuka.dispatch.adapter.mcp.McpTools;
import ai.kumbuka.dispatch.surface.Argument;
import ai.kumbuka.dispatch.surface.ProcessVerb;
import ai.kumbuka.dispatch.surface.ReasonCatalogue;
import ai.kumbuka.dispatch.surface.RefusalCode;
import ai.kumbuka.dispatch.surface.Surface;
import ai.kumbuka.dispatch.surface.SurfaceDeclaration;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The declaration against the target and the concept document.
 *
 * <p>Every expected value here is a fixed value written from the
 * specification ({@link TargetSurface}) or read from the contract copy
 * ({@link Contract}), never from the declaration it checks.
 *
 * <p>Red probes, observed: a twenty-sixth call declared, and a declared call
 * removed, each turn {@link #the_declaration_carries_the_twenty_five_calls_of_the_target}
 * red; a description over 500 characters stops the service at start and with
 * it the build, and turns {@link #every_description_is_within_its_budget} red.
 */
class DeclarationConformanceTest {

    // =======================================================================
    // Criterion 1: the calls of the target, nothing missing, nothing extra
    // =======================================================================

    @Test
    void the_declaration_carries_the_twenty_five_calls_of_the_target() {
        assertThat(ProcessVerb.names(Surface.REST))
            .as("TAR-0004 section 3: sixteen transitions and nine further calls, and no "
                + "other")
            .containsExactlyInAnyOrderElementsOf(TargetSurface.REST);
        assertThat(ProcessVerb.names(Surface.MCP))
            .as("on the assistant surface each is named dispatch_<verb>")
            .containsExactlyInAnyOrderElementsOf(TargetSurface.MCP);
        assertThat(McpTools.declared().stream().map(McpTools.Tool::name).toList())
            .as("and the tool list is exactly the declaration")
            .containsExactlyInAnyOrderElementsOf(TargetSurface.MCP);
    }

    @Test
    void the_sixteen_transitions_are_the_transitions_and_the_nine_are_not() {
        assertThat(Arrays.stream(ProcessVerb.values()).filter(ProcessVerb::isTransition)
                .map(ProcessVerb::verb).toList())
            .containsExactlyInAnyOrderElementsOf(TargetSurface.TRANSITIONS);
        assertThat(Arrays.stream(ProcessVerb.values()).filter(v -> !v.isTransition())
                .map(ProcessVerb::verb).toList())
            .containsExactlyInAnyOrderElementsOf(TargetSurface.OTHERS);
        for (ProcessVerb call : ProcessVerb.values()) {
            if (call.isTransition()) {
                assertThat(call.transition().wireName())
                    .as("a transition is bound to the row of the table of its own name")
                    .isEqualTo(call.verb());
            }
        }
    }

    // =======================================================================
    // Criterion 2: the arguments of the table in concept section 3.2
    // =======================================================================

    @Test
    void every_call_takes_exactly_the_arguments_of_the_concept_table() {
        for (ProcessVerb call : ProcessVerb.values()) {
            TargetSurface.Arguments expected = TargetSurface.ARGUMENTS.get(call.verb());
            assertThat(expected).as("the table has a row for %s", call.verb()).isNotNull();
            assertThat(call.topArguments().stream().map(Argument::name).toList())
                .as("%s: what names the target and what is transport stands at the top",
                    call.call())
                .containsExactlyInAnyOrderElementsOf(expected.top());
            assertThat(call.fieldArguments().stream().map(Argument::name).toList())
                .as("%s: everything written stands under fields", call.call())
                .containsExactlyInAnyOrderElementsOf(expected.fields());
        }
    }

    @Test
    void the_mandatory_arguments_are_the_ones_the_concept_names() {
        for (ProcessVerb call : ProcessVerb.values()) {
            Argument remark = call.argument("remark", Argument.Placement.FIELDS);
            if (remark != null) {
                assertThat(remark.required())
                    .as("remark is mandatory on fail, reject and rework and optional "
                        + "elsewhere: %s", call.call())
                    .isEqualTo(TargetSurface.REMARK_REQUIRED.contains(call.verb()));
            }
            Argument duration = call.argument("duration", Argument.Placement.TOP);
            if (duration != null) {
                assertThat(duration.required())
                    .as("a duration is optional everywhere: %s", call.call()).isFalse();
            }
            assertThat(call.argument("date", Argument.Placement.FIELDS))
                .as("create takes no date, and nothing else does: %s", call.call()).isNull();
        }
        for (String name : TargetSurface.CLAIM_NEXT_REQUIRED) {
            assertThat(ProcessVerb.CLAIM_NEXT.argument(name, Argument.Placement.TOP).required())
                .as("%s is mandatory on claim_next", name).isTrue();
        }
    }

    // =======================================================================
    // Descriptions
    // =======================================================================

    @Test
    void every_description_is_within_its_budget() {
        for (ProcessVerb call : ProcessVerb.values()) {
            assertThat(call.description().length())
                .as("%s: a description is at most 500 characters", call.call())
                .isLessThanOrEqualTo(500);
        }
        assertThat(SurfaceDeclaration.DESCRIPTION_BUDGET).isEqualTo(500);
    }

    @Test
    void a_description_over_the_budget_is_refused_by_the_start_up_guard() {
        assertThatThrownBy(() -> SurfaceDeclaration.requireWithinBudget("dispatch_probe",
                "x".repeat(501)))
            .as("RED STATE, observed: the guard the service runs at start refuses a "
                + "description of 501 characters, so a declaration that carried one would "
                + "not start and no test that boots it would pass")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("501");
        SurfaceDeclaration.requireWithinBudget("dispatch_probe", "x".repeat(500));
    }

    @Test
    void the_texts_of_concept_section_3_5_stand_word_for_word() {
        assertThat(ProcessVerb.CLAIM.description()).isEqualTo(TargetSurface.CLAIM_DESCRIPTION);
        assertThat(ProcessVerb.CLAIM_NEXT.description())
            .isEqualTo(TargetSurface.CLAIM_NEXT_DESCRIPTION);
        assertThat(ProcessVerb.CLAIM_NEXT.argument("apparatus", Argument.Placement.TOP)
                .description())
            .isEqualTo(TargetSurface.APPARATUS_ARGUMENT);
        assertThat(ProcessVerb.CLAIM.description())
            .as("the sentence about the text is the same in both descriptions")
            .contains(TargetSurface.THE_TEXT_SENTENCE);
        assertThat(ProcessVerb.CLAIM_NEXT.description())
            .contains(TargetSurface.THE_TEXT_SENTENCE);
    }

    @Test
    void every_description_is_the_contract_copy_s() {
        Map<String, String> described = Contract.describedCalls();
        assertThat(described.keySet())
            .as("the contract copy describes exactly the calls of the target")
            .containsExactlyInAnyOrderElementsOf(TargetSurface.MCP);
        for (ProcessVerb call : ProcessVerb.values()) {
            assertThat(Contract.collapsed(call.description()))
                .as("%s carries the description the contract copy writes", call.call())
                .isEqualTo(described.get(call.call()));
        }
        assertThat(Contract.collapsed(ProcessVerb.CLAIM_NEXT
                .argument("apparatus", Argument.Placement.TOP).description()))
            .isEqualTo(Contract.quotedUnder("### 3.3"));
    }

    // =======================================================================
    // The refusals
    // =======================================================================

    @Test
    void the_catalogue_is_the_contract_copy_s_table() {
        List<String> declared = Arrays.stream(RefusalCode.values()).map(Enum::name).toList();
        assertThat(declared)
            .as("every reason of the table is declared, and no other")
            .containsExactlyInAnyOrderElementsOf(Contract.declaredReasons());
        for (RefusalCode code : RefusalCode.values()) {
            assertThat(ReasonCatalogue.of(code).pattern())
                .as("the pattern of %s", code)
                .isEqualTo(Contract.patternOf(code.name()));
            assertThat(ReasonCatalogue.of(code).remedy())
                .as("the remedy of %s", code)
                .isEqualTo(Contract.remedyOf(code.name()));
        }
        assertThat(Contract.text())
            .as("and the terminal variant of the state refusal")
            .contains(ReasonCatalogue.TERMINAL_STATE_PATTERN);
    }

    @Test
    void the_declaration_is_servable() {
        SurfaceDeclaration.requireServable();
        assertThat(SurfaceDeclaration.calls()).hasSize(25);
    }
}
