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

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every outward form {@code verb-surface.tsv} specifies, called end to end
 * against a running service and database, is answered by the surface: a
 * success, or a refusal in the surface's envelope. Never the framework's own
 * not-found or method-not-allowed, which would mean the form is specified and
 * no route reaches it.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class SurfaceConformanceIT {

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asConsole(identity);
    }

    @Test
    void every_specified_call_form_is_answered_by_the_surface() {
        String id = SurfaceFixture.open("conformance", "code");
        List<String> unanswered = VerbSurfaceSpecification.of("call").stream()
            .filter(form -> !answeredBySurface(callOf(form, id)))
            .map(VerbSurfaceSpecification.Row::route)
            .toList();
        assertThat(unanswered)
            .as("a form the specification declares and no route answers is a call no REST "
                + "caller can make")
            .isEmpty();
    }

    @Test
    void the_specification_names_twenty_five_call_forms() {
        assertThat(VerbSurfaceSpecification.of("call")).hasSize(25);
    }

    private static boolean answeredBySurface(Response answer) {
        if (answer.statusCode() >= 500) {
            return false;
        }
        if (answer.statusCode() < 300) {
            return true;
        }
        return ContentType.JSON.matches(String.valueOf(answer.getContentType()))
            && answer.jsonPath().getString("reason") != null;
    }

    private static Response callOf(VerbSurfaceSpecification.Row form, String id) {
        String path = form.path()
            .replace("{scope}", SurfaceFixture.SCOPE)
            .replace("{selector}", SurfaceFixture.SELECTOR)
            .replace("{id}", id);
        var request = given().accept(ContentType.JSON);
        return switch (form.method()) {
            case "GET" -> request.get(path);
            case "POST" -> request.post(path);
            case "PATCH" -> request.contentType(ContentType.JSON).body("{}").patch(path);
            case "DELETE" -> request.delete(path);
            default -> throw new IllegalStateException(
                "the specification names a method this probe cannot call: " + form.method());
        };
    }
}
