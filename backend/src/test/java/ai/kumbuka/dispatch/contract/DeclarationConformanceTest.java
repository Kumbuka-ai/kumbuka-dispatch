package ai.kumbuka.dispatch.contract;

import ai.kumbuka.dispatch.adapter.mcp.McpTools;
import ai.kumbuka.dispatch.surface.ProcessVerb;
import ai.kumbuka.dispatch.surface.ReasonCatalogue;
import ai.kumbuka.dispatch.surface.RefusalCode;
import ai.kumbuka.dispatch.surface.SurfaceDeclaration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A1, A5 and A8: the declaration against the contract, with no service running.
 *
 * <p>A unit test and not an integration test, deliberately. What is asserted
 * here is a property of the DECLARATION — the names, the normative
 * descriptions, the reason catalogue — and none of it needs a database, a token
 * or a running surface to be true. Putting it behind Testcontainers would make
 * the cheapest and most-often-broken check the slowest one to run.
 *
 * <p>Every expected value comes from {@link Contract}, which reads the copied
 * contract document. Nothing here reads the declaration to decide what the
 * declaration should say.
 */
@Tag("TST-0001")
@Tag("TST-0005")
@Tag("TST-0008")
class DeclarationConformanceTest {

    // =======================================================================
    // A1 — the tool list is the contract's
    // =======================================================================

    @Test
    void the_tool_list_is_exactly_the_calls_the_contract_declares() {
        List<String> declared = McpTools.declared().stream().map(McpTools.Tool::name).toList();

        assertThat(declared)
            .as("a tool the contract does not name is an addition nobody ratified, and a "
                + "call the contract names and the surface does not carry is an omission "
                + "a caller has no way to notice")
            .containsExactlyInAnyOrderElementsOf(Contract.describedCalls().keySet());
    }

    @Test
    void the_tool_list_carries_fourteen_calls() {
        assertThat(McpTools.declared())
            .as("section 5 declares fourteen: seven for the commissioner, five for the "
                + "executor, two for both")
            .hasSize(14);
    }

    @Test
    void every_description_is_the_contract_s_own_text() {
        Map<String, String> expected = Contract.describedCalls();

        for (McpTools.Tool tool : McpTools.declared()) {
            assertThat(normalise(tool.description()))
                .as("the description of %s is normative text. Reword the contract first, "
                    + "then copy it here — the other order edits the specification to make "
                    + "a probe pass", tool.name())
                .isEqualTo(normalise(expected.get(tool.name())));
        }
    }

    /**
     * Whitespace is not part of the text.
     *
     * <p>The contract wraps its prose at a column and the Java source wraps it
     * at another; both are renderings of one paragraph. Comparing them
     * literally would turn every re-wrap into a red probe, which trains people
     * to edit the copy instead of reading it.
     */
    private static String normalise(String text) {
        return text == null ? null : text.replaceAll("\\s+", " ").trim();
    }

    // =======================================================================
    // A1, second half — the schemas are closed at both levels
    // =======================================================================

    @Test
    void every_schema_is_closed_at_the_top_level() {
        for (McpTools.Tool tool : McpTools.declared()) {
            assertThat(tool.inputSchema().get("additionalProperties"))
                .as("%s accepts an argument it does not declare, which is how a caller "
                    + "comes to depend on a value the server never read", tool.name())
                .isEqualTo(false);
        }
    }

    @Test
    void every_fields_object_is_closed_too() {
        for (McpTools.Tool tool : McpTools.declared()) {
            Object fields = properties(tool).get("fields");
            if (fields == null) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> schema = (Map<String, Object>) fields;
            assertThat(schema.get("additionalProperties"))
                .as("%s's fields object is open. One level of closure is exactly as much "
                    + "use as none: measured on 2026-09-18, `draft` was rejected nowhere "
                    + "because nothing looked inside", tool.name())
                .isEqualTo(false);
        }
    }

    @Test
    void the_calls_that_write_declare_a_fields_object() {
        for (ProcessVerb verb : ProcessVerb.values()) {
            if (!verb.hasFields()) {
                continue;
            }
            assertThat(properties(byName(verb.call())))
                .as("%s writes values into the exchange, and DEC-0040 puts those under "
                    + "fields", verb.call())
                .containsKey("fields");
        }
    }

    @Test
    void no_written_value_sits_at_the_top_level() {
        for (ProcessVerb verb : ProcessVerb.values()) {
            List<String> top = verb.topArguments().stream()
                .map(a -> a.name())
                .toList();
            assertThat(top)
                .as("%s carries a written value at the top level. The top level chooses "
                    + "the target and carries transport artefacts; everything the call "
                    + "writes lives under fields", verb.call())
                .doesNotContain("title", "text", "reason", "message", "question", "into");
        }
    }

    // =======================================================================
    // A5 — the reason catalogue is the contract's
    // =======================================================================

    @Test
    void every_reason_the_contract_declares_exists_in_the_catalogue() {
        List<String> declared =
            ReasonCatalogue.declared().stream().map(r -> r.code().name()).toList();

        assertThat(declared)
            .as("a reason the contract declares and the service cannot raise is a promise "
                + "to a caller that nothing keeps")
            .containsAll(Contract.declaredReasons());
    }

    @Test
    void the_catalogue_declares_no_reason_the_contract_does_not() {
        List<String> fromContract = Contract.declaredReasons();

        for (RefusalCode code : RefusalCode.values()) {
            assertThat(fromContract)
                .as("%s can be returned and the contract's table does not list it. A "
                    + "reason not in the table cannot be returned", code)
                .contains(code.name());
        }
    }

    @Test
    void every_pattern_carries_the_placeholders_the_contract_names() {
        for (RefusalCode code : RefusalCode.values()) {
            if (code == RefusalCode.CHILDREN_NOT_FINISHED) {
                // The one pattern whose contract text describes a REPEATED
                // element rather than a value: "<address> (<state>), ..." is
                // the shape of one entry in a list of unknown length, and the
                // catalogue renders the whole list into a single {offenders}.
                // Matching placeholder for placeholder would be asking the
                // pattern to carry a value per child. What the contract
                // actually requires of this refusal — that each child travels
                // with its complete address, its state and its next — is a
                // property of `data.offenders` and is asserted against a
                // running service in MeasuredDefectsIT and ProcessSurfaceIT.
                continue;
            }

            String contractPattern = Contract.patternOf(code.name());
            if (contractPattern == null || !contractPattern.contains("<")) {
                // 4.3's fixed text, and the two the table describes in prose
                // rather than by pattern. Their shape is asserted where they
                // are raised.
                continue;
            }

            String ours = ReasonCatalogue.of(code).pattern();
            for (String placeholder : placeholdersIn(contractPattern)) {
                assertThat(named(ours, code.name(), placeholder))
                    .as("%s's message must name %s: the contract's pattern does, and a "
                        + "refusal that drops it stops naming what the caller needs",
                        code, placeholder)
                    .isTrue();
            }
        }
    }

    /**
     * Whether our pattern carries the contract's placeholder, under either
     * spelling.
     *
     * <p>The contract writes {@code <call>} and the service fills {@code
     * {call}}; the second is a rendering decision and the first is the
     * specification. A few are renamed where one document's word is another's
     * — {@code <calls>} is the list and {@code {calls}} is the same list — and
     * those are matched by the name, not by the brackets.
     */
    private static boolean named(String ourPattern, String code, String placeholder) {
        if (ourPattern.contains("{" + placeholder + "}")) {
            return true;
        }
        String scoped = ALIASES.get(code + "." + placeholder);
        String plain = ALIASES.get(placeholder);
        return (scoped != null && ourPattern.contains("{" + scoped + "}"))
            || (plain != null && ourPattern.contains("{" + plain + "}"));
    }

    /**
     * Where the contract's word for a value and the catalogue's differ.
     *
     * <p>Each of these is one value under two names, never two values. The pair
     * that does most work is {@code scope} and {@code selector} against {@code
     * collection}: the contract writes {@code dispatch://<scope>/<selector>},
     * which IS the complete collection address, and section 3 requires the
     * complete form everywhere — so rendering it from two halves would be the
     * one place the service assembled an address by hand.
     */
    private static final Map<String, String> ALIASES = Map.ofEntries(
        Map.entry("n", "count"),
        Map.entry("role", "participation"),
        Map.entry("what the call does", "does"),
        Map.entry("what it is", "what"),
        Map.entry("time", "until"),
        Map.entry("ref", "reference"),
        Map.entry("scope", "collection"),
        Map.entry("selector", "collection"),
        // `<list>` means a different list in each of the two patterns that use
        // it, so it is keyed by the reason. An unscoped alias would let a
        // pattern satisfy this probe by naming the WRONG list — which is the
        // failure the probe exists to catch, arriving through the probe itself.
        Map.entry("ARGUMENT_UNKNOWN.list", "arguments"),
        Map.entry("SELECTOR_UNKNOWN.list", "declared"));

    private static List<String> placeholdersIn(String pattern) {
        List<String> found = new java.util.ArrayList<>();
        java.util.regex.Matcher m =
            java.util.regex.Pattern.compile("<([a-z ]+)>").matcher(pattern);
        while (m.find()) {
            found.add(m.group(1));
        }
        return found;
    }

    @Test
    void the_not_found_message_is_the_contract_s_own_and_carries_no_values() {
        assertThat(ReasonCatalogue.NOT_FOUND_MESSAGE)
            .as("the one deliberately indistinguishable refusal must be byte-identical "
                + "across its three causes, so it can carry nothing that differs between "
                + "them")
            .doesNotContain("{")
            .contains("Nothing is visible to you at the address you gave");
    }

    // =======================================================================
    // A8 — an undeclared reason stops the service
    // =======================================================================

    @Test
    void a_servable_declaration_starts() {
        SurfaceDeclaration.requireServable();
    }

    /**
     * A8's red probe, run as a probe rather than described.
     *
     * <p>The catalogue is checked against a set of codes it does not cover, in
     * exactly the shape the start-up guard checks the real one. What is
     * asserted is the guard's behaviour, and the reason this can be asserted at
     * all is that the completeness check takes the sets rather than reading two
     * globals.
     */
    @Test
    void a_reason_with_no_declared_pattern_refuses_to_start() {
        assertThatThrownBy(() -> SurfaceDeclaration.requireServable(Map.of()))
            .as("a service that came up with a reason it cannot word would answer a "
                + "caller with an internal error for a rule the caller broke")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("does not declare");
    }

    /**
     * The guard is what has to refuse, not the check it delegates to.
     *
     * <p>Measured 2026-09-19 by the red probe for A8: with the call to the
     * catalogue check deleted from {@code requireServable}, the earlier form of
     * this probe stayed GREEN, because it called the check directly. It was
     * asserting that the check works — which nothing was disputing — rather
     * than that anything runs it. Both probes now go through the guard, which
     * is the entry point the start-up observer calls.
     */
    @Test
    void the_guard_itself_refuses_an_incomplete_catalogue() {
        assertThatThrownBy(() -> SurfaceDeclaration.requireServable(Map.of()))
            .as("the start-up guard calls requireServable and nothing else; a check it "
                + "stopped delegating to would be a check nobody runs")
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void a_reason_declared_without_a_remedy_refuses_to_start() {
        Map<RefusalCode, ReasonCatalogue.Reason> crippled =
            new java.util.LinkedHashMap<>();
        for (RefusalCode code : RefusalCode.values()) {
            crippled.put(code, new ReasonCatalogue.Reason(code, "a pattern", ""));
        }

        assertThatThrownBy(() -> SurfaceDeclaration.requireServable(crippled))
            .as("a refusal that cannot say what to do about it is not a declared refusal")
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("without a pattern or without a remedy");
    }

    // =======================================================================
    // The declaration as a transportable artefact
    // =======================================================================

    @Test
    void the_declaration_projects_to_plain_maps() {
        Map<String, Object> declaration = SurfaceDeclaration.asMap();

        assertThat(declaration).containsKeys("shape_version", "service", "calls", "reasons");
        assertThat((List<?>) declaration.get("calls")).hasSize(14);
        assertThat((List<?>) declaration.get("reasons"))
            .hasSize(RefusalCode.values().length);
    }

    // =======================================================================
    // Reading the schema
    // =======================================================================

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(McpTools.Tool tool) {
        return (Map<String, Object>) tool.inputSchema().get("properties");
    }

    private static McpTools.Tool byName(String name) {
        return McpTools.declared().stream()
            .filter(t -> t.name().equals(name))
            .findFirst()
            .orElseThrow(() -> new AssertionError(name + " is not declared"));
    }
}
