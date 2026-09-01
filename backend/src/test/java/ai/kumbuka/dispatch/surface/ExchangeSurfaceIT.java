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
import static org.hamcrest.Matchers.equalTo;

/**
 * Every verb of the surface, driven over HTTP against a running database.
 *
 * <p>Coverage is asserted by the conformance probe against the specification;
 * what this class asserts is that each form <em>works</em> — that the route
 * reaches its domain method, that the answer carries what the verb promises,
 * and that the whole chain survives a real PostgreSQL with the real role shape
 * the service runs under. A route that exists and 500s is covered and broken,
 * and only one of the two probes would notice.
 *
 * <p>The database is the substrate container rather than a development
 * service, for the reason the substrate resource states: a development
 * datasource connects as a superuser, and a superuser bypasses row-level
 * security. Every isolation assertion would pass against a schema with the
 * policies deleted.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ExchangeSurfaceIT {

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asConsole(identity);
    }

    // =======================================================================
    // create — both address forms
    // =======================================================================

    @Test
    void create_opens_a_bracket_and_answers_201_with_a_location() {
        Response created = post(SurfaceFixture.collection(), commission("a bracket"));

        created.then()
            .statusCode(201)
            .body("status", equalTo("draft"))
            .body("sub", equalTo(0));

        assertThat(created.header("Location"))
            .as("201 carries the address of what came into being, generated in canonical "
                + "form rather than echoed from the request")
            .endsWith(SurfaceFixture.item(created.jsonPath().getString("number") + ".0"));
    }

    @Test
    void create_on_the_sub_collection_adds_a_child_to_the_bracket() {
        String bracket = openBracket();

        Response child = post(SurfaceFixture.item(bracket) + "/children", commission("a child"));

        child.then().statusCode(201).body("sub", equalTo(1));
        assertThat(child.jsonPath().getInt("number"))
            .as("a child numbers within its bracket instance, so it carries the bracket's "
                + "number and its own sub")
            .isEqualTo(number(bracket));
    }

    @Test
    void the_children_sub_collection_exists_only_at_a_bracket_root() {
        String bracket = openBracket();
        post(SurfaceFixture.item(bracket) + "/children", commission("a child"));

        post(SurfaceFixture.item(number(bracket) + ".1") + "/children", commission("a grandchild"))
            .then()
            .statusCode(400)
            .body("reason", equalTo("ADDRESS_MALFORMED"));
    }

    // =======================================================================
    // read
    // =======================================================================

    @Test
    void read_answers_the_exchange_and_a_conflict_token() {
        String bracket = openBracket();

        Response read = get(SurfaceFixture.item(bracket));

        read.then().statusCode(200).body("address", equalTo(SELECTOR_PREFIX + bracket));
        assertThat(read.header("ETag"))
            .as("the field write declares conflict-token repetition, so a read has to hand "
                + "the token out or the token is unusable")
            .isNotBlank();
    }

    @Test
    void a_well_formed_address_of_nothing_is_a_not_found_and_never_a_form_error() {
        get(SurfaceFixture.item("999999.0"))
            .then()
            .statusCode(404)
            .body("reason", equalTo("NOT_FOUND"));
    }

    @Test
    void a_malformed_address_is_a_form_refusal_and_never_a_not_found() {
        get(SurfaceFixture.item("999999"))
            .then()
            .statusCode(400)
            .body("reason", equalTo("ADDRESS_MALFORMED"));
    }

    // =======================================================================
    // The commitment gates and the claim family
    // =======================================================================

    @Test
    void send_freezes_the_dispatch_and_opens_it() {
        String bracket = openBracket();

        post(SurfaceFixture.item(bracket) + ":send", Map.of())
            .then().statusCode(200).body("status", equalTo("open"));
    }

    @Test
    void claim_awards_the_receipt_and_the_body_arrives_with_it() {
        String bracket = openBracket();
        send(bracket);

        SurfaceFixture.asExecutor(identity);
        Response claimed = post(SurfaceFixture.item(bracket) + ":claim",
            Map.of("duration", "PT1H"));

        claimed.then()
            .statusCode(200)
            .body("exchange.status", equalTo("active"))
            .body("exchange.effectiveHolder", equalTo(SurfaceFixture.EXECUTOR));

        assertThat(claimed.jsonPath().getString("receipt"))
            .as("the receipt is minted by the service and returned once; it is the only copy")
            .isNotBlank();
        assertThat(claimed.jsonPath().getString("exchange.body"))
            .as("taking it up is what buys the body")
            .isNotNull();
    }

    @Test
    void update_replaces_the_handover_draft_against_the_conflict_token() {
        String bracket = openBracket();
        send(bracket);

        SurfaceFixture.asExecutor(identity);
        String receipt = claim(bracket);
        String token = get(SurfaceFixture.item(bracket)).header("ETag");

        given().contentType(ContentType.JSON)
            .header("If-Match", token)
            .body(Map.of("draft", "the answer", "receipt", receipt))
            .patch(SurfaceFixture.item(bracket))
            .then().statusCode(200);
    }

    @Test
    void update_without_the_conflict_token_is_refused_rather_than_applied() {
        String bracket = openBracket();
        send(bracket);
        SurfaceFixture.asExecutor(identity);
        String receipt = claim(bracket);

        given().contentType(ContentType.JSON)
            .body(Map.of("draft", "the answer", "receipt", receipt))
            .patch(SurfaceFixture.item(bracket))
            .then()
            .statusCode(428)
            .body("reason", equalTo("CONFLICT_TOKEN_MISSING"));
    }

    @Test
    void update_on_a_stale_conflict_token_is_412_and_not_a_lost_update() {
        String bracket = openBracket();
        send(bracket);
        SurfaceFixture.asExecutor(identity);
        String receipt = claim(bracket);

        String stale = get(SurfaceFixture.item(bracket)).header("ETag");
        patch(bracket, stale, receipt, "the first answer");

        given().contentType(ContentType.JSON)
            .header("If-Match", stale)
            .body(Map.of("draft", "the second answer", "receipt", receipt))
            .patch(SurfaceFixture.item(bracket))
            .then()
            .statusCode(412)
            .body("reason", equalTo("CONFLICT_TOKEN_STALE"));
    }

    @Test
    void accept_ratifies_the_handover_the_executor_wrote() {
        String returned = anAnsweredExchange();

        SurfaceFixture.asConsole(identity);
        post(SurfaceFixture.item(returned) + ":accept", null)
            .then().statusCode(200).body("status", equalTo("returned"));
    }

    @Test
    void release_gives_the_lease_back_and_the_exchange_returns_to_open() {
        String bracket = openBracket();
        send(bracket);
        SurfaceFixture.asExecutor(identity);
        claim(bracket);

        post(SurfaceFixture.item(bracket) + ":release", null)
            .then().statusCode(200).body("status", equalTo("open"));
    }

    // =======================================================================
    // append
    // =======================================================================

    @Test
    void append_attaches_an_addendum_to_a_frozen_exchange() {
        String bracket = openBracket();
        send(bracket);

        Response addendum = post(SurfaceFixture.item(bracket) + "/addenda",
            Map.of("title", "a correction", "apparatus", "code", "date", "2026-09-01"));

        addendum.then().statusCode(201);
        assertThat(addendum.jsonPath().getString("address"))
            .as("an addendum is a letter on what it corrects, never a regular sub-number: "
                + "an ordinary child would carry the handover expectation and would count "
                + "in the terminality check")
            .endsWith(".0a");
    }

    @Test
    void append_on_a_draft_is_refused_because_there_is_nothing_committed_to_correct() {
        String bracket = openBracket();

        post(SurfaceFixture.item(bracket) + "/addenda",
            Map.of("title", "a correction", "apparatus", "code", "date", "2026-09-01"))
            .then()
            .statusCode(409)
            .body("reason", equalTo("TRANSITION_NOT_PERMITTED"));
    }

    // =======================================================================
    // Termination
    // =======================================================================

    @Test
    void abandon_before_takeup_refuses_the_commission() {
        String bracket = openBracket();
        send(bracket);

        SurfaceFixture.asExecutor(identity);
        post(SurfaceFixture.item(bracket) + ":abandon", null)
            .then()
            .statusCode(200)
            .body("status", equalTo("rejected"));
    }

    @Test
    void abandon_after_takeup_fails_the_work_and_the_caller_names_neither() {
        String bracket = openBracket();
        send(bracket);
        SurfaceFixture.asExecutor(identity);
        claim(bracket);

        // One verb over two domain methods. The prior state decides, the
        // service knows it, and the caller does not say which it meant.
        post(SurfaceFixture.item(bracket) + ":abandon", null)
            .then()
            .statusCode(200)
            .body("status", equalTo("failed"));
    }

    @Test
    void block_pauses_and_resume_returns_it_to_work() {
        String bracket = openBracket();
        send(bracket);
        SurfaceFixture.asExecutor(identity);
        claim(bracket);

        post(SurfaceFixture.item(bracket) + ":block", null)
            .then().statusCode(200).body("status", equalTo("needs_input"));

        post(SurfaceFixture.item(bracket) + ":resume", null)
            .then().statusCode(200).body("status", equalTo("active"));
    }

    @Test
    void close_terminates_administratively() {
        String bracket = openBracket();
        send(bracket);

        post(SurfaceFixture.item(bracket) + ":close", null)
            .then().statusCode(200).body("status", equalTo("closed"));
    }

    @Test
    void consume_terminates_a_returned_exchange_by_curating_it_forward() {
        String returned = anAnsweredExchange();
        SurfaceFixture.asConsole(identity);
        post(SurfaceFixture.item(returned) + ":accept", null).then().statusCode(200);

        post(SurfaceFixture.item(returned) + ":consume", null)
            .then().statusCode(200).body("status", equalTo("consumed"));
    }

    @Test
    void a_bracket_refuses_to_close_while_a_sibling_runs_and_names_the_sibling() {
        String bracket = openBracket();
        post(SurfaceFixture.item(bracket) + "/children", commission("a child"));

        post(SurfaceFixture.item(bracket) + ":close", null)
            .then()
            .statusCode(409)
            .body("reason", equalTo("SIBLINGS_NON_TERMINAL"))
            .body("offenders.size()", equalTo(1));
    }

    // =======================================================================
    // The four the scheme does not carry
    // =======================================================================

    @Test
    void query_is_a_typed_category_error_and_not_a_404() {
        get(SurfaceFixture.collection())
            .then()
            .statusCode(422)
            .body("reason", equalTo("VERB_NOT_CARRIED"))
            .body("message", org.hamcrest.Matchers.containsString("query"));
    }

    @Test
    void claim_next_is_a_typed_category_error_on_the_collection() {
        post(SurfaceFixture.collection() + ":claim_next", null)
            .then()
            .statusCode(422)
            .body("reason", equalTo("VERB_NOT_CARRIED"));
    }

    @Test
    void withdraw_names_the_console_rather_than_being_absent() {
        String bracket = openBracket();

        post(SurfaceFixture.item(bracket) + ":withdraw", null)
            .then()
            .statusCode(422)
            .body("reason", equalTo("WITHDRAWAL_VIA_CONSOLE_ONLY"))
            .body("message", org.hamcrest.Matchers.containsString("console"));
    }

    @Test
    void validate_names_the_undeclared_depth_rather_than_inventing_one() {
        String bracket = openBracket();

        post(SurfaceFixture.item(bracket) + ":validate", null)
            .then()
            .statusCode(422)
            .body("reason", equalTo("VERB_DEPTH_UNDECLARED"));
    }

    // =======================================================================
    // A writing verb on a truncated address
    // =======================================================================

    @Test
    void a_transition_on_a_collection_is_405_with_allow() {
        Response refused = post(SurfaceFixture.collection() + ":send", null);

        refused.then()
            .statusCode(405)
            .body("reason", equalTo("WRITE_ON_TRUNCATED_ADDRESS"));

        assertThat(refused.header("Allow"))
            .as("a 405 without Allow refuses without saying what would have worked, which "
                + "is the one thing the status is required to carry")
            .isEqualTo("GET, POST");
    }

    @Test
    void there_is_no_delete_on_an_exchange_even_where_the_convention_expects_one() {
        String bracket = openBracket();

        // REST follows HTTP conventions in expression, not in offering: what
        // the verb set lacks is not offered, even where convention expects it.
        given().delete(SurfaceFixture.item(bracket))
            .then()
            .statusCode(405);
    }

    // =======================================================================
    // The check order
    // =======================================================================

    @Test
    void an_invisible_scope_answers_404_however_broken_the_rest_of_the_call_is() {
        // Existence in the directory's answer IS the permission, so a 403
        // would confirm a scope exists to a caller who may not see it —
        // turning the error path into a scope enumerator nobody audits.
        given().get("/api/no-such-scope/sprint/1.0")
            .then()
            .statusCode(404)
            .body("reason", equalTo("SCOPE_UNRESOLVED"));
    }

    @Test
    void a_grammar_violation_is_answered_before_any_scope_is_looked_up() {
        // Stage 1 is decidable without knowing a scope, so it may answer 400
        // and leak nothing. A malformed scope must not reach the directory.
        given().get("/api/NO-SUCH-SCOPE/sprint/1.0")
            .then()
            .statusCode(400)
            .body("reason", equalTo("ADDRESS_MALFORMED"));
    }

    @Test
    void a_token_with_no_capacity_is_refused_rather_than_defaulted() {
        SurfaceFixture.asCapacitylessToken(identity);

        given().get(SurfaceFixture.item("1.0"))
            .then()
            .statusCode(403)
            .body("reason", equalTo("ACTOR_UNKNOWN"));
    }

    // =======================================================================
    // Driving the surface
    // =======================================================================

    private static final String SELECTOR_PREFIX = SurfaceFixture.SELECTOR + "/";

    private static Map<String, Object> commission(String title) {
        return Map.of("title", title, "apparatus", "code", "date", "2026-09-01",
            "body", "");
    }

    /** Opens a bracket and returns its id part. */
    private String openBracket() {
        Response created = post(SurfaceFixture.collection(), commission("a commission"));
        created.then().statusCode(201);
        return created.jsonPath().getString("number") + ".0";
    }

    /** An exchange that has been sent, claimed, answered — ready for accept. */
    private String anAnsweredExchange() {
        String bracket = openBracket();
        send(bracket);
        SurfaceFixture.asExecutor(identity);
        String receipt = claim(bracket);
        patch(bracket, get(SurfaceFixture.item(bracket)).header("ETag"), receipt, "the answer");
        return bracket;
    }

    private void send(String id) {
        post(SurfaceFixture.item(id) + ":send", Map.of()).then().statusCode(200);
    }

    private String claim(String id) {
        Response claimed = post(SurfaceFixture.item(id) + ":claim", Map.of("duration", "PT1H"));
        claimed.then().statusCode(200);
        return claimed.jsonPath().getString("receipt");
    }

    private void patch(String id, String token, String receipt, String draft) {
        given().contentType(ContentType.JSON)
            .header("If-Match", token)
            .body(Map.of("draft", draft, "receipt", receipt))
            .patch(SurfaceFixture.item(id))
            .then().statusCode(200);
    }

    private static int number(String id) {
        return Integer.parseInt(id.substring(0, id.indexOf('.')));
    }

    private static Response get(String path) {
        return given().accept(ContentType.JSON).get(path);
    }

    private static Response post(String path, Object body) {
        var request = given().accept(ContentType.JSON);
        if (body != null) {
            request = request.contentType(ContentType.JSON).body(body);
        }
        return request.post(path);
    }
}
