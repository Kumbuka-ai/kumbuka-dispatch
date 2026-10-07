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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A uuid reaches a caller in one place only: as the technical address of a
 * task, {@code dispatch://<uuid>}, in the field {@code identity} of a full head
 * (concept section 3.3). Nowhere else -- not in a lean answer, not in a
 * listing's ordering, not in a refusal -- and never as a bare label a caller
 * has no path from.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class NoUuidLabelsAtSurfaceIT {

    private static final Pattern UUID_SHAPE = Pattern.compile(
        "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asConsole(identity);
    }

    @Test
    void a_lean_answer_carries_no_uuid_and_a_head_carries_it_only_as_its_identity() {
        Response created = SurfaceFixture.create(Map.of("title", "a walk", "apparatus", "code",
            "text", "a commission without any uuid shape"));
        assertNoUuid("create", created.asString());
        String id = SurfaceFixture.idOf(created);
        Response sent = given().header("If-Match", created.header("ETag"))
            .post(SurfaceFixture.item(id) + ":send");
        assertNoUuid("send", sent.asString());

        SurfaceFixture.asExecutor(identity);
        Response claimed = given().contentType(ContentType.JSON).body(Map.of())
            .post(SurfaceFixture.item(id) + ":claim");
        assertNoUuid("claim", claimed.asString());

        Response head = given().get(SurfaceFixture.item(id));
        assertThat(head.jsonPath().getString("fields.identity"))
            .matches("dispatch://" + UUID_SHAPE.pattern());
        assertOnlyAsIdentity("read", head.asString());
        assertOnlyAsIdentity("query", given().get(SurfaceFixture.collection()).asString());
    }

    @Test
    void a_refusal_carries_no_uuid() {
        Response refused = given().accept(ContentType.JSON)
            .get("/api/no-such-scope/sprint/1.0");
        refused.then().statusCode(404);
        assertNoUuid("refusal", refused.asString());
    }

    private static void assertNoUuid(String label, String body) {
        assertThat(UUID_SHAPE.matcher(body).find())
            .as("%s: a lean answer carries no uuid. Body was:%n%s", label, body)
            .isFalse();
    }

    /** Every uuid in the body stands as {@code "identity":"dispatch://<uuid>"}. */
    private static void assertOnlyAsIdentity(String label, String body) {
        Matcher any = UUID_SHAPE.matcher(body);
        while (any.find()) {
            String before = body.substring(Math.max(0, any.start() - 24), any.start());
            assertThat(before)
                .as("%s: a uuid stands only as the technical address under identity. "
                    + "Body was:%n%s", label, body)
                .endsWith("\"identity\":\"dispatch://");
        }
    }
}
