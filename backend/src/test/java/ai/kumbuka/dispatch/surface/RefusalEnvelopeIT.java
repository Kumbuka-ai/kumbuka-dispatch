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

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The refusal envelope of DEC-0042: one shape, one not-found code, and the
 * machine-readable detail under {@code data}.
 *
 * <h2>Where the expectations come from</h2>
 *
 * From the node and from the dispatch, never from an answer this service was
 * observed giving. The literal {@code NOT_FOUND} is the node's; that the two
 * cases agree is the node's clause; that detail sits under {@code data} is the
 * node's envelope. A test written against a recorded answer would pass on
 * whatever the service does and would have passed before this change too.
 *
 * <h2>Why byte-for-byte and not "both are 404"</h2>
 *
 * The clause is about what a caller can distinguish. Two answers that agree in
 * the status and differ in the message are still an oracle: a caller learns
 * from the wording that the scope exists and it may not see it, which is
 * precisely what ADR-0011 requires to be invisible. So the comparison is
 * between the answers, in the fields a caller reads.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class RefusalEnvelopeIT {

    /** The one code the not-found class carries. Taken from DEC-0042, not from a run. */
    private static final String NOT_FOUND = "NOT_FOUND";

    /**
     * The one message that class carries, transcribed from the router.
     *
     * <p><strong>Source:</strong> {@code RouterException.NOT_FOUND_MESSAGE} on
     * {@code main} of {@code Kumbuka-ai/platform},
     * {@code router/src/main/java/ai/kumbuka/router/surface/RouterException.java},
     * lines 35-37, read on 2026-09-21 — character for character, and copied
     * rather than read from {@link ReasonCatalogue#NOT_FOUND_MESSAGE}.
     *
     * <p>Reading it from the artefact under test is the one thing this
     * constant must not do. Every comparison below it asks whether the
     * answers of this service agree with each other, and they did while all
     * of them together said something the router does not say: a message of
     * this service's own, which tells a caller by its wording which hop
     * answered. An expectation taken from the subject moves with the subject.
     * This one does not, which is why it can catch that.
     */
    private static final String NOT_FOUND_MESSAGE =
        "nothing is addressed here. Check the address, and that you are a member of "
            + "the scope it names.";

    /** An address in the visible scope whose number was never allocated. */
    private static final String NEVER_ALLOCATED = "4242.0";

    /** A scope name this caller has no membership in. Well formed, and not there. */
    private static final String UNSEEABLE = "a-scope-this-caller-cannot-see";

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asConsole(identity);
    }

    // =======================================================================
    // Duty 5 — one not-found code, on both address forms
    // =======================================================================

    /**
     * An absent object and an invisible scope, on the complete address.
     *
     * <p>These are two of the three members of the class in DEC-0042. The
     * third — an address whose scheme has no owner — is the router's and
     * cannot arise in this service; that it is out of reach here is recorded
     * rather than asserted against.
     */
    @Test
    void an_absent_object_and_an_invisible_scope_answer_identically() {
        Response absent = restItem(SurfaceFixture.SCOPE, NEVER_ALLOCATED);
        Response invisible = restItem(UNSEEABLE, "1.0");

        assertThat(absent.statusCode()).isEqualTo(404);
        assertThat(absent.jsonPath().getString("reason"))
            .as("the literal from the node, and the same one for every member of the "
                + "class. A distinguishing code is the enumeration oracle ADR-0011 was "
                + "written against")
            .isEqualTo(NOT_FOUND);
        assertThat((Object) absent.jsonPath().get("data"))
            .as("the not-found answers carry no data at all — an absent key, not an "
                + "empty one, because the clause is about what a caller can compare")
            .isNull();

        assertIdentical(absent, invisible,
            "an object that is not there and an object this caller may not know is there "
                + "are one answer");
    }

    /**
     * The same, on the collection form.
     *
     * <p>An invisible scope is reachable on both forms and has to answer the
     * same on both. The absent-object half has no collection counterpart —
     * an unknown selector is vocabulary of a scope the caller can already see,
     * not an address that names nothing — and it is deliberately not forced
     * into the class here.
     */
    @Test
    void an_invisible_scope_answers_the_same_on_the_collection_form() {
        Response item = restItem(UNSEEABLE, "1.0");
        Response collection = restCollection(UNSEEABLE);

        assertThat(collection.jsonPath().getString("reason")).isEqualTo(NOT_FOUND);
        assertIdentical(item, collection,
            "the two address forms are two methods on the surface, and the clause holds "
                + "on the call rather than on the spelling of the address");
    }

    /**
     * A write into an invisible scope answers the same as a read of one.
     *
     * <p>Otherwise the refusal separates the cases by verb instead of by code:
     * a caller that reads 404 and writes 403 has learned the same thing the
     * distinguishing code would have told it.
     */
    @Test
    void a_write_into_an_invisible_scope_answers_the_same_as_a_read() {
        assertIdentical(restCollection(UNSEEABLE), restWrite(UNSEEABLE),
            "the verb must not separate what the code unified");
    }

    /**
     * Every not-found answer of the generic surface carries the router's
     * message.
     *
     * <p><strong>This is the assertion the suite did not have.</strong> The
     * comparisons above bind the answers of this service to each other; this
     * one binds them to the other hop. The difference is not academic: they
     * were all equal to each other, and all of them differed from the router,
     * and nothing went red. A caller reading two wordings for one condition
     * learns which service answered — and from that whether a service stands
     * behind a scheme at all, which is the enumeration oracle ADR-0011 is
     * written against.
     */
    @Test
    void every_not_found_answer_of_the_generic_surface_carries_the_routers_message() {
        assertCarriesTheRoutersMessage(restItem(SurfaceFixture.SCOPE, NEVER_ALLOCATED),
            "an object that was never allocated");
        assertCarriesTheRoutersMessage(restItem(UNSEEABLE, "1.0"),
            "an item in a scope this caller cannot see");
        assertCarriesTheRoutersMessage(restCollection(UNSEEABLE),
            "the collection form of a scope this caller cannot see");
        assertCarriesTheRoutersMessage(restWrite(UNSEEABLE),
            "a write into a scope this caller cannot see");
    }

    /**
     * And so does every not-found answer of the process verbs.
     *
     * <p>Asserted against the literal and not against the generic surface's
     * answer. The comparison below already holds the two together, and two
     * surfaces that drift together is precisely the state it cannot see.
     *
     * <p>Both address forms, because the two are two methods here as well.
     */
    @Test
    void every_not_found_answer_of_the_process_verbs_carries_the_routers_message() {
        Response item = rpc("dispatch_read",
            Map.of("address", "dispatch://" + UNSEEABLE + "/" + SurfaceFixture.SELECTOR
                + "/1.0"));
        Response absent = rpc("dispatch_read",
            Map.of("address", SurfaceFixture.address(NEVER_ALLOCATED)));
        Response collection = rpc("dispatch_query",
            Map.of("scope", UNSEEABLE, "selector", SurfaceFixture.SELECTOR));

        for (Response refused : List.of(item, absent, collection)) {
            assertThat(isError(refused)).isTrue();
            assertThat(reason(refused)).isEqualTo(NOT_FOUND);
            assertThat(message(refused))
                .as("measured against the router's text rather than against what the "
                    + "generic surface of this same service happens to say")
                .isEqualTo(NOT_FOUND_MESSAGE);
            assertThat(dataOf(refused))
                .as("no data on this surface either — the clause is about bytes, and the "
                    + "envelope is the same envelope")
                .isNull();
        }
    }

    /**
     * The process verbs answer the same envelope as the generic verbs.
     *
     * <p>The community edition serves both, and a service whose protocol path
     * answered a bare error carrying prose is the state DEC-0042 records as
     * the reason it was written. The transport around the envelope differs
     * and is not compared.
     */
    @Test
    void the_process_verbs_carry_the_same_envelope_as_the_generic_verbs() {
        Response rest = restItem(UNSEEABLE, "1.0");

        // The process verbs address an item by URI rather than by three
        // arguments. Naming the parts here would be refused as an undeclared
        // argument before any scope was looked at, and the test would compare
        // two refusals that have nothing to do with the clause under test.
        Response tool = rpc("dispatch_read",
            Map.of("address", "dispatch://" + UNSEEABLE + "/" + SurfaceFixture.SELECTOR
                + "/1.0"));

        assertThat(isError(tool))
            .as("a refusal is a call that was made and answered, so it comes back as an "
                + "error result and not as a transport error")
            .isTrue();
        assertThat(reason(tool)).isEqualTo(rest.jsonPath().getString("reason"));
        assertThat(message(tool))
            .as("the message is compared too. A caller reads it, and two messages for one "
                + "condition is the divergence the envelope exists to close")
            .isEqualTo(rest.jsonPath().getString("message"));
        assertThat(dataOf(tool)).isNull();
    }

    // =======================================================================
    // Duty 6 — the detail sits under data
    // =======================================================================

    /**
     * A refusal that names what caused it puts the names under {@code data}.
     *
     * <p>An unfinished child is the one refusal in this service that carries
     * a list of objects, so it is the one that can say where such a list
     * travels. Before satellite/26.6 the names sat in a third top-level member
     * beside the reason and the message, which is a second envelope shape
     * however well the member was named.
     */
    @Test
    void the_offenders_of_a_refusal_sit_under_data_and_not_beside_the_message() {
        String bracket = aBracketWithAnUnfinishedChild();

        Response refused = given().contentType(ContentType.JSON)
            .post(SurfaceFixture.item(bracket) + ":close");

        assertThat(refused.statusCode()).isEqualTo(409);
        assertThat(refused.jsonPath().getString("reason"))
            .isEqualTo("CHILDREN_NOT_FINISHED");
        assertThat(refused.jsonPath().getList("data.offenders"))
            .as("under data, which is where DEC-0042 puts machine-readable detail")
            .hasSize(1);
        assertThat((Object) refused.jsonPath().get("offenders"))
            .as("and nowhere else. A member that stayed at the top level would be the old "
                + "shape surviving beside the new one, which is worse than either")
            .isNull();
    }

    /** The same detail, in the same place, on the process verbs. */
    @Test
    void the_process_verbs_put_the_offenders_under_data_too() {
        String bracket = aBracketWithAnUnfinishedChild();

        Response tool = rpc("dispatch_close_bracket",
            Map.of("address", SurfaceFixture.address(bracket)));

        assertThat(isError(tool)).isTrue();
        assertThat(reason(tool)).isEqualTo("CHILDREN_NOT_FINISHED");
        assertThat(tool.jsonPath().getList("result.structuredContent.data.offenders"))
            .hasSize(1);
        assertThat((Object) tool.jsonPath().get("result.structuredContent.offenders"))
            .as("no top-level member here either. The envelope is built in one place for "
                + "both surfaces, and this is what says so from the outside")
            .isNull();
    }

    /**
     * A scope refusal names what was attempted and nothing more.
     *
     * <p>{@code data.attempted} is section 4.1's, and it is carried here as
     * everywhere. What a scope refusal must NOT carry is an offender list:
     * there is no object to name — the call never reached one — and a list
     * with nothing in it is a key that would have to stay identical for ever.
     */
    @Test
    void a_scope_refusal_names_what_was_attempted_and_carries_no_offenders() {
        Response refused = restWrite(SurfaceFixture.LOCKED_SCOPE);

        assertThat(refused.jsonPath().getString("reason")).isEqualTo("SCOPE_LOCKED");
        assertThat((Object) refused.jsonPath().get("data.attempted")).isNotNull();
        assertThat((Object) refused.jsonPath().get("data.offenders")).isNull();
        assertThat((Object) refused.jsonPath().get("offenders")).isNull();
    }

    // =======================================================================
    // Helpers
    // =======================================================================

    /**
     * A bracket root whose child is still open.
     *
     * <p>Built through the surface rather than staged in the database: the
     * refusal under test re-reads each blocking child through the surface to
     * render its address, and a child written straight into the table would
     * exercise a path the service does not take.
     */
    private String aBracketWithAnUnfinishedChild() {
        String number = given().contentType(ContentType.JSON)
            .body(commission("a bracket that cannot close"))
            .post(SurfaceFixture.collection())
            .then().statusCode(201)
            .extract().jsonPath().getString("fields.number");

        given().contentType(ContentType.JSON)
            .body(commission("a child"))
            .post(SurfaceFixture.item(number + ".0") + "/children")
            .then().statusCode(201);

        return number + ".0";
    }

    /**
     * A commission as the generic surface takes one.
     *
     * <p>{@code dispatchBody} and not {@code text}: the two surfaces name this
     * field differently, and the generic one's name is the projection's.
     */
    private static Map<String, Object> commission(String title) {
        return Map.of("title", title, "apparatus", "code", "date", "2026-09-21",
            "dispatchBody", "the body");
    }

    private static Response restItem(String scope, String id) {
        return given().get("/api/" + scope + "/" + SurfaceFixture.SELECTOR + "/" + id);
    }

    private static Response restCollection(String scope) {
        return given().get("/api/" + scope + "/" + SurfaceFixture.SELECTOR);
    }

    private static Response restWrite(String scope) {
        return given().contentType(ContentType.JSON)
            .body(commission("a write into " + scope))
            .post("/api/" + scope + "/" + SurfaceFixture.SELECTOR);
    }

    /**
     * One refusal carrying the message the class carries everywhere, router
     * included.
     */
    private static void assertCarriesTheRoutersMessage(Response refused, String which) {
        assertThat(refused.jsonPath().getString("reason")).as(which).isEqualTo(NOT_FOUND);
        assertThat(refused.jsonPath().getString("message"))
            .as("%s — the message is the router's, character for character. A service "
                + "wording its own is one a caller tells from the router by reading it",
                which)
            .isEqualTo(NOT_FOUND_MESSAGE);
    }

    /** Two refusals a caller cannot tell apart, in the fields a caller reads. */
    private static void assertIdentical(Response one, Response other, String why) {
        assertThat(other.statusCode()).as(why).isEqualTo(one.statusCode());
        assertThat(other.jsonPath().getString("reason")).as(why)
            .isEqualTo(one.jsonPath().getString("reason"));
        assertThat(other.jsonPath().getString("message")).as(why)
            .isEqualTo(one.jsonPath().getString("message"));
        assertThat((Object) other.jsonPath().get("data")).as(why).isNull();
        assertThat((Object) one.jsonPath().get("data")).as(why).isNull();
    }

    /** One tool call on the process-verb surface. */
    private static Response rpc(String tool, Map<String, Object> arguments) {
        return given().contentType(ContentType.JSON)
            .body(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                "params", Map.of("name", tool, "arguments", arguments)))
            .post("/mcp");
    }

    private static boolean isError(Response answer) {
        return Boolean.TRUE.equals(answer.jsonPath().getBoolean("result.isError"));
    }

    private static String reason(Response answer) {
        return answer.jsonPath().getString("result.structuredContent.reason");
    }

    private static String message(Response answer) {
        return answer.jsonPath().getString("result.structuredContent.message");
    }

    private static Object dataOf(Response answer) {
        return answer.jsonPath().get("result.structuredContent.data");
    }
}
