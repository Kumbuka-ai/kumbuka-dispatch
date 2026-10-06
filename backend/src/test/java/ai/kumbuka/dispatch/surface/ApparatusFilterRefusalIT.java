package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every refusal of the draw's apparatus filter, over both surfaces, and the
 * one thing they must all have in common: nothing was claimed.
 *
 * <h2>Why "nothing was claimed" is asserted and not assumed</h2>
 *
 * A refused draw that had already awarded a claim would be the worst outcome
 * available: the caller is told its call failed and retries, while a task
 * somewhere is held by a lease nobody is working. The status code alone cannot
 * rule that out — it says what the caller was told, not what the service did —
 * so every case here reads the task back afterwards and checks that it is
 * still open and unheld.
 *
 * <p>The scope holds exactly one claimable task for the whole class, and
 * each case reads that one back. With more than one, a refusal that claimed
 * "some" task could still leave the one being read untouched.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ApparatusFilterRefusalIT {

    private static final String LEASE = "PT1H";

    @Inject TestIdentityAssociation identity;

    /**
     * The task every refusal case in this class reads back afterwards.
     *
     * <p>Fresh for each case, and it is the case's OWN: a refused draw must
     * leave the task that was there untouched, and reading back one an
     * earlier case had left would be asserting about that case's leftovers.
     */
    private String bait;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asConsole(identity);
        bait = commissionOne();
    }

    // =======================================================================
    // REST: the four refusals of the argument itself
    // =======================================================================

    @Test
    void rest_a_body_without_apparatus_is_refused_and_claims_nothing() {
        Response refused = draw(Map.of("duration", LEASE));

        assertThat(refused.statusCode())
            .as("a body fault, and 400 because the caller can correct it. Not 409: there "
                + "is something claimable there, and the call was refused before the draw")
            .isEqualTo(400);
        assertThat(refused.jsonPath().getString("reason")).isEqualTo("ARGUMENT_MISSING");
        assertThat(refused.jsonPath().getString("message"))
            .as("and the message names the argument, because the remedy is to send it")
            .contains("apparatus");
        assertNothingWasClaimed();
    }

    @Test
    void rest_an_empty_apparatus_list_is_refused_and_claims_nothing() {
        Response refused = draw(body(LEASE, List.of()));

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(refused.jsonPath().getString("reason")).isEqualTo("ARGUMENT_MISSING");
        assertNothingWasClaimed();
    }

    @Test
    void rest_a_pattern_of_only_wildcards_is_refused_and_claims_nothing() {
        for (String unbounded : List.of("*", "**")) {
            Response refused = draw(body(LEASE, List.of(unbounded)));

            assertThat(refused.statusCode())
                .as("'%s' is the blind draw, which this argument exists to close",
                    unbounded)
                .isEqualTo(400);
            assertThat(refused.jsonPath().getString("reason")).isEqualTo("ARGUMENT_INVALID");
            assertThat(refused.jsonPath().getString("message"))
                .as("and the message says WHY a well-spelled pattern was refused, or the "
                    + "caller corrects a spelling that was right")
                .contains("every apparatus");
            assertNothingWasClaimed();
        }
    }

    @Test
    void rest_a_pattern_outside_the_character_rule_is_refused_and_claims_nothing() {
        for (String malformed : List.of("agent_code", "agent-%", "agent code")) {
            Response refused = draw(body(LEASE, List.of(malformed)));

            assertThat(refused.statusCode())
                .as("'%s' carries a character the pattern language does not have", malformed)
                .isEqualTo(400);
            assertThat(refused.jsonPath().getString("reason")).isEqualTo("ARGUMENT_INVALID");
            assertThat(refused.jsonPath().getString("message"))
                .as("the offending pattern is named: this refusal is about one value and a "
                    + "caller sending several has no other way to tell which")
                .contains(malformed);
            assertNothingWasClaimed();
        }
    }

    // =======================================================================
    // REST: the closed body
    // =======================================================================

    /**
     * A field beside {@code duration} and {@code apparatus} is refused rather
     * than dropped.
     *
     * <p>F-0365 in one case: the deserialiser is configured to discard a field
     * it does not recognise, so before this the call would have SUCCEEDED and
     * the misspelt value would simply be gone. That is worse than a refusal in
     * the one way that matters — the caller is told it worked.
     */
    @Test
    void rest_an_undeclared_body_field_is_refused_and_claims_nothing() {
        Map<String, Object> withExtra = new LinkedHashMap<>(body(LEASE, List.of("code")));
        withExtra.put("apparatuses", List.of("agent-*"));

        Response refused = draw(withExtra);

        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(refused.jsonPath().getString("reason")).isEqualTo("ARGUMENT_UNKNOWN");
        assertThat(refused.jsonPath().getString("message"))
            .as("the field is named. A refusal whose whole content is a name and that "
                + "does not carry the name sends the caller reading its own body")
            .contains("apparatuses");
        assertNothingWasClaimed();
    }

    // =======================================================================
    // The assistant surface says the same
    // =======================================================================

    /**
     * The same four refusals over MCP, where the codes must be identical.
     *
     * <p>Both surfaces answer out of one reason catalogue, so a divergence here
     * would mean one of them had grown its own wording — which is the drift the
     * catalogue exists to prevent, and it is only visible by asking both.
     */
    @Test
    void the_assistant_surface_refuses_the_same_four_with_the_same_codes() {
        assertThat(reasonOfTakeNext(null)).isEqualTo("ARGUMENT_MISSING");
        assertThat(reasonOfTakeNext(List.of())).isEqualTo("ARGUMENT_MISSING");
        assertThat(reasonOfTakeNext(List.of("*"))).isEqualTo("ARGUMENT_INVALID");
        assertThat(reasonOfTakeNext(List.of("agent_code"))).isEqualTo("ARGUMENT_INVALID");
        assertNothingWasClaimed();
    }

    /**
     * An undeclared argument on the assistant surface is refused by the closed
     * schema, one layer earlier and under its own code.
     *
     * <p>Named here so the two surfaces' answers to the same mistake sit side
     * by side. They are not the same mechanism — {@code CallArguments} closes
     * every call of this surface, while REST closes this one body — and the
     * code a caller sees is the same either way.
     */
    @Test
    void the_assistant_surface_refuses_an_undeclared_argument_too() {
        Map<String, Object> arguments = new LinkedHashMap<>(takeNextArguments(List.of("code")));
        arguments.put("apparatuses", List.of("agent-*"));

        assertThat(reasonOf(mcp("dispatch_claim_next", arguments)))
            .isEqualTo("ARGUMENT_UNKNOWN");
        assertNothingWasClaimed();
    }

    // =======================================================================
    // The one that is not a refusal, so the class cannot pass by refusing all
    // =======================================================================

    /**
     * A well-formed draw still works, and what comes back matches the pattern.
     *
     * <p>Without this the whole class would pass against a {@code claim_next}
     * that refused everything, which is the failure a probe set made only of
     * refusals cannot see.
     *
     * <p><strong>WHICH task comes back is deliberately not asserted.</strong>
     * Every case in this class, and the other classes of this suite, share one
     * scope and one selector, so the draw takes the position-next claimable one
     * across everything they left behind. What IS asserted is that its
     * apparatus matches the pattern — the property the filter is for, and one
     * that holds whichever task the position picks. The selection order is
     * asserted where it can be, against a tenant of its own, in the domain
     * probe.
     */
    @Test
    void a_well_formed_draw_claims_a_task_of_the_named_apparatus() {
        SurfaceFixture.asExecutor(identity);
        Response drawn = draw(body(LEASE, List.of("code")));

        assertThat(drawn.statusCode()).isEqualTo(200);
        String id = SurfaceFixture.idOf(drawn);
        assertThat(given().get(SurfaceFixture.item(id)).jsonPath().getString("fields.apparatus"))
            .as("the draw was for 'code' and what came back must be addressed to it. This "
                + "is the one positive case of the class, and an address assertion here "
                + "would be about whichever task the shared selector happened to hold")
            .isEqualTo("code");
        assertThat(drawn.jsonPath().getString("fields.state")).isEqualTo("active");
        assertThat(drawn.jsonPath().getString("receipt")).isNotBlank();
    }

    // =======================================================================

    private static Map<String, Object> body(String duration, List<String> apparatus) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("duration", duration);
        body.put("apparatus", apparatus);
        return body;
    }

    private Response draw(Map<String, Object> body) {
        return given().contentType(ContentType.JSON).body(body)
            .post(SurfaceFixture.collection() + ":claim_next");
    }

    private String reasonOfTakeNext(List<String> apparatus) {
        return reasonOf(mcp("dispatch_claim_next", takeNextArguments(apparatus)));
    }

    private static Map<String, Object> takeNextArguments(List<String> apparatus) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put("scope", SurfaceFixture.SCOPE);
        arguments.put("selector", SurfaceFixture.SELECTOR);
        arguments.put("duration", LEASE);
        if (apparatus != null) {
            arguments.put("apparatus", apparatus);
        }
        return arguments;
    }

    private Response mcp(String tool, Map<String, Object> arguments) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", 1);
        envelope.put("method", "tools/call");
        envelope.put("params", Map.of("name", tool, "arguments", arguments));
        return given().contentType(ContentType.JSON).body(envelope).post("/mcp");
    }

    /**
     * The refusal code out of the assistant surface's envelope.
     *
     * <p>Read from {@code result.structuredContent}, which is where this
     * surface puts the platform envelope, and asserted to be an {@code isError}
     * result first: a refusal dressed as a JSON-RPC protocol error would tell
     * the caller the call never happened, and reading the reason without
     * checking would not notice.
     */
    private static String reasonOf(Response response) {
        assertThat(response.jsonPath().getBoolean("result.isError"))
            .as("a refused tool call answers in the platform envelope, as an isError "
                + "result: %s", response.body().asString())
            .isTrue();
        return response.jsonPath().getString("result.structuredContent.reason");
    }

    /**
     * The bait task is still open and held by nobody.
     *
     * <p>Read back over the surface rather than out of the database, because
     * what has to be true is what a caller can see: the next well-formed draw
     * must find this task exactly as the refused one left it.
     */
    private void assertNothingWasClaimed() {
        SurfaceFixture.asConsole(identity);
        Response read = given().get(SurfaceFixture.item(bait));
        read.then().statusCode(200);
        assertThat(read.jsonPath().getString("fields.state"))
            .as("a refused draw must not have moved anything. A task left active by "
                + "a call the caller was told had failed is a lease nobody is working and "
                + "nobody knows about")
            .isEqualTo("open");
        assertThat(read.jsonPath().getString("fields.holder"))
            .as("and nobody holds it")
            .isIn(null, "nobody");
    }

    /** One open, claimable task addressed to {@code code}. */
    private String commissionOne() {
        return SurfaceFixture.open("the bait", "code");
    }
}
