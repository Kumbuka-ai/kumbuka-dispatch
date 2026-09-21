package ai.kumbuka.dispatch.contract;

import ai.kumbuka.dispatch.surface.SurfaceFixture;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a refusal's message actually says — the half {@code EveryRefusalIT}
 * does not ask about.
 *
 * <p>That probe asks whether each declared code is raised and whether any
 * message names a kernel verb. This one asks whether the VALUES the contract's
 * patterns name are there: the instant a claim lapses, the selectors a scope
 * declares, the part the caller actually takes. Section 4.4 writes each of
 * them into its pattern, and each of them was rendered as a stand-in before
 * 2026-09-19 — "its claim lapses" where a time goes, "Declared: none"
 * where the list goes, "as bystander" for a caller that was holding the
 * exchange. A pattern with its value taken out reads as finished and tells the
 * caller nothing.
 *
 * <p>And the rule underneath all of them: a message is built from the pattern
 * alone, so no sentence of the kernel's reaches a caller in whole or in part.
 * The measured leak was through the one refusal whose pattern takes free text.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@Tag("TST-0005")
class RefusalWordingIT {

    /**
     * A short-form address: a selector, a number, a sub-position, and no
     * scheme in front of it.
     *
     * <p>Section 3: "Every address anywhere in an answer or a refusal ... is
     * the complete URI". So a match of this that is not preceded by
     * {@code dispatch://<scope>/} is an address the caller cannot hand back to
     * any call.
     */
    private static final Pattern SHORT_FORM =
        Pattern.compile("(?<!/)\\b([a-z][a-z0-9_-]*)/(\\d+)\\.(\\d+[a-z]?)\\b");

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asConsole(identity);
    }

    /**
     * {@code NOT_THE_HOLDER} names the instant the claim lapses.
     *
     * <p>The contract's {@code <claim expiry>} is an ISO-8601 instant, and the
     * caller's decision — wait, or do something else — cannot be made without
     * it. The predecessor rendered the phrase "its claim lapses", which is the
     * sentence with its one piece of information removed.
     */
    @Test
    void not_the_holder_names_when_the_claim_lapses() {
        String open = commission();

        SurfaceFixture.asExecutor(identity);
        String receipt = receiptOf(call("dispatch_take",
            Map.of("address", open, "duration", "PT1H")));

        SurfaceFixture.asOtherExecutor(identity);
        Response refused = call("dispatch_deliver_return", Map.of(
            "address", open, "receipt", receipt, "fields", Map.of("text", "an answer")));

        assertThat(reason(refused)).isEqualTo("NOT_THE_HOLDER");
        assertThat(message(refused))
            .as("an ISO-8601 instant, not a phrase where the instant goes")
            .containsPattern("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}")
            .doesNotContain("until its claim lapses");
    }

    /**
     * {@code SELECTOR_UNKNOWN} names the selectors the scope declares.
     *
     * <p>The remedy the contract gives this refusal is "use a declared one".
     * A caller told to use a declared one and not told which has been told
     * nothing it can act on — and the predecessor rendered "Declared: none" on
     * a scope that declares two.
     */
    @Test
    void selector_unknown_names_the_declared_ones() {
        Response refused = call("dispatch_query", Map.of(
            "scope", SurfaceFixture.SCOPE, "selector", "a-kind-nobody-declared"));

        assertThat(reason(refused)).isEqualTo("SELECTOR_UNKNOWN");
        assertThat(message(refused))
            .as("the scope declares sprint and satellite, and the refusal says so")
            .contains(SurfaceFixture.SELECTOR)
            .contains("satellite")
            .doesNotContain("Declared: none");
    }

    /**
     * {@code ROLE_DOES_NOT_ALLOW} states the caller's actual part.
     *
     * <p>Section 4.4: "{@code <your part>} is the caller's actual part from
     * section 2". A holder told it takes part as a bystander has been told
     * something false about the exchange it is holding, and the four parts are
     * exactly what the caller reasons with.
     */
    @Test
    void role_does_not_allow_states_the_part_the_caller_actually_takes() {
        String open = commission();

        SurfaceFixture.asExecutor(identity);
        call("dispatch_take", Map.of("address", open, "duration", "PT1H"));

        Response refused = call("dispatch_accept_return", Map.of("address", open));

        assertThat(reason(refused)).isEqualTo("ROLE_DOES_NOT_ALLOW");
        assertThat(message(refused))
            .as("the caller holds this exchange, so it takes part as the holder")
            .contains("as holder")
            .doesNotContain("as bystander");
    }

    /**
     * No message carries a sentence of the kernel's, and every address in one
     * is complete.
     *
     * <p>Two assertions over the same body of text because they failed
     * together: the kernel's sentences are where the short-form addresses
     * were, and the path they took out was the one refusal whose pattern
     * accepts free text.
     */
    @Test
    void no_message_carries_a_kernel_sentence_or_a_short_form_address() {
        for (Response refused : everyRefusalThisProbeCanProvoke()) {
            String message = message(refused);

            assertThat(message)
                .as("a kernel sentence explains the way IN and names the kernel's own "
                    + "vocabulary: %s", message)
                .doesNotContain("cannot takeup")
                .doesNotContain("is reachable only from")
                .doesNotContain("cannot be curated into itself")
                .doesNotContain("an addendum corrects an exchange");

            Matcher m = SHORT_FORM.matcher(message);
            while (m.find()) {
                assertThat(message.substring(0, m.start()))
                    .as("every address in a refusal is complete, and '%s' in '%s' is not",
                        m.group(), message)
                    .endsWith("dispatch://" + SurfaceFixture.SCOPE + "/");
            }
        }
    }

    // =======================================================================
    // Provoking
    // =======================================================================

    /**
     * One refusal of each kind this probe can raise over this surface.
     *
     * <p>Not the whole catalogue — {@code EveryRefusalIT} covers that — but
     * every one whose message carries a value or could carry an address.
     */
    private List<Response> everyRefusalThisProbeCanProvoke() {
        String open = commission();
        String root = commission();
        commissionChild(root);
        String child = commissionChild(root);

        Map<String, Object> withExtra = new LinkedHashMap<>(commissionArguments());
        withExtra.put("no_call_declares_this", "x");

        return List.of(
            // A curation into itself: the branch that used to pass the
            // kernel's sentence through, short-form address and all.
            curateInto(child, child),
            // A bracket verb at a child, and a correction that names one.
            call("dispatch_close_bracket", Map.of("address", child)),
            call("dispatch_add_correction", Map.of("address", child + "a",
                "fields", Map.of("title", "a correction", "text", "its text"))),
            // The state, role and children refusals.
            call("dispatch_accept_return", Map.of("address", open)),
            call("dispatch_close_bracket", Map.of("address", root)),
            call("dispatch_commission", withExtra),
            call("dispatch_read", Map.of("address", "not-an-address")));
    }

    /** A curation of one exchange into another, driven to the point of refusal. */
    private Response curateInto(String address, String target) {
        SurfaceFixture.asExecutor(identity);
        String receipt = receiptOf(call("dispatch_take",
            Map.of("address", address, "duration", "PT1H")));
        call("dispatch_deliver_return", Map.of("address", address, "receipt", receipt,
            "fields", Map.of("text", "the answer")));
        SurfaceFixture.asConsole(identity);

        return call("dispatch_curate_return", Map.of(
            "address", address, "fields", Map.of("into", target)));
    }

    private Map<String, Object> commissionArguments() {
        return Map.of(
            "scope", SurfaceFixture.SCOPE,
            "selector", SurfaceFixture.SELECTOR,
            "fields", Map.of("title", "a probe", "apparatus", "code",
                "text", "the body of the commission"));
    }

    private String commission() {
        return call("dispatch_commission", commissionArguments())
            .jsonPath().getString("result.structuredContent.address");
    }

    private String commissionChild(String parent) {
        Map<String, Object> arguments = new LinkedHashMap<>(commissionArguments());
        arguments.put("parent", parent);
        return call("dispatch_commission", arguments)
            .jsonPath().getString("result.structuredContent.address");
    }

    private static String reason(Response answer) {
        return answer.jsonPath().getString("result.structuredContent.reason");
    }

    private static String message(Response answer) {
        return String.valueOf(
            answer.jsonPath().getString("result.structuredContent.message"));
    }

    private static String receiptOf(Response answer) {
        return answer.jsonPath().getString("result.structuredContent.receipt");
    }

    private Response call(String tool, Map<String, Object> arguments) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", 1);
        envelope.put("method", "tools/call");
        envelope.put("params", Map.of("name", tool, "arguments", arguments));
        return given().contentType(ContentType.JSON).body(envelope).when().post("/mcp");
    }
}
