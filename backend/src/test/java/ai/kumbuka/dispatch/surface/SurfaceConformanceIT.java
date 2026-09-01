package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.api.VerbSurfaceSpecification;
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
 * The conformance probe, end-to-end half: every specified outward form is
 * reachable, against a running service and a running database.
 *
 * <p>The static half asserts closure — no route outside the declared bindings
 * — and it cannot assert reachability, because no reading of annotations shows
 * that a colon verb actually arrives at its act. That is what this class does:
 * it takes each form out of the same specification file and calls it.
 *
 * <h2>What counts as reached</h2>
 *
 * The surface answered, rather than the framework. A form that answers 404
 * with no body was never routed; one that answers 500 was routed into
 * something broken. Either way the verb is unreachable, and a probe that only
 * checked for "no 404" would pass the second.
 *
 * <p>The <em>outcome</em> is deliberately not asserted here. Whether a
 * transition succeeds depends on the exchange's state, and pinning the state
 * for thirteen verbs would make this a second copy of {@code ExchangeSurfaceIT}
 * — which asserts the outcomes, one verb at a time and on the right state.
 * What is asserted here is the property that class cannot see: that the set of
 * reachable forms is exactly the specified set.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class SurfaceConformanceIT {

    /** A verb name for the refusal form, which specifies a placeholder. */
    private static final String ANY_VERB = "send";

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asConsole(identity);
    }

    @Test
    void every_specified_outward_form_is_reached_by_the_surface() {
        String bracket = openBracket();

        List<String> unreachable = specifiedForms().stream()
            .filter(form -> !reaches(form, bracket))
            .map(VerbSurfaceSpecification.Row::route)
            .toList();

        assertThat(unreachable)
            .as("every form the specification declares must be answered by this surface. A "
                + "form that is specified and unreachable is not a smaller surface, it is a "
                + "broken contract — and this surface is the published front door of a "
                + "copyleft product, not an internal leg that can be rebuilt at will")
            .isEmpty();
    }

    /**
     * The probe must have had forms to call.
     *
     * <p>An empty specification makes the assertion above pass while measuring
     * nothing, and the file is on the classpath rather than beside the test, so
     * an empty read is a plausible accident rather than an impossible one.
     */
    @Test
    void the_probe_called_the_whole_specified_set() {
        assertThat(specifiedForms())
            .as("thirteen carried forms across fourteen rows, four uncarried, one refusal")
            .hasSize(19);
    }

    /**
     * The uncarried verbs answer a category error and never a not-found.
     *
     * <p>Asserted over the specification rather than one by one, so a verb
     * added to the uncarried list is covered the moment it is written down.
     * The status class is what a caller acts on: 404 says try again later, and
     * these four never will.
     */
    @Test
    void every_uncarried_verb_answers_a_typed_category_error() {
        String bracket = openBracket();

        for (var form : specifiedForms()) {
            if (!"uncarried".equals(form.klass())) {
                continue;
            }
            Response answer = callOf(form, bracket);

            assertThat(answer.statusCode())
                .as("'%s' must answer a typed category error naming the reason — never a "
                    + "404, never an unimplemented path, never a silent absence",
                    form.verb())
                .isEqualTo(422);
            assertThat(answer.jsonPath().getString("reason"))
                .as("and the reason is the stable part a caller matches on, because prose "
                    + "changes when somebody improves it")
                .isNotBlank();
            assertThat(answer.jsonPath().getString("message"))
                .as("naming the reason means saying why, not only that")
                .isNotBlank();
        }
    }

    /**
     * The refusal form answers 405 with {@code Allow}, and that is what the
     * reachability clause above distinguishes every other form from.
     *
     * <p>Asserted here rather than assumed there: if this form stopped
     * answering 405, the clause would start reading a real refusal as the
     * unnamed-verb one and the coverage half would go quiet again.
     */
    @Test
    void the_refusal_form_answers_405_with_allow_and_nothing_else_does() {
        VerbSurfaceSpecification.Row refusal = specifiedForms().stream()
            .filter(r -> "refusal".equals(r.klass()))
            .findFirst()
            .orElseThrow();

        Response answer = callOf(refusal, openBracket());

        assertThat(answer.statusCode()).isEqualTo(405);
        assertThat(answer.jsonPath().getString("reason"))
            .isEqualTo("WRITE_ON_TRUNCATED_ADDRESS");
        assertThat(answer.header("Allow"))
            .as("a 405 without Allow refuses without saying what would have worked")
            .isEqualTo("GET, POST");
    }

    // =======================================================================
    // Calling a specified form
    // =======================================================================

    private static List<VerbSurfaceSpecification.Row> specifiedForms() {
        return VerbSurfaceSpecification.outwardForms();
    }

    /**
     * Whether the surface reached this form's verb.
     *
     * <p>Three ways it did not, and the third one is the one that matters.
     * A 5xx means the form was routed into something broken. An answer with no
     * typed reason means the framework replied, not the surface. And an answer
     * of {@code WRITE_ON_TRUNCATED_ADDRESS} means the surface recognised no
     * verb by that name — which for a specified form is precisely the failure
     * this probe exists to catch.
     *
     * <p>That third clause was added after a red run. Removing {@code consume}
     * from the routing table left this probe green: the call fell through to
     * the refusal that answers an unnamed verb, and a refusal carrying a typed
     * reason looked like the surface having answered. It had answered — with
     * "there is no such verb here", about a verb the specification requires.
     * A reachability probe that accepts that answer measures whether the
     * server is up.
     */
    private boolean reaches(VerbSurfaceSpecification.Row form, String bracket) {
        Response answer = callOf(form, bracket);

        if (answer.statusCode() >= 500) {
            return false;
        }
        if (answer.statusCode() < 300) {
            return true;
        }
        if (answer.body() == null
                || !ContentType.JSON.matches(String.valueOf(answer.getContentType()))) {
            return false;
        }

        String reason = answer.jsonPath().getString("reason");
        boolean unnamedVerb = "WRITE_ON_TRUNCATED_ADDRESS".equals(reason);

        // The refusal row is the one form whose whole purpose IS that answer.
        return reason != null && (unnamedVerb == "refusal".equals(form.klass()));
    }

    private Response callOf(VerbSurfaceSpecification.Row form, String bracket) {
        String path = form.path()
            .replace("{scope}", SurfaceFixture.SCOPE)
            .replace("{selector}", SurfaceFixture.SELECTOR)
            .replace("{id}", bracket)
            .replace("{verb}", ANY_VERB);

        var request = given().accept(ContentType.JSON)
            .contentType(ContentType.JSON)
            .body(payloadFor(form));

        return switch (form.method()) {
            case "GET" -> request.get(path);
            case "POST" -> request.post(path);
            case "PATCH" -> request.patch(path);
            default -> throw new IllegalStateException(
                "the specification names a method this probe cannot call: " + form.method());
        };
    }

    /**
     * Enough of a body for the verb to be entered.
     *
     * <p>Not enough for it to succeed, and that is the point: what is being
     * measured is whether the form arrives at its act, not whether the act
     * likes its arguments.
     */
    private static Map<String, Object> payloadFor(VerbSurfaceSpecification.Row form) {
        return switch (form.verb()) {
            case "create", "append" -> Map.of(
                "title", "a probe", "apparatus", "code", "date", "2026-09-01");
            case "update" -> Map.of("draft", "a probe");
            case "claim" -> Map.of("duration", "PT1H");
            default -> Map.of();
        };
    }

    private String openBracket() {
        Response created = given().contentType(ContentType.JSON)
            .body(Map.of("title", "a commission", "apparatus", "code", "date", "2026-09-01"))
            .post(SurfaceFixture.collection());
        created.then().statusCode(201);
        return created.jsonPath().getString("number") + ".0";
    }
}
