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
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A10: the four defects measured on 2026-09-18, each asserted as repaired.
 *
 * <p>Every probe here was RED before this repair and is green after it. That
 * is the whole reason the class exists as its own file rather than as four
 * methods spread over the suites that own the verbs: a defect that was
 * measured in the field is evidence, and evidence kept together can be re-run
 * as a set the next time somebody asks whether the surface still behaves.
 *
 * <p>The four, in the wording of the measurement:
 * <ol>
 *   <li>{@code create} with {@code draft} in its body reported success and
 *       discarded the text.</li>
 *   <li>{@code claim} on a draft answered {@code cannot takeup} — a kernel
 *       name, and the way in rather than the way out.</li>
 *   <li>{@code create} and {@code claim} answered short addresses
 *       ({@code satellite/26.2}).</li>
 *   <li>The blocking-children list travelled under {@code offenders} as prose,
 *       with short addresses.</li>
 * </ol>
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@Tag("TST-0010")
class MeasuredDefectsIT {

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asConsole(identity);
    }

    // =======================================================================
    // Defect 1 — an argument accepted and discarded
    // =======================================================================

    /**
     * The measured call: {@code create} carrying {@code draft}. The service
     * answered success and dropped the text, so the caller believed it had
     * commissioned work that in fact had an empty body.
     */
    @Test
    void an_argument_the_call_does_not_declare_is_refused_by_name() {
        Map<String, Object> arguments = new LinkedHashMap<>(commissionArguments());
        arguments.put("draft", "the text the caller believed it was sending");

        Response answer = call("dispatch_commission", arguments);

        assertThat(isError(answer))
            .as("an argument the call does not declare is refused, never accepted and "
                + "discarded: the caller that sent it believes the text arrived")
            .isTrue();
        assertThat(reason(answer)).isEqualTo("ARGUMENT_UNKNOWN");
        assertThat(message(answer))
            .as("the refusal names the argument, so the caller can correct it")
            .contains("draft");
    }

    /** The same rule one level down: an argument nested inside {@code fields}. */
    @Test
    void an_unknown_argument_nested_in_fields_is_refused_by_name() {
        Map<String, Object> arguments = new LinkedHashMap<>(commissionArguments());
        @SuppressWarnings("unchecked")
        Map<String, Object> fields =
            new LinkedHashMap<>((Map<String, Object>) arguments.get("fields"));
        fields.put("draft", "nested, and just as discarded");
        arguments.put("fields", fields);

        Response answer = call("dispatch_commission", arguments);

        assertThat(isError(answer)).isTrue();
        assertThat(reason(answer)).isEqualTo("ARGUMENT_UNKNOWN");
        assertThat(message(answer)).contains("draft");
    }

    // =======================================================================
    // Defect 2 — a refusal naming the kernel and the way in
    // =======================================================================

    /**
     * The measured refusal read {@code satellite/26.2 is draft and cannot
     * takeup; active is reachable only from [open]} — three faults at once:
     * the kernel name {@code takeup} for a call the caller made as
     * {@code claim}, the state {@code draft} which this surface does not have,
     * and a statement of what reaches {@code active} rather than of what the
     * caller can do next.
     *
     * <p>After the repair there is no draft to take up at all: commissioning
     * is atomic, so the exchange is {@code open} and the take succeeds. What
     * is asserted is the surface's side of it — a refusal, when one comes,
     * carries the caller's own name for the call and the way out.
     */
    @Test
    void a_refusal_names_the_call_the_caller_made_and_the_way_out() {
        String address = commission();
        take(address);

        // Taking up an exchange that is already active: a real refusal, from
        // a state the caller can see.
        Response answer =
            call("dispatch_take", Map.of("address", address, "duration", "PT1H"));

        assertThat(isError(answer)).isTrue();
        assertThat(message(answer))
            .as("the refusal names the call under the name the caller used")
            .contains("dispatch_take");
        assertThat(message(answer))
            .as("no kernel name ever reaches a caller")
            .doesNotContain("takeup")
            .doesNotContain("ratify")
            .doesNotContain("reject");
        assertThat(nextOf(answer))
            .as("a refusal that carries data carries the way out with it")
            .isNotNull();
    }

    // =======================================================================
    // Defect 3 — short addresses
    // =======================================================================

    /**
     * The measured answers carried {@code satellite/26.2}, which no call of
     * this surface accepts. A caller that reads an address out of an answer
     * and puts it into the next call must not have to repair it first.
     */
    @Test
    void every_address_an_answer_carries_is_complete() {
        String commissioned = addressOf(call("dispatch_commission", commissionArguments()));
        assertThat(commissioned)
            .as("the address a commission answers with is the one dispatch_read takes")
            .startsWith("dispatch://");

        Response taken =
            call("dispatch_take", Map.of("address", commissioned, "duration", "PT1H"));
        assertThat(addressOf(taken)).startsWith("dispatch://");

        // The round trip is the point: the address is handed straight back.
        assertThat(isError(call("dispatch_read", Map.of("address", commissioned))))
            .as("an address taken from an answer is accepted unchanged by the read")
            .isFalse();
    }

    // =======================================================================
    // Defect 4 — the blocking list beside the data, in short form
    // =======================================================================

    /**
     * The measured refusal carried {@code offenders: ["satellite/26.1
     * (draft)"]} — a member of its own rather than {@code data}, prose rather
     * than structure, and short addresses a caller cannot act on.
     */
    @Test
    void the_blocking_children_travel_under_data_as_structure() {
        String root = commission();
        commissionChild(root);

        Response answer = call("dispatch_close_bracket", Map.of("address", root));

        assertThat(isError(answer)).isTrue();
        assertThat(reason(answer)).isEqualTo("CHILDREN_NOT_FINISHED");
        assertThat((Object) answer.jsonPath().get("result.structuredContent.offenders"))
            .as("lists of objects travel in data, never in a member of their own")
            .isNull();

        List<Map<String, Object>> offenders =
            answer.jsonPath().getList("result.structuredContent.data.offenders");
        assertThat(offenders).as("the refusal names each unfinished child").isNotEmpty();
        assertThat(offenders.get(0).get("address").toString())
            .as("each blocking child is named by its complete address")
            .startsWith("dispatch://");
        assertThat(offenders.get(0))
            .as("each offender carries the state it is in and the call that would finish it")
            .containsKeys("address", "state", "next");
    }

    // =======================================================================
    // Calling
    // =======================================================================

    private Map<String, Object> commissionArguments() {
        return Map.of(
            "scope", SurfaceFixture.SCOPE,
            "selector", SurfaceFixture.SELECTOR,
            "fields", Map.of(
                "title", "a measured probe",
                "apparatus", "code",
                "text", "the body of the commission"));
    }

    private String commission() {
        return addressOf(call("dispatch_commission", commissionArguments()));
    }

    private void commissionChild(String parent) {
        Map<String, Object> arguments = new LinkedHashMap<>(commissionArguments());
        arguments.put("parent", parent);
        call("dispatch_commission", arguments);
    }

    private void take(String address) {
        call("dispatch_take", Map.of("address", address, "duration", "PT1H"));
    }

    private Response call(String tool, Map<String, Object> arguments) {
        return rpc("tools/call", Map.of("name", tool, "arguments", arguments));
    }

    private Response rpc(String method, Map<String, Object> params) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", 1);
        envelope.put("method", method);
        envelope.put("params", params);
        return given().contentType(ContentType.JSON).body(envelope).when().post("/mcp");
    }

    private static boolean isError(Response answer) {
        return Boolean.TRUE.equals(answer.jsonPath().getBoolean("result.isError"));
    }

    private static String reason(Response answer) {
        return answer.jsonPath().getString("result.structuredContent.reason");
    }

    private static String message(Response answer) {
        return String.valueOf(answer.jsonPath().getString("result.structuredContent.message"));
    }

    private static Object nextOf(Response answer) {
        return (Object) answer.jsonPath().get("result.structuredContent.data.next");
    }

    private static String addressOf(Response answer) {
        return answer.jsonPath().getString("result.structuredContent.address");
    }
}
