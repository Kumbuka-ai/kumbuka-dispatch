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

        // Read back in a LATER transaction, which the 201 alone does not show.
        // A 201 built inside the writing transaction names an address that a
        // rollback then takes away, and the answer looks identical either way:
        // this assertion is here because that is exactly what happened once.
        get(SurfaceFixture.item(number(bracket) + ".0a"))
            .then()
            .statusCode(200)
            .body("address", equalTo(SurfaceFixture.SELECTOR + "/" + number(bracket) + ".0a"));
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
    // query and claim_next, on the collection
    // =======================================================================

    /**
     * GET on the collection lists, and the filter is read from the query
     * string.
     */
    @Test
    void query_lists_the_selector_and_narrows_by_a_declared_filter() {
        String bracket = openBracket();

        get(SurfaceFixture.collection())
            .then()
            .statusCode(200)
            .body("exchanges.address", org.hamcrest.Matchers.hasItem(
                org.hamcrest.Matchers.containsString(bracket)));

        get(SurfaceFixture.collection() + "?status=draft")
            .then()
            .statusCode(200)
            .body("exchanges.address", org.hamcrest.Matchers.hasItem(
                org.hamcrest.Matchers.containsString(bracket)));
    }

    /**
     * An undeclared filter field is refused on the wire, and the field is
     * named.
     *
     * <p>The wire half of the fail-closed rule. A framework that dropped an
     * unknown query parameter would answer 200 with the full set — a plausible
     * answer to a question nobody asked — so the parameters reach the domain
     * unfiltered and the domain refuses.
     */
    @Test
    void an_undeclared_filter_field_is_refused_on_the_wire() {
        openBracket();

        get(SurfaceFixture.collection() + "?title=anything")
            .then()
            .statusCode(422)
            .body("reason", equalTo("FILTER_FIELD_UNKNOWN"))
            .body("offenders", org.hamcrest.Matchers.hasItem("title"));
    }

    /**
     * POST {@code :claim_next} on the collection draws one exchange and
     * answers with its receipt.
     *
     * <p>The one write admissible on a truncated address, because its verb
     * contract declares set semantics and the only declarable one is exactly
     * one.
     */
    @Test
    void claim_next_draws_one_exchange_from_the_collection() {
        String bracket = openBracket();
        post(SurfaceFixture.item(bracket) + ":send", null).then().statusCode(200);

        // WHICH exchange comes back is not asserted here, and deliberately.
        // The cases in this class share one scope and one selector, so the
        // draw takes the position-next claimable one across everything every
        // other case left behind — which is the verb working, not a defect.
        // The selection order is asserted where it can be: against a tenant of
        // its own, in the domain probe.
        post(SurfaceFixture.collection() + ":claim_next", Map.of("duration", "PT1H"))
            .then()
            .statusCode(200)
            .body("exchange.address", org.hamcrest.Matchers.containsString(
                SurfaceFixture.SELECTOR))
            .body("exchange.status", equalTo("active"))
            .body("receipt", org.hamcrest.Matchers.not(org.hamcrest.Matchers.emptyString()));
    }

    /**
     * An empty draw is a typed refusal and never a not-found.
     *
     * <p>Asked of the second declared selector, which no case in this class
     * writes to. The shared one is never reliably empty, and a probe that
     * emptied it first would be asserting about whatever the emptying loop
     * happened to leave.
     */
    @Test
    void claim_next_on_a_selector_with_nothing_claimable_refuses_typed() {
        post("/api/" + SurfaceFixture.SCOPE + "/satellite:claim_next",
                Map.of("duration", "PT1H"))
            .then()
            .statusCode(409)
            .body("reason", equalTo("NOTHING_TO_CLAIM"));
    }

    // =======================================================================
    // The two the scheme does not carry
    // =======================================================================

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

    /**
     * POST on a plain item URI names no verb, and is refused as such.
     *
     * <p>405 rather than 404: the address resolved and the object may well be
     * there — what does not exist is a verb by that spelling. A 404 would send
     * the caller looking for the exchange.
     */
    @Test
    void a_post_on_an_item_that_names_no_verb_is_405_with_allow() {
        String bracket = openBracket();

        Response refused = post(SurfaceFixture.item(bracket), Map.of());

        refused.then()
            .statusCode(405)
            .body("reason", equalTo("WRITE_ON_TRUNCATED_ADDRESS"));
        assertThat(refused.header("Allow")).isEqualTo("GET, PATCH, POST");
    }

    @Test
    void a_colon_naming_no_verb_of_this_scheme_is_refused_the_same_way() {
        String bracket = openBracket();

        post(SurfaceFixture.item(bracket) + ":frobnicate", Map.of())
            .then()
            .statusCode(405)
            .body("reason", equalTo("WRITE_ON_TRUNCATED_ADDRESS"));
    }

    // =======================================================================
    // What the verbs refuse about their arguments
    // =======================================================================

    @Test
    void a_body_that_is_not_the_json_this_verb_takes_is_a_form_refusal() {
        given().contentType(ContentType.JSON).accept(ContentType.JSON)
            .body("{\"title\": ")
            .post(SurfaceFixture.collection())
            .then()
            .statusCode(400)
            .body("reason", equalTo("PAYLOAD_MALFORMED"));
    }

    @Test
    void a_verb_that_takes_a_body_and_got_none_says_so() {
        given().accept(ContentType.JSON).contentType(ContentType.JSON)
            .post(SurfaceFixture.collection())
            .then()
            .statusCode(400)
            .body("reason", equalTo("PAYLOAD_MALFORMED"));
    }

    @Test
    void a_claim_with_no_duration_is_refused_rather_than_given_a_default() {
        String bracket = openBracket();
        send(bracket);

        SurfaceFixture.asExecutor(identity);
        post(SurfaceFixture.item(bracket) + ":claim", Map.of())
            .then()
            .statusCode(400)
            .body("reason", equalTo("PAYLOAD_MALFORMED"))
            .body("message", org.hamcrest.Matchers.containsString("policy"));
    }

    @Test
    void a_claim_duration_that_is_not_a_duration_is_refused() {
        String bracket = openBracket();
        send(bracket);

        SurfaceFixture.asExecutor(identity);
        post(SurfaceFixture.item(bracket) + ":claim", Map.of("duration", "one hour"))
            .then().statusCode(400).body("reason", equalTo("PAYLOAD_MALFORMED"));
    }

    @Test
    void a_claim_duration_that_has_already_lapsed_is_refused_by_the_domain() {
        String bracket = openBracket();
        send(bracket);

        SurfaceFixture.asExecutor(identity);
        post(SurfaceFixture.item(bracket) + ":claim", Map.of("duration", "PT0S"))
            .then()
            .statusCode(400)
            .body("reason", equalTo("CLAIM_DURATION_NOT_POSITIVE"));
    }

    /**
     * The receipt is checked, and a wrong one is a different refusal from a
     * missing claim.
     *
     * <p>Several runs can share one service identity, so the subject alone is
     * not enough: the receipt is what distinguishes the run that won the award
     * from one that merely looks like it.
     */
    @Test
    void a_receipt_that_is_not_the_one_held_is_refused_as_a_mismatch() {
        String bracket = openBracket();
        send(bracket);

        SurfaceFixture.asExecutor(identity);
        claim(bracket);
        String token = get(SurfaceFixture.item(bracket)).header("ETag");

        given().contentType(ContentType.JSON).accept(ContentType.JSON)
            .header("If-Match", token)
            .body(Map.of("draft", "the answer", "receipt", "not-the-minted-one"))
            .patch(SurfaceFixture.item(bracket))
            .then()
            .statusCode(403)
            .body("reason", equalTo("RECEIPT_MISMATCH"));
    }

    /**
     * Metadata that carries a credential is refused, on the surface as in the
     * core.
     *
     * <p>This service holds pointers and never follows them, so a credential
     * stored here can only ever be read by somebody — never used.
     */
    @Test
    void metadata_carrying_a_credential_is_refused_at_the_send_gate() {
        String bracket = openBracket();

        post(SurfaceFixture.item(bracket) + ":send",
            Map.of("metadata", Map.of("mirror", "https://user:secret@example.invalid/x")))
            .then()
            .statusCode(422)
            .body("reason", equalTo("METADATA_REFUSED"));
    }

    @Test
    void metadata_survives_the_send_gate_when_it_is_a_pointer() {
        String bracket = openBracket();

        post(SurfaceFixture.item(bracket) + ":send",
            Map.of("metadata", Map.of("pr", "https://example.invalid/pull/5")))
            .then().statusCode(200).body("status", equalTo("open"));
    }

    /**
     * An addendum is readable through the surface, and carries no conflict
     * token.
     *
     * <p>Both halves are consequences of the domain rather than choices here,
     * and the second one is the reason the first is worth asserting: the token
     * is taken from a method that refuses to draw an addendum at all, and
     * letting that refusal escape would turn a read into a 422 about a field
     * the caller never asked for.
     */
    @Test
    void an_addendum_reads_without_a_conflict_token() {
        String bracket = openBracket();
        send(bracket);
        post(SurfaceFixture.item(bracket) + "/addenda",
            Map.of("title", "a correction", "apparatus", "code", "date", "2026-09-01"))
            .then().statusCode(201);

        Response read = get(SurfaceFixture.item(number(bracket) + ".0a"));

        read.then().statusCode(200);
        assertThat(read.header("ETag")).isNull();
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
