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

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two bolts, observed through the surface that was just built around them.
 *
 * <p>Both live in the core and neither is enforced here. That is the whole
 * point of probing them <em>from</em> the surface: a check in an adapter would
 * be positioning rather than enforcement, and the next adapter nobody has
 * written yet would not have it. What these tests establish is that the new
 * surface did not walk around either one on its way past — which is the
 * failure mode that costs nothing to introduce and shows up nowhere.
 *
 * <h2>Each carries an observed red run</h2>
 *
 * A guard never seen failing is not a guard. Both were watched going red
 * against a deliberately broken surface, and the breakage in each case is the
 * plausible one — the mapping a hurried reading of the domain would produce,
 * and the identity an adapter "smoothing over a special case" would supply.
 * The runs are recorded where this work was commissioned.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class SurfaceBoltsIT {

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asConsole(identity);
    }

    // =======================================================================
    // Bolt one: no body without a claim
    // =======================================================================

    /**
     * An executing apparatus reading a commission it has not claimed receives
     * <strong>no body field</strong> — not an empty one, not a null one. The
     * key is absent.
     *
     * <p>The difference between absent and null is the guarantee itself. A
     * null body is a field a caller reads and finds empty, which invites a
     * later change to populate it; an absent one cannot be read by accident.
     * This is also the first of three bolts against the claim race: a loser
     * cannot have started work, because it never had anything to start from.
     *
     * <p>RED RUN, observed: with the {@code read} verb mapped onto
     * {@code ExchangeService.read} instead of {@code view} — the same-named
     * domain method, which takes no actor and returns the raw entity — this
     * test fails on the body appearing. Recorded in the return.
     */
    @Test
    void an_executor_reading_an_unclaimed_commission_gets_no_body_field_at_all() {
        String bracket = aSentCommission();

        SurfaceFixture.asExecutor(identity);
        Response answer = given().accept(ContentType.JSON).get(SurfaceFixture.item(bracket));

        answer.then().statusCode(200);
        assertThat(answer.jsonPath().getMap("$"))
            .as("enough to refuse, not enough to work. The field does not exist on the "
                + "answer — a field that is sometimes populated invites a caller to read "
                + "it and invites a later change to populate it always")
            .doesNotContainKey("body")
            .containsKey("title");
    }

    @Test
    void the_body_arrives_with_the_claim_and_only_for_the_holder() {
        String bracket = aSentCommission();

        SurfaceFixture.asExecutor(identity);
        given().contentType(ContentType.JSON).body(Map.of("duration", "PT1H"))
            .post(SurfaceFixture.item(bracket) + ":claim").then().statusCode(200);

        assertThat(given().get(SurfaceFixture.item(bracket)).jsonPath().getMap("$"))
            .as("taking it up is what buys the body")
            .containsKey("body");

        SurfaceFixture.asOtherExecutor(identity);
        assertThat(given().get(SurfaceFixture.item(bracket)).jsonPath().getMap("$"))
            .as("and only for the holder — a second executor still sees none, which is "
                + "what makes the guarantee about the claim rather than about the state")
            .doesNotContainKey("body");
    }

    @Test
    void a_console_identity_reads_the_body_without_holding_the_claim() {
        String bracket = aSentCommission();

        assertThat(given().get(SurfaceFixture.item(bracket)).jsonPath().getMap("$"))
            .as("operators read commissions as a matter of course. Without this half the "
                + "guarantee would just be a switched-off feature")
            .containsKey("body");
    }

    /**
     * The bolt holds on every answer, not only on the read.
     *
     * <p>A transition hands back the entity in the domain, and an entity
     * carries the body. Eleven routes answer a transition, so a surface that
     * serialised what the domain returned would leak the body on all eleven at
     * once — and each of them looks, at the call site, like it is just
     * returning what it got.
     */
    @Test
    void no_transition_answer_carries_the_body_to_an_executor_that_lost_the_race() {
        String bracket = aSentCommission();

        SurfaceFixture.asExecutor(identity);
        given().contentType(ContentType.JSON).body(Map.of("duration", "PT1H"))
            .post(SurfaceFixture.item(bracket) + ":claim").then().statusCode(200);

        SurfaceFixture.asOtherExecutor(identity);
        Response refused = given().contentType(ContentType.JSON).body(Map.of())
            .post(SurfaceFixture.item(bracket) + ":block");

        assertThat(refused.jsonPath().getMap("$"))
            .as("the loser of the race is refused, and the refusal carries no more than "
                + "the refusal")
            .doesNotContainKey("body");
    }

    // =======================================================================
    // Bolt two: the executing apparatus cannot ratify
    // =======================================================================

    /**
     * The executing apparatus calling {@code accept} is refused by the core,
     * with its own typed reason.
     *
     * <p>{@code RATIFICATION_NOT_PERMITTED} rather than the transition
     * refusal, and the distinction is the substance: the transition IS
     * permitted from this state, just not to this caller. An adapter that
     * could not tell the two apart would report "you cannot do that yet" where
     * the truth is "not you, ever", and the caller would retry forever.
     *
     * <p>RED RUN, observed: with the surface substituting a console identity
     * for this token — the shape an adapter "smoothing over a special case"
     * takes — the ratification goes through and this test fails. The bolt
     * itself was not touched in that run. Recorded in the return.
     */
    @Test
    void an_executor_cannot_ratify_and_the_refusal_says_not_you_rather_than_not_yet() {
        String answered = anAnsweredCommission();

        Response refused = given().contentType(ContentType.JSON).body(Map.of())
            .post(SurfaceFixture.item(answered) + ":accept");

        refused.then().statusCode(403);
        assertThat(refused.jsonPath().getString("reason"))
            .as("its own reason rather than a reuse of the transition refusal: an adapter "
                + "that could not tell the two apart would report 'not yet' where the "
                + "truth is 'not you, ever'")
            .isEqualTo("RATIFICATION_NOT_PERMITTED");
    }

    @Test
    void the_console_ratifies_the_same_exchange_the_executor_could_not() {
        String answered = anAnsweredCommission();

        SurfaceFixture.asConsole(identity);
        given().contentType(ContentType.JSON).body(Map.of())
            .post(SurfaceFixture.item(answered) + ":accept")
            .then()
            .statusCode(200)
            .body("status", org.hamcrest.Matchers.equalTo("returned"));
    }

    /**
     * The refusal is the core's, so it reaches the other exposition unchanged.
     *
     * <p>Anything that must hold has to hold for every caller that reaches the
     * core, including an adapter nobody has written yet. Two expositions is
     * the smallest number at which that claim is testable at all.
     */
    @Test
    void the_ratification_bolt_holds_on_the_mcp_exposition_too() {
        String answered = anAnsweredCommission();

        Response refused = given().contentType(ContentType.JSON)
            .body(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                "params", Map.of("name", "accept",
                    "arguments", Map.of("address", SurfaceFixture.address(answered)))))
            .post("/mcp");

        assertThat(refused.jsonPath().getBoolean("result.isError")).isTrue();
        assertThat(refused.jsonPath().getString("result.structuredContent.reason"))
            .isEqualTo("RATIFICATION_NOT_PERMITTED");
    }

    // =======================================================================
    // Staging, through the surface
    // =======================================================================

    /** A commission that has been sent, as the console. */
    private String aSentCommission() {
        Response created = given().contentType(ContentType.JSON)
            .body(Map.of("title", "a commission with a body",
                "apparatus", "code", "date", "2026-09-01"))
            .post(SurfaceFixture.collection());
        created.then().statusCode(201);

        String bracket = created.jsonPath().getString("number") + ".0";
        given().contentType(ContentType.JSON).body(Map.of())
            .post(SurfaceFixture.item(bracket) + ":send").then().statusCode(200);
        return bracket;
    }

    /**
     * A commission the executor holds and has answered — the state in which
     * ratification is the next legitimate act, and in which refusing it can
     * only be about who is asking.
     */
    private String anAnsweredCommission() {
        String bracket = aSentCommission();

        SurfaceFixture.asExecutor(identity);
        String receipt = given().contentType(ContentType.JSON).body(Map.of("duration", "PT1H"))
            .post(SurfaceFixture.item(bracket) + ":claim")
            .jsonPath().getString("receipt");

        String token = given().get(SurfaceFixture.item(bracket)).header("ETag");
        given().contentType(ContentType.JSON).header("If-Match", token)
            .body(Map.of("draft", "the answer", "receipt", receipt))
            .patch(SurfaceFixture.item(bracket)).then().statusCode(200);

        return bracket;
    }
}
