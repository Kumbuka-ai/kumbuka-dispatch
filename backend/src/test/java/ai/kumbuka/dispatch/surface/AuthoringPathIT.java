package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;

/**
 * The authoring path, driven end to end over each exposition.
 *
 * <p>What is asserted is the whole author-then-executor movement in one run:
 * create, read the token, write the body (dispatch role), read it back on the
 * dispatch role, change the title, send, claim, write the return, read it
 * back on the return role. Both roles are written by the same verb —
 * {@code update} — and the choice of role is a function of state. The role
 * that arrives on the read is the entire assertion the three defects broke.
 *
 * <p><strong>Two expositions, one specification.</strong> The verb surface
 * has one implementation and the two adapters are projections onto it, so an
 * end-to-end probe run twice — once through REST and once through MCP — makes
 * a projection defect impossible to hide in one exposition while the other
 * reads clean. That is the same discipline as the conformance probe, applied
 * to a movement rather than to a single verb.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class AuthoringPathIT {

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asConsole(identity);
    }

    @Test
    @DisplayName("authoring path over REST — create, write body, retitle, send, claim, write "
        + "return, read back each role")
    void the_authoring_path_over_rest() {
        new RestHarness().run();
    }

    /**
     * The assistant surface has no authoring path, and that is the repair.
     *
     * <p>This replaced a probe that drove the same seven-step sequence as the
     * REST one. Six of those steps are the author's — create a draft, write its
     * body, retitle it, send it — and satellite/26.6 removed all of them from
     * this surface: {@code dispatch_commission} creates, writes and freezes in
     * one transaction. Running the old sequence here would mean putting the
     * draft back.
     *
     * <p>What is asserted instead is the property that replaced it: the
     * exchange is {@code open} the moment it exists, and the text the
     * commission carried is the text the read hands back. The REST half of this
     * file still drives the authoring path, because the generic surface still
     * has one.
     */
    @Test
    @DisplayName("assistant surface — commissioning is one step, and leaves no draft")
    void commissioning_over_mcp_leaves_no_authoring_path_to_walk() {
        SurfaceFixture.asConsole(identity);

        Map<String, Object> commissioned = mcp("dispatch_commission", Map.of(
            "scope", SurfaceFixture.SCOPE, "selector", SurfaceFixture.SELECTOR,
            "fields", Map.of("title", "a commission", "apparatus", "code",
                "text", "the commission text", "date", "2026-09-01")));

        assertThat(fieldOf(commissioned, "state"))
            .as("the exchange is open the moment it exists: draft is a state inside the "
                + "transaction and is never visible here")
            .isEqualTo("open");

        String address = (String) commissioned.get("address");
        Map<String, Object> read = mcp("dispatch_read", Map.of("address", address));

        assertThat(fieldOf(read, "dispatch_text"))
            .as("the text the commission carried is the text that was stored — measured "
                + "2026-09-18, a `draft` argument was accepted and discarded, and the "
                + "author was told it had succeeded")
            .isEqualTo("the commission text");
        assertThat(fieldOf(read, "title")).isEqualTo("a commission");
    }

    @SuppressWarnings("unchecked")
    private static Object fieldOf(Map<String, Object> answer, String name) {
        Map<String, Object> fields = (Map<String, Object>) answer.get("fields");
        return fields == null ? null : fields.get(name);
    }

    /** One tool call, asserted to have succeeded, and its structured answer. */
    @SuppressWarnings("unchecked")
    private Map<String, Object> mcp(String tool, Map<String, Object> arguments) {
        Response answer = given().contentType(ContentType.JSON)
            .body(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                "params", Map.of("name", tool, "arguments", arguments)))
            .post("/mcp");

        assertThat(answer.jsonPath().getBoolean("result.isError"))
            .as("'%s' was expected to succeed but was refused: %s", tool,
                answer.jsonPath().getString("result.structuredContent.message"))
            .isFalse();
        return (Map<String, Object>) answer.jsonPath().getMap("result")
            .get("structuredContent");
    }

    // =======================================================================
    // The movement, in one place
    // =======================================================================

    /**
     * Runs the same sequence over whichever exposition the harness offers.
     *
     * <p>Every step reads or writes something the surface exposes, and every
     * assertion is against what the surface hands back. There is no
     * database-side check: the point is that the SURFACE tells the truth about
     * the row, and a row-side check would silently accept a projection that
     * hides the right value.
     */
    private abstract class Harness {

        void run() {
            SurfaceFixture.asConsole(identity);

            // 1. create — the author opens a bracket. No body argument: that is
            //    the initial-draft moment, and taking it away in create is
            //    exactly the alternative the dispatch rejects.
            Created created = create("a first title, to be replaced", "code", "2026-09-01");
            assertThat(created.status).isEqualTo("draft");

            // 2. read — the caller takes the conflict token out of the answer.
            //    The token is a per-exchange marker; the surface hands it out on
            //    reads and the next write is accepted against it.
            String tokenBeforeBodyWrite = read(created.id).conflictToken;

            // 3. update on a draft — the dispatch role. Writes body, and the
            //    dispatch metadata. Before this repair, this call landed the
            //    text in return_body and reported success — a blind write.
            update(created.id, tokenBeforeBodyWrite, Map.of(
                "draft", "the commission text",
                "metadata", Map.of("pr", "https://example.invalid/pr/1")));

            // 4. read — the body is there, on the dispatch role. The return
            //    role must be absent, because nothing has been written to it.
            //    That is what "the state chooses which role is written" MEANS.
            View afterBodyWrite = read(created.id);
            assertThat(afterBodyWrite.dispatchBody)
                .as("the write before send lands in the dispatch role, so the read finds it "
                    + "on body; before this repair it landed in return_body and the read "
                    + "found it there instead")
                .isEqualTo("the commission text");
            assertThat(afterBodyWrite.returnBody)
                .as("nothing has been written to the return role, and NON_NULL means the "
                    + "field is absent from the response rather than empty")
                .isNull();

            // 5. update on a draft — this time the title. Title-change through
            //    the surface was unreachable until this repair: create sets it
            //    and no verb touched it afterwards.
            String tokenBeforeTitleWrite = afterBodyWrite.conflictToken;
            update(created.id, tokenBeforeTitleWrite, Map.of("title", "the actual title"));

            View afterTitleWrite = read(created.id);
            assertThat(afterTitleWrite.title)
                .as("title is a dispatch-role property, and dispatch-role properties are "
                    + "writable before send. This step is the assertion that they REACH the "
                    + "row through the surface, not just that the entity allows the write")
                .isEqualTo("the actual title");
            assertThat(afterTitleWrite.dispatchBody)
                .as("body was not touched in the title write; a null argument leaves its "
                    + "field alone")
                .isEqualTo("the commission text");

            // 6. send — the author commits the dispatch. From here on the
            //    dispatch role is frozen and update lands in the return role.
            send(created.id);

            // 7. claim — the executor takes it up and mints a receipt. This
            //    switches identity — the console cannot claim its own.
            SurfaceFixture.asExecutor(identity);
            String receipt = claim(created.id);

            // 8. read (as executor) — the executor now holds the exchange, so
            //    the projection carries the body. The token has rotated at send
            //    and claim, so a stale one from before either would be refused.
            View afterClaim = read(created.id);
            assertThat(afterClaim.dispatchBody).isEqualTo("the commission text");
            assertThat(afterClaim.status).isEqualTo("active");

            // 9. update after send — the return role. Writes return_body
            //    and return_metadata. Same verb, different target row.
            update(created.id, afterClaim.conflictToken, Map.of(
                "draft", "the answer text",
                "receipt", receipt));

            // 10. read — the return role now carries the answer, and the
            //     dispatch role still carries what the author committed. Two
            //     roles, one row, and the projection tells both.
            View afterReturnWrite = read(created.id);
            assertThat(afterReturnWrite.returnBody)
                .as("the write after send lands in the return role and reads back on the "
                    + "return projection — the two halves the second and third defect broke")
                .isEqualTo("the answer text");
            assertThat(afterReturnWrite.dispatchBody)
                .as("the dispatch role survives the return write unchanged; the row is "
                    + "the same, the roles are separate")
                .isEqualTo("the commission text");
            assertThat(afterReturnWrite.title)
                .as("and the title stays the one the author set")
                .isEqualTo("the actual title");

            // 11. update after send with a title argument — refused, because a
            //     frozen field is frozen. The caller-facing reason is
            //     STATE_DOES_NOT_ALLOW since satellite/26.6: the kernel still
            //     distinguishes FROZEN internally, and the contract's closed
            //     set of reasons does not carry it — what the caller needs is
            //     that the exchange's state does not permit the write, and the
            //     calls that it does permit.
            //     The dispatch fields are frozen at send; a caller that thinks
            //     it can still rename a sent exchange learns so through a typed
            //     refusal, not by finding the old title on a later read.
            updateRefused(created.id, afterReturnWrite.conflictToken, Map.of(
                "draft", "another answer",
                "receipt", receipt,
                "title", "a title that must not stick"),
                "STATE_DOES_NOT_ALLOW");

            View afterFrozenAttempt = read(created.id);
            assertThat(afterFrozenAttempt.title)
                .as("a refused write leaves the row alone — the title stays what it was "
                    + "before the frozen attempt")
                .isEqualTo("the actual title");
        }

        abstract Created create(String title, String apparatus, String date);

        abstract View read(String id);

        abstract void update(String id, String token, Map<String, Object> body);

        /**
         * Attempts an update the surface must refuse with the named reason.
         *
         * <p>Written as a distinct verb rather than a flag on {@link #update}
         * because the two answers a caller receives on refusal and on
         * acceptance are different shapes on both expositions — the harness
         * hides the shape, but the intent has to travel through the call site
         * so that a passing "acceptance" test with a subtly refused body could
         * not be reported as green.
         */
        abstract void updateRefused(String id, String token, Map<String, Object> body,
                                    String expectedReason);

        abstract void send(String id);

        abstract String claim(String id);
    }

    // =======================================================================
    // REST
    // =======================================================================

    private class RestHarness extends Harness {

        @Override
        Created create(String title, String apparatus, String date) {
            Response response = given().contentType(ContentType.JSON)
                .body(Map.of("title", title, "apparatus", apparatus, "date", date))
                .post(SurfaceFixture.collection());
            response.then().statusCode(201);
            String id = response.jsonPath().getString("fields.number") + ".0";
            return new Created(id, response.jsonPath().getString("fields.state"));
        }

        @Override
        View read(String id) {
            Response response = given().accept(ContentType.JSON).get(SurfaceFixture.item(id));
            response.then().statusCode(200);
            return new View(
                response.jsonPath().getString("fields.title"),
                response.jsonPath().getString("fields.state"),
                response.jsonPath().getString("fields.dispatch_text"),
                response.jsonPath().getString("fields.return_text"),
                response.jsonPath().getString("conflict_token"));
        }

        @Override
        void update(String id, String token, Map<String, Object> body) {
            given().contentType(ContentType.JSON)
                .header("If-Match", token)
                .body(body)
                .patch(SurfaceFixture.item(id))
                .then().statusCode(200);
        }

        @Override
        void updateRefused(String id, String token, Map<String, Object> body,
                           String expectedReason) {
            given().contentType(ContentType.JSON)
                .header("If-Match", token)
                .body(body)
                .patch(SurfaceFixture.item(id))
                .then()
                .statusCode(anyOf(equalTo(400), equalTo(403), equalTo(409), equalTo(422)))
                .body("reason", equalTo(expectedReason));
        }

        @Override
        void send(String id) {
            given().accept(ContentType.JSON)
                .post(SurfaceFixture.item(id) + ":send")
                .then().statusCode(200);
        }

        @Override
        String claim(String id) {
            Response response = given().contentType(ContentType.JSON)
                .body(Map.of("duration", "PT1H"))
                .post(SurfaceFixture.item(id) + ":claim");
            response.then().statusCode(200);
            return response.jsonPath().getString("receipt");
        }
    }

    // =======================================================================
    // MCP
    //
    // There is no MCP harness. The harness exists to drive ONE sequence over
    // two expositions and compare them, and since satellite/26.6 the two do not
    // share this sequence: the authoring path is the generic surface's, and the
    // assistant surface reaches the same end state in a single call. A harness
    // that mapped the six author steps onto process verbs would be asserting
    // that a path exists which the repair deliberately removed.
    // =======================================================================

    // =======================================================================
    // What the harness exchanges: two small records so the sequence reads
    // like prose. The wire shapes stay behind their respective harnesses.
    // =======================================================================

    private record Created(String id, String status) {
    }

    private record View(String title, String status, String dispatchBody, String returnBody,
                        String conflictToken) {
    }
}
