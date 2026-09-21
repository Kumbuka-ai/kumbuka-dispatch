package ai.kumbuka.dispatch.contract;

import ai.kumbuka.dispatch.surface.SurfaceFixture;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Section 3's idempotency rule, against a running service and a running
 * database.
 *
 * <blockquote>A call that takes {@code idempotency_key} and is repeated by the
 * same caller in the same scope with the same key, within 24 hours of the
 * first, creates nothing and answers with the answer the first call produced,
 * in the object's current state. The same key with different arguments is
 * refused as {@code IDEMPOTENCY_KEY_REUSED}.</blockquote>
 *
 * <p>Until 2026-09-19 the argument was declared, accepted and discarded: a
 * retried commission commissioned twice while the tool description promised it
 * would not. That is the defect class the whole assistant surface exists to
 * remove, sitting inside its own remedy — which is why this probe exists at
 * all and why it runs against the real ledger table under the real role rather
 * than against a mock. A mocked suite once stayed green while a missing grant
 * returned 500 in production, and V14 adds a relation with a new grant.
 *
 * <p>Three outcomes, three probes, because they are three different promises
 * and any two of them can hold while the third does not.
 *
 * <p>The correction half of the rule is probed in {@code
 * CurationAndCorrectionIT} rather than here, and not by preference: a
 * correction has no standing of its own and this surface has no read path to
 * one, so the only way to see whether two arrived is to ask the store.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@Tag("TST-0011")
class IdempotentCallsIT {

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asConsole(identity);
    }

    /**
     * The same key, the same call: nothing is created and the answer is the
     * first one's exchange.
     *
     * <p>The count is what makes this a probe about creation rather than about
     * the answer: two calls could both return the first address AND have
     * created a second exchange nobody was told about. So the listing is read
     * before and after, and the difference must be exactly one.
     */
    @Test
    void a_repeat_under_the_same_key_creates_nothing_and_answers_the_same_address() {
        int before = countOfExchanges();

        String first = addressOf(commissionWith("a-key-i-chose", "a commission"));
        String second = addressOf(commissionWith("a-key-i-chose", "a commission"));

        assertThat(second)
            .as("section 3: the repeat answers with the answer the first call produced")
            .isEqualTo(first);
        assertThat(countOfExchanges() - before)
            .as("and creates nothing. Before 2026-09-19 the key was accepted and "
                + "discarded, so this difference was two")
            .isEqualTo(1);
    }

    /**
     * The answer is the object as it stands NOW, not a replay of the first
     * answer.
     *
     * <p>"in the object's current state" is the contract's own wording, and it
     * is why the ledger stores a durable identity rather than a projection. A
     * stored answer would be wrong the moment anything happened to the
     * exchange — here, the moment it is taken up.
     */
    @Test
    void the_repeat_answers_the_object_as_it_stands_now() {
        String address = addressOf(commissionWith("a-key-for-the-state", "a commission"));

        SurfaceFixture.asExecutor(identity);
        call("dispatch_take", Map.of("address", address, "duration", "PT1H"));
        SurfaceFixture.asConsole(identity);

        Response repeat = commissionWith("a-key-for-the-state", "a commission");

        assertThat(repeat.jsonPath().getString("result.structuredContent.fields.state"))
            .as("the exchange was taken up between the two calls, and the repeat says so. "
                + "A stored projection would still answer 'open'")
            .isEqualTo("active");
    }

    /**
     * The same key with different arguments is refused, and nothing is
     * created.
     *
     * <p>The outcome that is easy to leave out and expensive to leave out: a
     * caller whose loop variable did not advance would otherwise be told its
     * second, different commission succeeded and handed the first one's
     * address, and would find out much later by reading what it thought it had
     * written.
     */
    @Test
    void the_same_key_on_a_different_call_is_refused_and_writes_nothing() {
        commissionWith("a-key-spent-once", "the commission I meant");
        int before = countOfExchanges();

        Response refused = commissionWith("a-key-spent-once", "an entirely different one");

        assertThat(refused.jsonPath().getString("result.structuredContent.reason"))
            .isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(refused.jsonPath().getString("result.structuredContent.message"))
            .as("and the message names the key, the call and the scope, so the caller can "
                + "tell which of its keys it spent")
            .contains("a-key-spent-once")
            .contains("dispatch_commission")
            .contains(SurfaceFixture.SCOPE);
        assertThat(countOfExchanges())
            .as("nothing was created: a refusal that had written would be the worst of "
                + "both outcomes")
            .isEqualTo(before);
    }

    /**
     * A key is the caller's own; another caller may choose the same one.
     *
     * <p>Section 3 scopes the memory "per caller and per scope". Without the
     * caller in the key, one apparatus's retry-protection would silently
     * swallow another's genuinely new commission — a cross-caller data loss
     * with no error anywhere.
     */
    @Test
    void another_caller_spending_the_same_key_is_not_a_repeat() {
        String mine = addressOf(commissionWith("a-key-we-both-like", "mine"));

        SurfaceFixture.asOtherConsole(identity);
        String theirs = addressOf(commissionWith("a-key-we-both-like", "theirs"));

        assertThat(theirs)
            .as("the ledger is per caller: another caller's key of the same name is "
                + "another key")
            .isNotEqualTo(mine);
    }

    // =======================================================================
    // Driving the surface
    // =======================================================================

    private Response commissionWith(String key, String title) {
        Map<String, Object> arguments = new LinkedHashMap<>(Map.of(
            "scope", SurfaceFixture.SCOPE,
            "selector", SurfaceFixture.SELECTOR,
            "fields", Map.of("title", title, "apparatus", "code",
                "text", "the body of " + title)));
        if (key != null) {
            arguments.put("idempotency_key", key);
        }
        return call("dispatch_commission", arguments);
    }

    /** How many exchanges the selector holds right now. */
    private int countOfExchanges() {
        return call("dispatch_query", Map.of(
            "scope", SurfaceFixture.SCOPE, "selector", SurfaceFixture.SELECTOR))
            .jsonPath().getList("result.structuredContent.exchanges").size();
    }

    private static String addressOf(Response answer) {
        return answer.jsonPath().getString("result.structuredContent.address");
    }

    private Response call(String tool, Map<String, Object> arguments) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", 1);
        envelope.put("method", "tools/call");
        envelope.put("params", Map.of("name", tool, "arguments", arguments));
        return given().contentType(ContentType.JSON).body(envelope).when().post("/mcp");
    }
}
