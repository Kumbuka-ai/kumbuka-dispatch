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
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every answer this surface gives, checked against one rule: does it carry
 * the two role bodies or not?
 *
 * <p>The hotfix says: {@code read} and {@code update} carry them, every
 * other verb answers compact. A twenty-hit query dragging ten-thousand-
 * character bodies is not an answer to "what is open"; a {@code close} that
 * hands back the full dispatch text of a bracket with five children is not
 * an answer to "did it close" — those are the failure modes this test is
 * against.
 *
 * <p><strong>Two expositions, one specification.</strong> Every carrier
 * check runs over REST and over MCP. A projection defect can hide in one
 * exposition while the other reads clean, so both are probed.
 *
 * <p><strong>The token is not stripped.</strong> Every compact response
 * still carries the {@code conflictToken}. Losing it here would put the
 * surface back where it was before the token was projected: {@code update}
 * demands a token that no verb hands out, and every follow-up write is a
 * 412. Asserted alongside the body-absence checks.
 *
 * <p><strong>{@code create} answers compact — a freshly created exchange
 * has no body yet.</strong> The full projection would carry an empty
 * carrier as the empty string (the DB column is {@code NOT NULL DEFAULT
 * ''}), which is worse than absent: a caller reads {@code ""} and thinks
 * it saw a body.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class CompactAnswerIT {

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asConsole(identity);
    }

    @Test
    @DisplayName("compact answers over REST — every transition, create, append, query strip "
        + "the two role bodies; read and update carry them")
    void the_compact_answer_shape_over_rest() {
        new RestHarness().run();
    }

    @Test
    @DisplayName("compact answers over MCP — every transition, create, append, query strip "
        + "the two role bodies; read and update carry them")
    void the_compact_answer_shape_over_mcp() {
        new McpHarness().run();
    }

    // =======================================================================
    // The sequence: every verb once, each answer checked
    // =======================================================================

    private abstract class Harness {

        void run() {
            SurfaceFixture.asConsole(identity);

            // create — compact.
            Map<String, Object> created = create("a commission with a body", "code", "2026-09-01");
            assertCompact(created, "create");

            String id = idOf(created);
            String token = (String) tokenOf(created);

            // update on a draft — full. The caller is the author reading back
            // their own write; that read is exactly the projection that
            // caught three defects in v0.3.0.
            Map<String, Object> updated = update(id, token, Map.of(
                "draft", "the commission text"));
            assertFull(updated, "update on draft");

            // read as console — full.
            Map<String, Object> read = read(id);
            assertFull(read, "read");

            // send — compact.
            Map<String, Object> sent = send(id);
            assertCompact(sent, "send");

            // append — compact, and its answer is the addendum. An addendum
            // takes no field write and carries no conflict token by domain
            // rule (Exchange.conflictToken()), so the compact assertion here
            // skips the token check by way of the addendum flag.
            Map<String, Object> appended = append(id, "a correction");
            assertCompactAddendum(appended, "append");

            // claim (as executor) — compact + receipt.
            SurfaceFixture.asExecutor(identity);
            Map<String, Object> claim = claim(id);
            assertClaim(claim);

            // update after send — full (the return-role readback).
            String tokenAfterClaim = (String) tokenOf(readAsMap(id));
            Map<String, Object> updatedAfterSend = update(id, tokenAfterClaim, Map.of(
                "draft", "the answer text",
                "receipt", receiptOf(claim)));
            assertFull(updatedAfterSend, "update on sent");

            // block — compact.
            assertCompact(block(id), "block");
            // resume — compact.
            assertCompact(resume(id), "resume");
            // release — compact. Note: release drops the claim, so a following
            // read as the same subject sees no body carrier at all.
            assertCompact(release(id), "release");

            // Back to console for the terminating verbs.
            SurfaceFixture.asConsole(identity);

            // query — a listing of compacts.
            List<Map<String, Object>> hits = query();
            assertThat(hits)
                .as("query answers with a listing, and every entry is compact")
                .anySatisfy(entry -> assertCompact(entry, "query entry"));

            // close — compact. The bracket has one child (the addendum) already
            // in a terminal state (append inserts an addendum as sent-and-
            // frozen; addenda close on the base's terminal transition), so
            // close of the .0 succeeds.
            Map<String, Object> closed = close(id);
            assertCompact(closed, "close");
        }

        // -------- assertions ------------------------------------------------

        void assertCompact(Map<String, Object> answer, String label) {
            assertNoCarriers(answer, label);
            assertThat(answer)
                .as("%s: conflictToken travels on every response, compact or not — losing it "
                    + "would put the surface back where it was before the token projection: "
                    + "update demands a token no verb hands out", label)
                .containsKey("conflictToken");
            assertThat(answer.get("conflictToken"))
                .as("%s: conflictToken is a value, not a placeholder", label)
                .isNotNull();
        }

        /**
         * Compact assertion for an addendum: no carriers, no token.
         *
         * <p>The token absence is not a projection bug — it is the domain
         * rule. An addendum takes no field write; a token is what protects
         * one, and one for an object that cannot be written to is a value a
         * caller could send back on a write with nothing to compare against.
         * See {@code Exchange.conflictToken()}, which returns null for
         * {@code addendumSuffix != null}.
         */
        void assertCompactAddendum(Map<String, Object> answer, String label) {
            assertNoCarriers(answer, label);
            assertThat(answer)
                .as("%s: an addendum carries no conflict token — that is a domain rule "
                    + "(Exchange.conflictToken() returns null for an addendum), and the "
                    + "compact projection follows it", label)
                .doesNotContainKey("conflictToken");
        }

        void assertNoCarriers(Map<String, Object> answer, String label) {
            assertThat(answer)
                .as("%s: the two role carriers must be absent from a compact answer — a null "
                    + "value would reach the caller as a field to read; @JsonInclude(NON_NULL) "
                    + "drops it instead", label)
                .doesNotContainKey("dispatchBody")
                .doesNotContainKey("dispatchMetadata")
                .doesNotContainKey("returnBody")
                .doesNotContainKey("returnMetadata");
        }

        void assertFull(Map<String, Object> answer, String label) {
            // The full projection includes at least dispatchBody (an empty
            // string is fine — the DB column is NOT NULL DEFAULT '').
            assertThat(answer)
                .as("%s: the full projection carries the dispatch body — read and update "
                    + "are the two verbs that exist to carry it", label)
                .containsKey("dispatchBody");
            assertThat(answer)
                .as("%s: conflictToken travels here too", label)
                .containsKey("conflictToken");
        }

        void assertClaim(Map<String, Object> claim) {
            Object exchange = claim.get("exchange");
            assertThat(exchange)
                .as("claim's response has an 'exchange' key that carries the compact shape")
                .isInstanceOf(Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> compact = (Map<String, Object>) exchange;
            assertCompact(compact, "claim.exchange");
            assertThat(claim.get("receipt"))
                .as("the receipt is the one thing this response says the compact shape does not")
                .isNotNull();
        }

        // -------- shared helpers -------------------------------------------

        private static String idOf(Map<String, Object> response) {
            return response.get("number") + "." + response.get("sub");
        }

        private static Object tokenOf(Map<String, Object> response) {
            return response.get("conflictToken");
        }

        private static String receiptOf(Map<String, Object> claim) {
            return (String) claim.get("receipt");
        }

        // -------- verbs, one method per exposition -------------------------

        abstract Map<String, Object> create(String title, String apparatus, String date);
        abstract Map<String, Object> read(String id);
        abstract Map<String, Object> readAsMap(String id);
        abstract Map<String, Object> update(String id, String token, Map<String, Object> body);
        abstract Map<String, Object> send(String id);
        abstract Map<String, Object> append(String id, String title);
        abstract Map<String, Object> claim(String id);
        abstract Map<String, Object> release(String id);
        abstract Map<String, Object> block(String id);
        abstract Map<String, Object> resume(String id);
        abstract Map<String, Object> close(String id);
        abstract List<Map<String, Object>> query();
    }

    // =======================================================================
    // REST
    // =======================================================================

    private class RestHarness extends Harness {
        @Override
        Map<String, Object> create(String title, String apparatus, String date) {
            Response r = given().contentType(ContentType.JSON)
                .body(Map.of("title", title, "apparatus", apparatus, "date", date))
                .post(SurfaceFixture.collection());
            r.then().statusCode(201);
            return r.jsonPath().getMap("$");
        }

        @Override
        Map<String, Object> read(String id) {
            Response r = given().accept(ContentType.JSON).get(SurfaceFixture.item(id));
            r.then().statusCode(200);
            return r.jsonPath().getMap("$");
        }

        @Override
        Map<String, Object> readAsMap(String id) {
            return read(id);
        }

        @Override
        Map<String, Object> update(String id, String token, Map<String, Object> body) {
            Response r = given().contentType(ContentType.JSON)
                .header("If-Match", token)
                .body(body)
                .patch(SurfaceFixture.item(id));
            r.then().statusCode(200);
            return r.jsonPath().getMap("$");
        }

        @Override
        Map<String, Object> send(String id) {
            Response r = given().accept(ContentType.JSON)
                .post(SurfaceFixture.item(id) + ":send");
            r.then().statusCode(200);
            return r.jsonPath().getMap("$");
        }

        @Override
        Map<String, Object> append(String id, String title) {
            Response r = given().contentType(ContentType.JSON)
                .body(Map.of("title", title, "apparatus", "code", "date", "2026-09-01"))
                .post(SurfaceFixture.item(id) + "/addenda");
            r.then().statusCode(201);
            return r.jsonPath().getMap("$");
        }

        @Override
        Map<String, Object> claim(String id) {
            Response r = given().contentType(ContentType.JSON)
                .body(Map.of("duration", "PT1H"))
                .post(SurfaceFixture.item(id) + ":claim");
            r.then().statusCode(200);
            return r.jsonPath().getMap("$");
        }

        @Override
        Map<String, Object> release(String id) {
            Response r = given().accept(ContentType.JSON)
                .post(SurfaceFixture.item(id) + ":release");
            r.then().statusCode(200);
            return r.jsonPath().getMap("$");
        }

        @Override
        Map<String, Object> block(String id) {
            Response r = given().accept(ContentType.JSON)
                .post(SurfaceFixture.item(id) + ":block");
            r.then().statusCode(200);
            return r.jsonPath().getMap("$");
        }

        @Override
        Map<String, Object> resume(String id) {
            Response r = given().accept(ContentType.JSON)
                .post(SurfaceFixture.item(id) + ":resume");
            r.then().statusCode(200);
            return r.jsonPath().getMap("$");
        }

        @Override
        Map<String, Object> close(String id) {
            Response r = given().accept(ContentType.JSON)
                .post(SurfaceFixture.item(id) + ":close");
            r.then().statusCode(200);
            return r.jsonPath().getMap("$");
        }

        @Override
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> query() {
            Response r = given().accept(ContentType.JSON).get(SurfaceFixture.collection());
            r.then().statusCode(200);
            return (List<Map<String, Object>>) r.jsonPath().getMap("$").get("exchanges");
        }
    }

    // =======================================================================
    // MCP
    // =======================================================================

    private class McpHarness extends Harness {
        @Override
        Map<String, Object> create(String title, String apparatus, String date) {
            return call("create", Map.of(
                "scope", SurfaceFixture.SCOPE, "selector", SurfaceFixture.SELECTOR,
                "title", title, "apparatus", apparatus, "date", date));
        }

        @Override
        Map<String, Object> read(String id) {
            return call("read", Map.of("address", SurfaceFixture.address(id)));
        }

        @Override
        Map<String, Object> readAsMap(String id) {
            return read(id);
        }

        @Override
        Map<String, Object> update(String id, String token, Map<String, Object> body) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("address", SurfaceFixture.address(id));
            args.put("conflict_token", token);
            args.putAll(body);
            return call("update", args);
        }

        @Override
        Map<String, Object> send(String id) {
            return call("send", Map.of("address", SurfaceFixture.address(id)));
        }

        @Override
        Map<String, Object> append(String id, String title) {
            return call("append", Map.of(
                "address", SurfaceFixture.address(id),
                "title", title, "apparatus", "code", "date", "2026-09-01"));
        }

        @Override
        Map<String, Object> claim(String id) {
            return call("claim",
                Map.of("address", SurfaceFixture.address(id), "duration", "PT1H"));
        }

        @Override
        Map<String, Object> release(String id) {
            return call("release", Map.of("address", SurfaceFixture.address(id)));
        }

        @Override
        Map<String, Object> block(String id) {
            return call("block", Map.of("address", SurfaceFixture.address(id)));
        }

        @Override
        Map<String, Object> resume(String id) {
            return call("resume", Map.of("address", SurfaceFixture.address(id)));
        }

        @Override
        Map<String, Object> close(String id) {
            return call("close", Map.of("address", SurfaceFixture.address(id)));
        }

        @Override
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> query() {
            Map<String, Object> listing = call("query",
                Map.of("scope", SurfaceFixture.SCOPE, "selector", SurfaceFixture.SELECTOR));
            return (List<Map<String, Object>>) listing.get("exchanges");
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> call(String tool, Map<String, Object> arguments) {
            Response answer = rpc("tools/call", Map.of("name", tool, "arguments", arguments));
            answer.then().statusCode(200);
            Map<String, Object> result = answer.jsonPath().getMap("result");
            assertThat((Boolean) result.get("isError"))
                .as("MCP call '%s' expected to succeed but was refused: %s", tool,
                    ((Map<String, Object>) result.get("structuredContent")).get("message"))
                .isFalse();
            return (Map<String, Object>) result.get("structuredContent");
        }
    }

    private static Response rpc(String method, Map<String, Object> params) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", 1);
        envelope.put("method", method);
        envelope.put("params", params);
        return given().contentType(ContentType.JSON).accept(ContentType.JSON)
            .body(envelope).post("/mcp");
    }
}
