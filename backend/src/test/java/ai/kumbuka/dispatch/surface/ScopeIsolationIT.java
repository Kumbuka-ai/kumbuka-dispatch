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
 * What the platform's read contract says about a scope, enforced — on the
 * collection address and on the complete address alike.
 *
 * <h2>Why both address forms, every time</h2>
 *
 * A caller reaches this service two ways: naming a scope and a selector, or
 * naming a whole address. They are two methods in {@link VerbSurface} and were
 * two places a check could be written, which is the shape a rule drifts out of
 * — the second form gets the check later, or differently, or not at all.
 * Measured in sprint 186 against the composed stack: a write without the write
 * right went through, and no test in this suite could have seen it, because
 * the suite had no scope that refused one.
 *
 * <p>So each rule is probed on both forms and the two answers are compared
 * with each other rather than each against a literal. A comparison against a
 * literal passes on two separately-correct answers that differ; only comparing
 * them catches the case where one form is right and the other is nearly right.
 *
 * <h2>What the fixture has to contain for any of this to be observable</h2>
 *
 * Four scopes and two subjects, staged in {@link SubstrateDatabaseResource}
 * against the V24 form of the view. A suite whose only scope is an ordinary
 * writable project scope cannot observe a single rule below, and would report
 * the service as covered.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ScopeIsolationIT {

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asConsole(identity);
    }

    // =======================================================================
    // The kind filter is gone: a global scope is addressable
    // =======================================================================

    /**
     * The half of the contract change that is not a refusal.
     *
     * <p>The view used to end in {@code WHERE kind = 'project'}, so a global
     * scope did not exist as far as this service was concerned — it answered
     * the not-found class, indistinguishable from a scope that is not there.
     * Now it resolves and the verbs work in it.
     *
     * <p>This is the assertion that keeps the two refusals below honest. A
     * service that refused every scope would pass them both.
     */
    @Test
    void a_global_scope_resolves_and_is_written_and_read_like_any_other() {
        Response created = given().contentType(ContentType.JSON)
            .body(Map.of("title", "in the global scope", "apparatus", "code",
                "date", "2026-09-21"))
            .post("/api/" + SurfaceFixture.GLOBAL_SCOPE + "/" + SurfaceFixture.SELECTOR);

        assertThat(created.statusCode())
            .as("the kind filter is gone from the view, so a global scope is a scope this "
                + "service serves. Before V24 it answered the not-found class")
            .isEqualTo(201);

        given().get("/api/" + SurfaceFixture.GLOBAL_SCOPE + "/" + SurfaceFixture.SELECTOR)
            .then().statusCode(200);
    }

    // =======================================================================
    // Duty 3 — a private scope is refused, identically on both forms
    // =======================================================================

    /**
     * A private scope is visible to this caller and still refused.
     *
     * <p>That combination is the point. The contract publishes the scope — it
     * answers for every service and the memory service serves it — so the
     * refusal cannot be the not-found class without lying about what the
     * directory returned. It is a category statement: an exchange has no
     * meaning in a container for memory content.
     */
    @Test
    void a_private_scope_is_refused_the_same_way_on_both_address_forms() {
        Response collection = given()
            .get("/api/" + SurfaceFixture.PRIVATE_SCOPE + "/" + SurfaceFixture.SELECTOR);
        Response item = given()
            .get("/api/" + SurfaceFixture.PRIVATE_SCOPE + "/" + SurfaceFixture.SELECTOR + "/1.0");

        assertThat(collection.statusCode()).isEqualTo(422);
        assertThat(collection.jsonPath().getString("reason"))
            .isEqualTo("SCOPE_KIND_UNSUPPORTED");

        assertSameRefusal(collection, item,
            "the collection form and the complete address are two methods on the surface, "
                + "and a rule written into one of them is a rule the other does not have");
    }

    /**
     * Refused for a write as well, and with the same answer.
     *
     * <p>A service that refused only the write would be saying the read was
     * fine — a statement about memory content it has no standing to make.
     */
    @Test
    void a_private_scope_refuses_a_write_with_the_same_answer_as_a_read() {
        Response read = given()
            .get("/api/" + SurfaceFixture.PRIVATE_SCOPE + "/" + SurfaceFixture.SELECTOR);
        Response write = given().contentType(ContentType.JSON)
            .body(Map.of("title", "into a private scope", "apparatus", "code",
                "date", "2026-09-21"))
            .post("/api/" + SurfaceFixture.PRIVATE_SCOPE + "/" + SurfaceFixture.SELECTOR);

        assertSameRefusal(read, write,
            "the kind of a scope is not a permission that a read happens to pass and a "
                + "write happens to fail. It is what the scheme carries");
    }

    // =======================================================================
    // Duty 4a — no write right: refused, and reading still works
    // =======================================================================

    /**
     * The muted member: may read this scope, may not write to it.
     *
     * <p>Both write forms are probed, and the collection one is
     * {@code claim_next} rather than {@code create}: it is the one write this
     * surface permits on a truncated address, so it is the form a write check
     * written only for complete addresses would miss entirely.
     */
    @Test
    void a_caller_without_the_write_right_is_refused_on_both_forms_and_still_reads() {
        SurfaceFixture.asMutedConsole(identity);

        Response collectionWrite = given().contentType(ContentType.JSON)
            .body(Map.of("title", "no write right", "apparatus", "code",
                "date", "2026-09-21"))
            .post("/api/" + SurfaceFixture.SCOPE + "/" + SurfaceFixture.SELECTOR);
        Response truncatedWrite = given().contentType(ContentType.JSON)
            .body(Map.of("duration", "PT1H"))
            .post("/api/" + SurfaceFixture.SCOPE + "/" + SurfaceFixture.SELECTOR
                + ":claim_next");
        Response itemWrite = given().contentType(ContentType.JSON)
            .body(Map.of("duration", "PT1H"))
            .post("/api/" + SurfaceFixture.SCOPE + "/" + SurfaceFixture.SELECTOR
                + "/1.0:claim");

        assertThat(collectionWrite.statusCode())
            .as("403 and not 404: this caller can already SEE the scope — the directory "
                + "answered for it — so nothing is revealed, and a 404 would send "
                + "somebody looking for a typo in a correct address")
            .isEqualTo(403);
        assertThat(collectionWrite.jsonPath().getString("reason")).isEqualTo("SCOPE_READ_ONLY");

        assertSameRefusal(collectionWrite, itemWrite,
            "the write right is the platform's answer about the membership and does not "
                + "depend on how the caller spelled the address");
        assertSameRefusal(collectionWrite, truncatedWrite,
            "claim_next writes on a truncated address, which is the form a check written "
                + "for complete addresses alone would let through");

        given().get("/api/" + SurfaceFixture.SCOPE + "/" + SurfaceFixture.SELECTOR)
            .then()
            .statusCode(200);
    }

    // =======================================================================
    // Duty 4b — a locked scope: refused with its own code, and still readable
    // =======================================================================

    /**
     * The lock answers apart from the missing write right, and the order of
     * the two checks is what makes that possible.
     *
     * <p>The view derives {@code can_write} as {@code NOT locked AND …}, so a
     * locked scope always arrives with the write right already false. Checking
     * the write right first would answer every locked scope
     * {@code SCOPE_READ_ONLY} and leave {@code SCOPE_LOCKED} unreachable — a
     * declared code that can never be produced. This test is what holds the
     * order in place.
     */
    @Test
    void a_locked_scope_refuses_a_write_with_its_own_code_on_both_forms() {
        Response collectionWrite = given().contentType(ContentType.JSON)
            .body(Map.of("title", "into a locked scope", "apparatus", "code",
                "date", "2026-09-21"))
            .post("/api/" + SurfaceFixture.LOCKED_SCOPE + "/" + SurfaceFixture.SELECTOR);
        Response itemWrite = given().contentType(ContentType.JSON)
            .body(Map.of("duration", "PT1H"))
            .post("/api/" + SurfaceFixture.LOCKED_SCOPE + "/" + SurfaceFixture.SELECTOR
                + "/1.0:claim");

        assertThat(collectionWrite.statusCode())
            .as("409 and not 403: a lock is the scope's own state, and the same caller "
                + "with the same token gets through once it is lifted")
            .isEqualTo(409);
        assertThat(collectionWrite.jsonPath().getString("reason"))
            .as("SCOPE_LOCKED and not SCOPE_READ_ONLY. The two have different remedies — "
                + "one is lifted where the lock was set, the other by whoever "
                + "administers the membership — and sending somebody to the wrong one is "
                + "worse than telling them nothing")
            .isEqualTo("SCOPE_LOCKED");

        assertSameRefusal(collectionWrite, itemWrite,
            "a lock is a property of the scope and not of the address form used to reach "
                + "it");
    }

    @Test
    void a_locked_scope_is_still_read() {
        Response read = given()
            .get("/api/" + SurfaceFixture.LOCKED_SCOPE + "/" + SurfaceFixture.SELECTOR);

        assertThat(read.statusCode())
            .as("a lock freezes the content; it does not withdraw the scope. Refusing the "
                + "read as well would make a locked scope indistinguishable from one this "
                + "caller may not see")
            .isEqualTo(200);
    }

    /**
     * Two refusals that must agree in the envelope, whatever the transport
     * around them did.
     *
     * <p>Compared in {@code reason} and {@code message} and in the presence of
     * {@code data}, which is the whole of what DEC-0042 fixes. The status is
     * compared too: a difference there would separate the two below the
     * envelope, where a caller branching on the code would not see it.
     */
    private static void assertSameRefusal(Response one, Response other, String why) {
        assertThat(other.statusCode()).as(why).isEqualTo(one.statusCode());
        assertThat(other.jsonPath().getString("reason")).as(why)
            .isEqualTo(one.jsonPath().getString("reason"));
        assertThat(other.jsonPath().getString("message")).as(why)
            .isEqualTo(one.jsonPath().getString("message"));
        assertThat((Object) other.jsonPath().get("data") == null).as(why)
            .isEqualTo((Object) one.jsonPath().get("data") == null);
    }
}
