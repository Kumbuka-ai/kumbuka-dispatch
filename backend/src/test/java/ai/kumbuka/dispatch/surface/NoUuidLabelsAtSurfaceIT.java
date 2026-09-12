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
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A single guard that stands over every verb answer of this service and asserts
 * one thing: no UUID leaks onto the wire under the identifier of an ordering
 * construct or piece of declared vocabulary.
 *
 * <p><strong>Why this class exists.</strong> The rule that ordering constructs
 * and declared vocabulary present as name, number or slug — and never as a raw
 * UUID — has to be built in code, not carried by convention. A per-field
 * assertion in each verb's own probe would let one added response quietly
 * escape by never being checked. This probe drives every verb once and pattern-
 * matches the answer against the UUID shape: any hit is a leak, and the failure
 * points at the specific answer.
 *
 * <p><strong>What it does not police.</strong> Freetext fields a caller
 * supplies — {@code title}, {@code dispatchBody}, {@code returnBody},
 * metadata — could carry any string, and this probe uses UUID-free values in
 * every write so their content is not the subject. The subject is the fields
 * the service itself fills.
 *
 * <p><strong>Where the wire actually says.</strong> Dispatch happens to be a
 * mostly good citizen already: {@code scope} is a DNS-label slug at the path,
 * {@code selector} is a token, {@code status} is a name, {@code conflictToken}
 * is a timestamp, and {@code receipt} is opaque base64. The one field this
 * class was added to police is {@code effectiveHolder}, which is now one of
 * three states rather than a subject.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class,
    restrictToAnnotatedClass = true)
class NoUuidLabelsAtSurfaceIT {

    /** The RFC 4122 shape, case-insensitive. Nothing else looks like this. */
    private static final Pattern UUID_SHAPE = Pattern.compile(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asConsole(identity);
    }

    /**
     * Every verb answer the surface hands out, driven once in order, and every
     * answer checked in place.
     *
     * <p>One method rather than one per verb, because the point is the walk:
     * the exchange moves through its states, each verb answers, and no answer
     * carries a UUID under an ordering identifier. Splitting the walk would
     * lose the chain and add fixture boilerplate for something the chain
     * already builds.
     */
    @Test
    void no_verb_answer_carries_a_uuid_under_an_ordering_identifier() {
        // create — draft
        Response created = post(SurfaceFixture.collection(),
            Map.of("title", "a walk of every verb",
                "apparatus", "code",
                "date", "2026-09-12",
                "dispatchBody", "the commission text, deliberately without any uuid shape"));
        created.then().statusCode(201);
        assertNoUuid("create", created);

        String bracket = created.jsonPath().getString("number") + ".0";

        // read on a draft
        Response draftRead = get(SurfaceFixture.item(bracket));
        draftRead.then().statusCode(200);
        assertNoUuid("read (draft)", draftRead);

        // send — open
        Response sent = post(SurfaceFixture.item(bracket) + ":send", Map.of());
        sent.then().statusCode(200);
        assertNoUuid("send", sent);

        // query on the collection
        Response listed = get(SurfaceFixture.collection());
        listed.then().statusCode(200);
        assertNoUuid("query", listed);

        // claim — active, and the ClaimResponse
        SurfaceFixture.asExecutor(identity);
        Response claimed = post(SurfaceFixture.item(bracket) + ":claim",
            Map.of("duration", "PT1H"));
        claimed.then().statusCode(200);
        // The receipt is opaque base64 but does not contain the dash-shape a
        // UUID does, so the plain shape check holds even against it.
        assertNoUuid("claim", claimed);
        String receipt = claimed.jsonPath().getString("receipt");

        // read on active — the body now travels
        Response activeRead = get(SurfaceFixture.item(bracket));
        activeRead.then().statusCode(200);
        assertNoUuid("read (active, holder)", activeRead);

        // update — writes the return draft
        String token = activeRead.header("ETag");
        Response updated = given().contentType(ContentType.JSON)
            .header("If-Match", token)
            .body(Map.of("draft", "the answer, no uuid shape here",
                "receipt", receipt))
            .patch(SurfaceFixture.item(bracket));
        updated.then().statusCode(200);
        assertNoUuid("update", updated);

        // read after update — sees the return body
        Response afterUpdate = get(SurfaceFixture.item(bracket));
        afterUpdate.then().statusCode(200);
        assertNoUuid("read (after update)", afterUpdate);

        // append — an addendum lives beside the bracket
        Response addendum = post(SurfaceFixture.item(bracket) + "/addenda",
            Map.of("title", "a correction", "apparatus", "code", "date", "2026-09-01"));
        addendum.then().statusCode(201);
        assertNoUuid("append", addendum);

        // read from a caller who does NOT hold the exchange — same wire fields
        // apply, and effectiveHolder should read "other" rather than a subject
        SurfaceFixture.asOtherExecutor(identity);
        Response otherRead = get(SurfaceFixture.item(bracket));
        otherRead.then().statusCode(200);
        assertNoUuid("read (other executor)", otherRead);
    }

    /**
     * The refusal shape also travels — it carries {@code offenders}, and a
     * caller-visible refusal on a wrong scope-shape should not smuggle a UUID
     * back either.
     */
    @Test
    void refusals_do_not_carry_a_uuid_under_an_ordering_identifier() {
        Response refused = given().accept(ContentType.JSON)
            .get("/api/no-such-scope/sprint/1.0");
        refused.then().statusCode(404);
        assertNoUuid("refusal (unknown scope)", refused);
    }

    private static void assertNoUuid(String label, Response response) {
        String body = response.asString();
        assertThat(UUID_SHAPE.matcher(body).find())
            .as("%s: no verb answer of this service may carry a UUID under an "
                + "ordering identifier — a caller reading a raw UUID has no path "
                + "to what it names. Body was:%n%s", label, body)
            .isFalse();
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
