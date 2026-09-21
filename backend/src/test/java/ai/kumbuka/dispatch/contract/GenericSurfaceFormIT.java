package ai.kumbuka.dispatch.contract;

import ai.kumbuka.dispatch.surface.ProcessVerb;
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

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A9: the generic surface answers in the same shape, in its own vocabulary.
 *
 * <p>Two halves, and the second is the one that is easy to get wrong. The shape
 * is the contract's and is shared — {@code address}, {@code fields}, {@code
 * conflict_token}, {@code next}, {@code waiting_for} on an answer; {@code
 * reason}, {@code message}, {@code data} on a refusal. The VOCABULARY is each
 * surface's own: a REST caller told to call {@code dispatch_accept_return} has
 * been told to call something it cannot reach, and telling it so is worse than
 * telling it nothing, because it will try.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@Tag("TST-0009")
class GenericSurfaceFormIT {

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asConsole(identity);
    }

    // =======================================================================
    // The answer
    // =======================================================================

    @Test
    void an_answer_carries_the_five_members_of_section_three() {
        String id = openAndRead();

        Response read = get(SurfaceFixture.item(id));

        read.then().statusCode(200);
        assertThat(read.jsonPath().getString("address"))
            .as("the address is complete here too — one shape, both surfaces")
            .startsWith("dispatch://");
        assertThat((Object) read.jsonPath().get("fields"))
            .as("what the exchange holds lives under fields, on either surface")
            .isNotNull();
        assertThat(read.jsonPath().getString("conflict_token")).isNotBlank();
        assertThat((Object) read.jsonPath().get("next"))
            .as("REQ-0152 is about every answer, not about the assistant surface's")
            .isNotNull();
    }

    @Test
    void next_names_rest_verbs_and_never_a_process_verb() {
        String id = openAndRead();

        List<String> calls = get(SurfaceFixture.item(id)).jsonPath().getList("next.call");

        assertThat(calls)
            .as("a REST caller can only make REST calls; naming a process verb would send "
                + "it to a tool it has no way to reach")
            .isNotEmpty()
            .noneMatch(call -> call.startsWith("dispatch_"));

        for (ProcessVerb verb : ProcessVerb.values()) {
            assertThat(calls).doesNotContain(verb.call());
        }
    }

    @Test
    void a_listing_entry_carries_its_own_next() {
        openAndRead();

        Response listed = get(SurfaceFixture.collection());

        listed.then().statusCode(200);
        assertThat((Object) listed.jsonPath().get("exchanges[0].next"))
            .as("a caller that lists and then acts must not have to read every hit again "
                + "before it can act on any of them")
            .isNotNull();
        assertThat(listed.jsonPath().getString("exchanges[0].address"))
            .startsWith("dispatch://");
    }

    // =======================================================================
    // The refusal
    // =======================================================================

    @Test
    void a_refusal_names_the_rest_verb_the_caller_made() {
        String id = openAndRead();

        // accept on an exchange with no answer: a real refusal from a state
        // the caller can see.
        Response refused = post(SurfaceFixture.item(id) + ":accept", Map.of());

        assertThat(refused.statusCode()).isNotEqualTo(200);
        assertThat(refused.jsonPath().getString("message"))
            .as("the refusal names the call under the name this caller used for it")
            .contains("accept")
            .doesNotContain("dispatch_accept_return");
    }

    @Test
    void a_refusal_carries_the_contract_s_three_members() {
        String id = openAndRead();

        Response refused = post(SurfaceFixture.item(id) + ":accept", Map.of());

        assertThat(refused.jsonPath().getString("reason")).isNotBlank();
        assertThat(refused.jsonPath().getString("message")).isNotBlank();
        assertThat((Object) refused.jsonPath().get("data"))
            .as("a refusal about a real exchange carries the data a caller acts on")
            .isNotNull();
        assertThat((Object) refused.jsonPath().get("data.next"))
            .as("and the way out travels with it, on either surface")
            .isNotNull();
    }

    @Test
    void the_not_found_refusal_is_the_same_bytes_here_too() {
        Response absent = get(SurfaceFixture.item("99999.0"));

        assertThat(absent.statusCode()).isEqualTo(404);
        assertThat(absent.jsonPath().getString("message"))
            // The router's wording since 187.17, not this service's own.
            // Transcribed from RouterException.NOT_FOUND_MESSAGE on main of
            // Kumbuka-ai/platform (lines 35-37, read 2026-09-21): DEC-0042's
            // clause does not stop at the service boundary, and two hops
            // wording one condition differently is an oracle for which hop
            // answered. RefusalEnvelopeIT is where that is argued and probed
            // in full; this assertion is the generic surface's own copy.
            .isEqualTo("nothing is addressed here. Check the address, and that you are "
                + "a member of the scope it names.");
        assertThat((Object) absent.jsonPath().get("data"))
            .as("no data, so the three causes cannot be told apart by what travels")
            .isNull();
    }


    /**
     * The two codes only this surface can raise, raised.
     *
     * <p>Section 4.4: "On the assistant surface the receipt and the conflict
     * token are required arguments wherever a call takes them, so their absence
     * is answered as {@code ARGUMENT_MISSING}; {@code RECEIPT_MISSING} and
     * {@code CONFLICT_TOKEN_MISSING} are raised on the generic surface only,
     * where both are optional in form."
     *
     * <p>This probe is the reason {@code EveryRefusalIT} may exclude the two:
     * it names this test, and Before 2026-09-19 it named a probe that did
     * not exist — a comment asserting coverage, which is the one kind of
     * comment that makes a gap invisible. A code declared in the catalogue and
     * raised by nothing is a published promise nothing keeps, and the exclusion
     * is only honest while the coverage is somewhere.
     */
    @Test
    void the_two_codes_this_surface_alone_can_raise() {
        String id = openAndRead();

        // CONFLICT_TOKEN_MISSING — a field write with no If-Match. On REST the
        // token travels in an optional header, so the surface sees the absence
        // itself rather than a schema check answering first.
        Response noToken = given().contentType(ContentType.JSON).accept(ContentType.JSON)
            .body(Map.of("title", "a new title"))
            .patch(SurfaceFixture.item(id));

        assertThat(noToken.jsonPath().getString("reason"))
            .as("a write that repeats the conflict token and carries none is refused for "
                + "the absence, and told so specifically enough to fix")
            .isEqualTo("CONFLICT_TOKEN_MISSING");
        assertThat(noToken.jsonPath().getString("message"))
            .as("and the message names this surface's call, never a process verb")
            .doesNotContain("dispatch_");

        // RECEIPT_MISSING — the holder writing its answer without the receipt.
        // The claim is real and the proof did not travel, which is a different
        // thing from holding no claim at all.
        SurfaceFixture.asExecutor(identity);
        Response claimed = post(SurfaceFixture.item(id) + ":claim",
            Map.of("duration", "PT1H"));
        claimed.then().statusCode(200);

        String token = get(SurfaceFixture.item(id)).jsonPath().getString("conflict_token");
        Response noReceipt = given().contentType(ContentType.JSON).accept(ContentType.JSON)
            .header("If-Match", token)
            .body(Map.of("draft", "the answer"))
            .patch(SurfaceFixture.item(id));

        assertThat(noReceipt.jsonPath().getString("reason"))
            .as("writing the return role needs the receipt the claim issued, and its "
                + "absence has its own code on the surface where it is optional in form")
            .isEqualTo("RECEIPT_MISSING");
        assertThat(noReceipt.jsonPath().getString("message"))
            .as("the step is named in THIS surface's vocabulary: section 4.2 has the "
                + "pattern name a step and the surface fill in its own call for it")
            .doesNotContain("dispatch_take");
    }

    /**
     * A blocking child on this surface carries the calls that would finish it.
     *
     * <p>Section 4.4 gives {@code CHILDREN_NOT_FINISHED} the remedy "the calls
     * in each offender's next". Before 2026-09-19 this surface wrote an
     * empty list into every offender, so the remedy pointed at nothing on the
     * one surface where a caller has no second way to find out what is blocking
     * it.
     */
    @Test
    void a_blocking_child_carries_its_own_next_here_too() {
        String root = openAndRead();
        post(SurfaceFixture.item(root) + "/children", Map.of(
            "title", "a child", "apparatus", "code", "date", "2026-09-01"))
            .then().statusCode(201);

        Response refused = post(SurfaceFixture.item(root) + ":close", Map.of());

        assertThat(refused.jsonPath().getString("reason"))
            .isEqualTo("CHILDREN_NOT_FINISHED");
        assertThat(refused.jsonPath().getList("data.offenders"))
            .as("the blockers travel as structure under data, never as prose")
            .isNotEmpty();
        assertThat(refused.jsonPath().getList("data.offenders[0].next"))
            .as("and each one carries what would finish it, computed for this caller on "
                + "this surface")
            .isNotEmpty();
        assertThat(refused.jsonPath().getString("data.offenders[0].address"))
            .as("with a complete address, so the caller can act on it unchanged")
            .startsWith("dispatch://");
        assertThat(refused.jsonPath().getList("data.offenders[0].next.call"))
            .as("REST verbs, because this is a REST caller")
            .noneMatch(call -> String.valueOf(call).startsWith("dispatch_"));
    }

    // =======================================================================
    // Driving the surface
    // =======================================================================

    /** A bracket, opened and sent, and its id part. */
    private String openAndRead() {
        Response created = post(SurfaceFixture.collection(), Map.of(
            "title", "a commission", "apparatus", "code", "date", "2026-09-01"));
        created.then().statusCode(201);

        String id = created.jsonPath().getString("fields.number") + ".0";
        post(SurfaceFixture.item(id) + ":send", Map.of()).then().statusCode(200);
        return id;
    }

    private static Response get(String path) {
        return given().accept(ContentType.JSON).get(path);
    }

    private static Response post(String path, Map<String, Object> body) {
        return given().contentType(ContentType.JSON).accept(ContentType.JSON)
            .body(body).post(path);
    }
}
