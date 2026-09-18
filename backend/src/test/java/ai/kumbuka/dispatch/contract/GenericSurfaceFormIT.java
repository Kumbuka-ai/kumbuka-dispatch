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
            .isEqualTo("Nothing is visible to you at the address you gave. The address or "
                + "the scope may be wrong, or you may lack access; for your protection "
                + "and that of others these cases are not told apart. Check the scope "
                + "name and the number.");
        assertThat((Object) absent.jsonPath().get("data"))
            .as("no data, so the three causes cannot be told apart by what travels")
            .isNull();
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
