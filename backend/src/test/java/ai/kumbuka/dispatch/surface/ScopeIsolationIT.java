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

import java.util.LinkedHashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the platform's read contract says about a scope, enforced — on both
 * address forms and on both surfaces.
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
 * <h2>Why both surfaces, every time</h2>
 *
 * The process verbs and the generic verbs are two expositions, and each one
 * builds its own refusal from its own vocabulary. That the rule holds on one
 * of them says nothing about the other: the scope check is shared, but the
 * ANSWER is not, and a code that reached a caller on one surface and fell
 * through to a state lookup on the other would be a rule half kept. Both are
 * probed for every duty, and the answers within a surface are compared with
 * each other rather than each against a literal — a comparison against a
 * literal passes on two separately-correct answers that differ.
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

    /** An address in a scope, well formed, whose object need not exist. */
    private static String addressIn(String scope) {
        return "dispatch://" + scope + "/" + SurfaceFixture.SELECTOR + "/1.0";
    }

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
     * <p>This is the assertion that keeps every refusal below honest. A
     * service that refused every scope would pass all of them.
     */
    @Test
    void a_global_scope_resolves_and_is_written_and_read_on_both_surfaces() {
        assertThat(restCollectionWrite(SurfaceFixture.GLOBAL_SCOPE).statusCode())
            .as("the kind filter is gone from the view, so a global scope is a scope this "
                + "service serves. Before V24 it answered the not-found class")
            .isEqualTo(201);

        given().get("/api/" + SurfaceFixture.GLOBAL_SCOPE + "/" + SurfaceFixture.SELECTOR)
            .then().statusCode(200);

        Response commissioned = mcpCollectionWrite(SurfaceFixture.GLOBAL_SCOPE);
        assertThat(isError(commissioned))
            .as("the process verbs reach the same directory through the same call, so a "
                + "global scope has to work here too — and it is worth asserting rather "
                + "than assuming, because this surface builds its own answer")
            .isFalse();

        assertThat(isError(mcpCollectionRead(SurfaceFixture.GLOBAL_SCOPE))).isFalse();
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
    void a_private_scope_is_refused_the_same_way_on_both_address_forms_of_rest() {
        Response collection = restCollectionRead(SurfaceFixture.PRIVATE_SCOPE);
        Response item = restItemRead(SurfaceFixture.PRIVATE_SCOPE);

        assertThat(collection.statusCode())
            .as("422 and not 404: the scope is real and visible, and what is refused is "
                + "an exchange in a scope of that kind. Nothing the caller presents "
                + "changes it")
            .isEqualTo(422);
        assertThat(collection.jsonPath().getString("reason"))
            .isEqualTo("SCOPE_KIND_UNSUPPORTED");
        assertThat(collection.jsonPath().getString("message"))
            .as("the pattern names the kind, which is what tells the caller WHICH of its "
                + "scopes it reached rather than only that this one was wrong")
            .contains("private");

        assertSameRestRefusal(collection, item,
            "the collection form and the complete address are two methods on the surface, "
                + "and a rule written into one of them is a rule the other does not have");
    }

    @Test
    void a_private_scope_is_refused_the_same_way_on_both_address_forms_of_the_process_verbs() {
        Response collection = mcpCollectionRead(SurfaceFixture.PRIVATE_SCOPE);
        Response item = mcpItemRead(SurfaceFixture.PRIVATE_SCOPE);

        assertThat(reason(collection)).isEqualTo("SCOPE_KIND_UNSUPPORTED");
        assertThat(message(collection)).contains("private");

        assertSameMcpRefusal(collection, item,
            "the two address forms are two methods here as well, and this surface builds "
                + "its own answer from them");
    }

    /**
     * Refused for a write as well, and with the same answer.
     *
     * <p>A service that refused only the write would be saying the read was
     * fine — a statement about memory content it has no standing to make.
     */
    @Test
    void a_private_scope_refuses_a_write_with_the_same_answer_as_a_read() {
        assertSameRestRefusal(restCollectionRead(SurfaceFixture.PRIVATE_SCOPE),
            restCollectionWrite(SurfaceFixture.PRIVATE_SCOPE),
            "the kind of a scope is not a permission that a read happens to pass and a "
                + "write happens to fail. It is what the scheme carries");

        assertSameMcpRefusal(mcpCollectionRead(SurfaceFixture.PRIVATE_SCOPE),
            mcpCollectionWrite(SurfaceFixture.PRIVATE_SCOPE),
            "and the process verbs say the same, for the same reason");
    }

    // =======================================================================
    // Duty 4a — no write right: refused, and reading still works
    // =======================================================================

    /**
     * The muted member: may read this scope, may not write to it.
     *
     * <p>Three write forms are probed on REST, and the middle one is
     * {@code claim_next}: it is the one write this surface permits on a
     * truncated address, so it is the form a write check written only for
     * complete addresses would miss entirely.
     */
    @Test
    void a_caller_without_the_write_right_is_refused_on_both_rest_forms_and_still_reads() {
        SurfaceFixture.asMutedConsole(identity);

        Response collectionWrite = restCollectionWrite(SurfaceFixture.SCOPE);
        Response truncatedWrite = given().contentType(ContentType.JSON)
            .body(Map.of("duration", "PT1H"))
            .post("/api/" + SurfaceFixture.SCOPE + "/" + SurfaceFixture.SELECTOR
                + ":claim_next");
        Response itemWrite = restItemWrite(SurfaceFixture.SCOPE);

        assertThat(collectionWrite.statusCode())
            .as("403 and not 404: this caller can already SEE the scope — the directory "
                + "answered for it — so nothing is revealed, and a 404 would send "
                + "somebody looking for a typo in a correct address. Ratified by the "
                + "operator on 2026-09-21 as an ADR-0011 exception")
            .isEqualTo(403);
        assertThat(collectionWrite.jsonPath().getString("reason")).isEqualTo("SCOPE_READ_ONLY");
        assertThat(collectionWrite.jsonPath().getString("message"))
            .as("the pattern names the scope, because the remedy is that scope's "
                + "membership and a caller told only 'you may not write' does not know "
                + "whose membership to ask about")
            .contains(SurfaceFixture.SCOPE);

        assertSameRestRefusalBarTheCall(collectionWrite, itemWrite,
            "the write right is the platform's answer about the membership and does not "
                + "depend on how the caller spelled the address");
        assertSameRestRefusalBarTheCall(collectionWrite, truncatedWrite,
            "claim_next writes on a truncated address, which is the form a check written "
                + "for complete addresses alone would let through");

        given().get("/api/" + SurfaceFixture.SCOPE + "/" + SurfaceFixture.SELECTOR)
            .then()
            .statusCode(200);
    }

    @Test
    void a_caller_without_the_write_right_is_refused_on_both_process_verb_forms_too() {
        SurfaceFixture.asMutedConsole(identity);

        Response collectionWrite = mcpCollectionWrite(SurfaceFixture.SCOPE);
        Response itemWrite = mcpItemWrite(SurfaceFixture.SCOPE);

        assertThat(reason(collectionWrite)).isEqualTo("SCOPE_READ_ONLY");
        assertThat(message(collectionWrite))
            .as("the vocabulary is this surface's: the pattern names the call the caller "
                + "actually made, and a caller here told to stop making a REST verb has "
                + "been told about something it cannot reach")
            .contains("dispatch_commission");

        assertSameMcpRefusalBarTheCall(collectionWrite, itemWrite,
            "the write right does not depend on the address form here either");

        assertThat(isError(mcpCollectionRead(SurfaceFixture.SCOPE)))
            .as("reading is unaffected: the refusal is about writing, and a read that "
                + "failed too would make a read-only scope indistinguishable from one "
                + "this caller may not see")
            .isFalse();
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
    void a_locked_scope_refuses_a_write_with_its_own_code_on_both_rest_forms() {
        Response collectionWrite = restCollectionWrite(SurfaceFixture.LOCKED_SCOPE);
        Response itemWrite = restItemWrite(SurfaceFixture.LOCKED_SCOPE);

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

        assertSameRestRefusal(collectionWrite, itemWrite,
            "a lock is a property of the scope and not of the address form used to reach "
                + "it");
    }

    @Test
    void a_locked_scope_refuses_a_write_with_its_own_code_on_both_process_verb_forms() {
        Response collectionWrite = mcpCollectionWrite(SurfaceFixture.LOCKED_SCOPE);
        Response itemWrite = mcpItemWrite(SurfaceFixture.LOCKED_SCOPE);

        assertThat(reason(collectionWrite))
            .as("the order of the two checks holds on this surface as well, and it holds "
                + "for the same reason: they share the one directory call")
            .isEqualTo("SCOPE_LOCKED");
        assertThat(message(collectionWrite)).contains(SurfaceFixture.LOCKED_SCOPE);

        assertSameMcpRefusal(collectionWrite, itemWrite,
            "a lock is a property of the scope here too");
    }

    @Test
    void a_locked_scope_is_still_read_on_both_surfaces() {
        assertThat(restCollectionRead(SurfaceFixture.LOCKED_SCOPE).statusCode())
            .as("a lock freezes the content; it does not withdraw the scope. Refusing the "
                + "read as well would make a locked scope indistinguishable from one this "
                + "caller may not see")
            .isEqualTo(200);

        assertThat(isError(mcpCollectionRead(SurfaceFixture.LOCKED_SCOPE))).isFalse();
    }

    // =======================================================================
    // The two surfaces, on the one thing they must NOT differ on
    // =======================================================================

    /**
     * Where the pattern carries no call, the two surfaces answer alike.
     *
     * <p>Section 4.2 makes the vocabulary part of the message, so two surfaces
     * wording one refusal differently is correct wherever the message names a
     * call. {@code SCOPE_KIND_UNSUPPORTED} and {@code SCOPE_LOCKED} name none
     * — they are about the scope and not about what was attempted — so a
     * difference between the surfaces there would be drift rather than
     * vocabulary, and nothing else in the suite would see it.
     */
    @Test
    void the_two_surfaces_word_the_scope_only_refusals_identically() {
        assertThat(message(mcpCollectionRead(SurfaceFixture.PRIVATE_SCOPE)))
            .as("the kind refusal names no call, so the two surfaces have nothing to "
                + "differ about")
            .isEqualTo(restCollectionRead(SurfaceFixture.PRIVATE_SCOPE)
                .jsonPath().getString("message"));

        assertThat(message(mcpCollectionWrite(SurfaceFixture.LOCKED_SCOPE)))
            .as("and neither does the lock")
            .isEqualTo(restCollectionWrite(SurfaceFixture.LOCKED_SCOPE)
                .jsonPath().getString("message"));
    }

    // =======================================================================
    // The four calls, per surface
    // =======================================================================

    private static Response restCollectionRead(String scope) {
        return given().get("/api/" + scope + "/" + SurfaceFixture.SELECTOR);
    }

    private static Response restCollectionWrite(String scope) {
        return given().contentType(ContentType.JSON)
            .body(Map.of("title", "a write into " + scope, "apparatus", "code",
                "date", "2026-09-21", "dispatchBody", "the body"))
            .post("/api/" + scope + "/" + SurfaceFixture.SELECTOR);
    }

    private static Response restItemRead(String scope) {
        return given().get("/api/" + scope + "/" + SurfaceFixture.SELECTOR + "/1.0");
    }

    /**
     * A write at a complete address.
     *
     * <p>{@code claim} rather than an update: it needs no conflict token, so
     * the call reaches stage 2 without a form fault in front of it. What is
     * under test is the scope rule, and a probe that was refused for a missing
     * token would never find out whether the scope rule ran.
     */
    private static Response restItemWrite(String scope) {
        return given().contentType(ContentType.JSON)
            .body(Map.of("duration", "PT1H"))
            .post("/api/" + scope + "/" + SurfaceFixture.SELECTOR + "/1.0:claim");
    }

    private Response mcpCollectionRead(String scope) {
        return call("dispatch_query",
            Map.of("scope", scope, "selector", SurfaceFixture.SELECTOR));
    }

    private Response mcpCollectionWrite(String scope) {
        return call("dispatch_commission", Map.of(
            "scope", scope,
            "selector", SurfaceFixture.SELECTOR,
            "fields", Map.of("title", "a write into " + scope, "apparatus", "code",
                "text", "the body", "date", "2026-09-21")));
    }

    private Response mcpItemRead(String scope) {
        return call("dispatch_read", Map.of("address", addressIn(scope)));
    }

    /**
     * A write at a complete address, in the caller's own part.
     *
     * <p>{@code dispatch_add_correction} rather than {@code dispatch_take}:
     * the probing identity is a commissioner, and a call reserved for the
     * executor would be refused for the part rather than for the scope if the
     * order of the two checks ever inverted. This way the only thing that can
     * refuse it is the scope.
     */
    private Response mcpItemWrite(String scope) {
        return call("dispatch_add_correction", Map.of(
            "address", addressIn(scope),
            "fields", Map.of("title", "a correction", "text", "the correction's body")));
    }

    private Response call(String tool, Map<String, Object> arguments) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", 1);
        envelope.put("method", "tools/call");
        envelope.put("params", Map.of("name", tool, "arguments", arguments));

        return given().contentType(ContentType.JSON).body(envelope).when().post("/mcp");
    }

    // =======================================================================
    // Comparing two refusals
    // =======================================================================

    /**
     * Two REST refusals that must agree in the envelope, whatever the
     * transport around them did.
     *
     * <p>Compared in {@code reason} and {@code message} and in the presence of
     * {@code data}, which is the whole of what DEC-0042 fixes. The status is
     * compared too: a difference there would separate the two below the
     * envelope, where a caller branching on the code would not see it.
     */
    private static void assertSameRestRefusal(Response one, Response other, String why) {
        assertThat(other.statusCode()).as(why).isEqualTo(one.statusCode());
        assertThat(other.jsonPath().getString("reason")).as(why)
            .isEqualTo(one.jsonPath().getString("reason"));
        assertThat(other.jsonPath().getString("message")).as(why)
            .isEqualTo(one.jsonPath().getString("message"));
        assertThat(other.jsonPath().get("data") == null).as(why)
            .isEqualTo(one.jsonPath().get("data") == null);
    }

    /**
     * The same comparison on the process verbs.
     *
     * <p>There is no status to compare: a refusal travels here as an error
     * result inside a 200, so {@code isError} is the member that carries what
     * the status carries on REST — and it is compared for exactly that
     * reason.
     */
    private static void assertSameMcpRefusal(Response one, Response other, String why) {
        assertThat(isError(other)).as(why).isEqualTo(isError(one));
        assertThat(isError(one)).as("both answers must actually be refusals").isTrue();
        assertThat(reason(other)).as(why).isEqualTo(reason(one));
        assertThat(message(other)).as(why).isEqualTo(message(one));
        assertThat(dataOf(other) == null).as(why).isEqualTo(dataOf(one) == null);
    }

    /**
     * The same comparison, for a refusal whose pattern names the call.
     *
     * <p>{@code SCOPE_READ_ONLY} names what was attempted, which section 4.2
     * requires: the message tells the caller which of its calls was the write,
     * and two address forms are two different calls. So the messages differ,
     * and they may differ in exactly one way — the first word. Everything
     * after it is compared, which is what keeps this from being a comparison
     * that passes on anything.
     *
     * <p>The alternative, dropping the message from the comparison entirely,
     * would have let the two forms word the same refusal differently and said
     * nothing about it.
     */
    private static void assertSameRestRefusalBarTheCall(Response one, Response other,
                                                        String why) {
        assertThat(other.statusCode()).as(why).isEqualTo(one.statusCode());
        assertThat(other.jsonPath().getString("reason")).as(why)
            .isEqualTo(one.jsonPath().getString("reason"));
        assertThat(other.jsonPath().get("data") == null).as(why)
            .isEqualTo(one.jsonPath().get("data") == null);
        assertThat(afterTheCall(other.jsonPath().getString("message"))).as(why)
            .isEqualTo(afterTheCall(one.jsonPath().getString("message")));
    }

    /** The same, on the process verbs. */
    private static void assertSameMcpRefusalBarTheCall(Response one, Response other,
                                                       String why) {
        assertThat(isError(one)).as("both answers must actually be refusals").isTrue();
        assertThat(isError(other)).as(why).isTrue();
        assertThat(reason(other)).as(why).isEqualTo(reason(one));
        assertThat(dataOf(other) == null).as(why).isEqualTo(dataOf(one) == null);
        assertThat(afterTheCall(message(other))).as(why)
            .isEqualTo(afterTheCall(message(one)));
    }

    /**
     * A message with its leading call name removed.
     *
     * <p>The patterns that name a call put it first and follow it with a
     * space, so the split is the pattern's own shape rather than a guess. A
     * message that does not begin with a call is returned whole, which makes
     * this safe to call on any of them.
     */
    private static String afterTheCall(String message) {
        int firstSpace = message == null ? -1 : message.indexOf(' ');
        return firstSpace < 0 ? message : message.substring(firstSpace + 1);
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
