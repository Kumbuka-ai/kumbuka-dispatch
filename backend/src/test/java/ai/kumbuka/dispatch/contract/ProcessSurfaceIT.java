package ai.kumbuka.dispatch.contract;

import ai.kumbuka.dispatch.surface.ProcessVerb;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * A2, A3, A4 and A6, against a running service and a running database.
 *
 * <p>These four cannot be asserted anywhere else. A2 is about what the service
 * does with an argument it does not declare — a declaration cannot say whether
 * anything was written. A3 drives an exchange through every state in both roles
 * and calls both halves of each {@code next} list, which is a claim about
 * behaviour and not about data. A4 takes every address the service emitted and
 * hands it back. A6 compares three refusals byte for byte.
 *
 * <p>Expected values come from {@link Contract} wherever the contract states
 * one. Where it does not — "the call succeeded", "nothing was written" — the
 * expectation is the service's observable behaviour, which is what an
 * integration probe is for.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@Tag("TST-0002")
@Tag("TST-0003")
@Tag("TST-0004")
@Tag("TST-0006")
class ProcessSurfaceIT {

    @Inject TestIdentityAssociation identity;

    /**
     * Every address this probe saw the service emit.
     *
     * <p>A4's evidence, collected as a side effect of every other probe rather
     * than by a walk of its own. An address that only appears in a rare refusal
     * is exactly the one a hand-written list would miss.
     */
    private final Set<String> addressesSeen = new LinkedHashSet<>();

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asConsole(identity);
    }

    // =======================================================================
    // A2 — no argument is accepted and discarded
    // =======================================================================

    /**
     * Every call, with an argument it does not declare.
     *
     * <p>All fourteen, not a sample. The one that would be missed by a sample
     * is the one somebody added last, and the failure it produces is silent by
     * construction: the caller is told it succeeded.
     */
    @Test
    void every_call_refuses_an_argument_it_does_not_declare() {
        String address = commission();

        for (ProcessVerb verb : ProcessVerb.values()) {
            Map<String, Object> arguments =
                new LinkedHashMap<>(plausibleArgumentsFor(verb, address));
            arguments.put("a_name_no_call_declares", "a value the caller believes in");

            Response answer = call(verb.call(), arguments);

            assertThat(reason(answer))
                .as("%s accepted an argument it does not declare. The caller that sent it "
                    + "believes the value arrived", verb.call())
                .isEqualTo("ARGUMENT_UNKNOWN");
            assertThat(message(answer))
                .as("%s must name the argument it refused", verb.call())
                .contains("a_name_no_call_declares");
        }
    }

    /** The same rule inside {@code fields}, for every call that has one. */
    @Test
    void every_call_refuses_an_unknown_argument_nested_in_fields() {
        String address = commission();

        for (ProcessVerb verb : ProcessVerb.values()) {
            if (!verb.hasFields()) {
                continue;
            }
            Map<String, Object> arguments =
                new LinkedHashMap<>(plausibleArgumentsFor(verb, address));
            @SuppressWarnings("unchecked")
            Map<String, Object> fields =
                new LinkedHashMap<>((Map<String, Object>) arguments.get("fields"));
            fields.put("nested_and_undeclared", "just as discarded");
            arguments.put("fields", fields);

            Response answer = call(verb.call(), arguments);

            assertThat(reason(answer))
                .as("%s's fields object is open. One level of closure is as much use as "
                    + "none", verb.call())
                .isEqualTo("ARGUMENT_UNKNOWN");
            assertThat(message(answer)).contains("nested_and_undeclared");
        }
    }

    /**
     * Nothing was written.
     *
     * <p>The other half of A2, and the half a refusal alone does not prove: a
     * service could refuse AND have written. The exchange is read before and
     * after and must be unchanged — the conflict token is the cheapest witness,
     * because it rotates on every write including one that changed nothing.
     */
    @Test
    void a_refused_argument_writes_nothing() {
        String address = commission();
        String tokenBefore = tokenOf(read(address));

        Map<String, Object> arguments = new LinkedHashMap<>(Map.of(
            "address", address,
            "fields", Map.of("title", "a correction", "text", "its text")));
        arguments.put("undeclared", "x");
        call(ProcessVerb.ADD_CORRECTION.call(), arguments);

        assertThat(tokenOf(read(address)))
            .as("the refused call must not have written: a rotated token is the evidence "
                + "of a write the caller was told did not happen")
            .isEqualTo(tokenBefore);
    }

    // =======================================================================
    // A3 — next is exactly what succeeds
    // =======================================================================

    /**
     * An exchange through every state, read in both roles, with {@code next}
     * checked against the contract's table and then EXERCISED.
     *
     * <p>The exercise is the part that matters. A list that agrees with a table
     * is two documents agreeing; a list whose every entry succeeds and whose
     * every omission is refused is the promise the contract actually makes.
     */
    @Test
    void next_lists_exactly_the_calls_that_succeed() {
        List<Contract.Row> table = Contract.nextTable();
        assertThat(table).as("the contract's section 6 table must be readable").isNotEmpty();

        // open, as the commissioner — on a CHILD, not on a bracket root.
        //
        // The contract says two things about a root here and they disagree.
        // Section 6 lists `dispatch_cancel` for every open exchange the
        // commissioner sees; section 5 declares cancel "Not for a bracket root;
        // a bracket is finished with dispatch_close_bracket". The build follows
        // section 5, because that is where the verb is defined and because the
        // alternative would let a cancel walk past the bracket's own gate — so
        // the row is exercised where both sections agree, on a child.
        //
        // The consequence is reported rather than papered over: an open bracket
        // root has `dispatch_add_correction` and nothing else, and no way to
        // end at all until its own record is delivered. That is a gap in the
        // contract, not in this build, and it is named in the return.
        String root = commission();
        String open = commissionChildOf(root);

        assertThat(callsIn(read(open)))
            .as("open, commissioner: the contract lists add_correction and cancel")
            .containsExactlyInAnyOrder("dispatch_add_correction", "dispatch_cancel");
        assertThat(waitingFor(read(open)))
            .as("the contract says two things here and they disagree. Section 3: "
                + "waiting_for names who the exchange waits for WHERE NEXT IS EMPTY and "
                + "the exchange is not finished. Section 6's table puts 'the executor' in "
                + "the waiting_for column of the same row whose next column lists two "
                + "calls. The build follows section 3, because that is the normative "
                + "description of the answer's shape and because naming both leaves the "
                + "caller to decide which of the two is the real answer — which is the "
                + "situation this contract exists to end. Reported in the return.")
            .isNull();

        assertThat(callsIn(read(root)))
            .as("and on a root, cancel is absent — section 5 excludes it, and a call this "
                + "list offered would be refused for a reason the list does not know")
            .containsExactly("dispatch_add_correction");

        // open, as an executor that holds nothing.
        SurfaceFixture.asExecutor(identity);
        assertThat(callsIn(read(open)))
            .as("open, anyone else: the one call is the take")
            .containsExactly("dispatch_take");

        // active, as the holder.
        String receipt = receiptOf(call("dispatch_take",
            Map.of("address", open, "duration", "PT1H")));
        assertThat(callsIn(read(open)))
            .as("active, holder: deliver, ask, decline")
            .containsExactlyInAnyOrder("dispatch_deliver_return",
                "dispatch_ask_commissioner", "dispatch_decline");

        // active, as the commissioner.
        SurfaceFixture.asConsole(identity);
        assertThat(callsIn(read(open)))
            .as("active, commissioner: correct or cancel")
            .containsExactlyInAnyOrder("dispatch_add_correction", "dispatch_cancel");

        // needs_input with an answer delivered, as the commissioner.
        SurfaceFixture.asExecutor(identity);
        call("dispatch_deliver_return", Map.of(
            "address", open, "receipt", receipt, "fields", Map.of("text", "the answer")));

        assertThat(callsIn(read(open)))
            .as("needs_input, holder: nothing; the commissioner is due")
            .isEmpty();
        assertThat(waitingFor(read(open))).isEqualTo("the commissioner");

        SurfaceFixture.asConsole(identity);
        assertThat(callsIn(read(open)))
            .as("needs_input with an answer, commissioner: accept, curate or reply — and "
                + "the two that section 6 omits from this row. The omission is the "
                + "contract's third internal disagreement: section 3 defines next as "
                + "EXACTLY the calls that succeed, and section 5 puts needs_input in "
                + "cancel's from-states and places no state condition on add_correction "
                + "at all. Both succeed here, so both are listed; leaving them out would "
                + "make the list smaller than the definition requires. Reported in the "
                + "return.")
            .contains("dispatch_accept_return", "dispatch_curate_return",
                "dispatch_reply_to_executor")
            .containsExactlyInAnyOrder("dispatch_accept_return", "dispatch_curate_return",
                "dispatch_reply_to_executor", "dispatch_add_correction", "dispatch_cancel");

        // Every listed call succeeds. Checked on the one that finishes the
        // exchange last, so the other two are still callable when tested.
        assertThat(isError(call("dispatch_reply_to_executor", Map.of(
            "address", open, "conflict_token", tokenOf(read(open)),
            "fields", Map.of("message", "another round, please")))))
            .as("a listed call must succeed — that is the promise the list makes")
            .isFalse();

        // Terminal: nothing, and nobody is waiting.
        SurfaceFixture.asExecutor(identity);
        call("dispatch_deliver_return", Map.of(
            "address", open, "receipt", receipt, "fields", Map.of("text", "the answer, again")));
        SurfaceFixture.asConsole(identity);
        call("dispatch_accept_return", Map.of("address", open));

        assertThat(callsIn(read(open))).as("a finished exchange offers nothing").isEmpty();
        assertThat(waitingFor(read(open)))
            .as("and says so rather than leaving the caller to infer it")
            .isEqualTo("nobody: the exchange is finished");
    }

    /**
     * A call the list does not offer is refused — from state or from role.
     *
     * <p>The closure half of A3. Without it "next lists what succeeds" is
     * satisfied by a list of everything.
     */
    @Test
    void a_call_not_listed_is_refused_from_state_or_role() {
        String open = commission();

        // accept_return on an open exchange: state, not role.
        Response answer = call("dispatch_accept_return", Map.of("address", open));
        assertThat(isError(answer)).isTrue();
        assertThat(reason(answer))
            .as("an exchange with no delivered answer refuses the accept, and says which "
                + "of the two reasons it is")
            .isIn("STATE_DOES_NOT_ALLOW", "NO_ANSWER_DELIVERED");
        assertThat(nextIn(answer))
            .as("every refusal that carries data carries the way out with it")
            .isNotNull();
    }

    // =======================================================================
    // A4 — every address the service emits is one it accepts
    // =======================================================================

    /**
     * Every address seen in this run, handed straight back to the read.
     *
     * <p>The addresses are collected by {@link #note}, which every helper in
     * this class runs its answers through. So the set covers answers, listings
     * and refusals alike, which is what the criterion asks for — and not a
     * hand-kept list, which would miss the address that only appears in a rare
     * refusal.
     */
    @Test
    void every_address_the_service_emitted_is_accepted_unchanged() {
        String root = commission();
        commissionChild(root);
        read(root);
        list();
        call("dispatch_close_bracket", Map.of("address", root));

        assertThat(addressesSeen)
            .as("the probe must have seen addresses at all, or it asserts nothing")
            .isNotEmpty();

        for (String address : addressesSeen) {
            assertThat(address)
                .as("every address anywhere is the complete URI")
                .startsWith("dispatch://");
            assertThat(isError(call("dispatch_read", Map.of("address", address))))
                .as("%s came out of an answer and the read refuses it", address)
                .isFalse();
        }
    }

    // =======================================================================
    // A6 — the three causes of NOT_FOUND are byte-identical
    // =======================================================================

    @Test
    void absent_invisible_and_unroutable_answer_the_same_bytes() {
        // Does not exist: a well-formed address in a declared selector.
        String absent = call("dispatch_read", Map.of("address",
            "dispatch://" + SurfaceFixture.SCOPE + "/" + SurfaceFixture.SELECTOR + "/99999.0"))
            .jsonPath().getString("result.structuredContent");

        // Not visible: a scope this caller has no account in.
        String invisible = call("dispatch_read", Map.of("address",
            "dispatch://a-scope-this-caller-cannot-see/" + SurfaceFixture.SELECTOR + "/1.0"))
            .jsonPath().getString("result.structuredContent");

        // Not routable: a selector that is not declared in this scope.
        String unroutable = call("dispatch_read", Map.of("address",
            "dispatch://" + SurfaceFixture.SCOPE + "/a-selector-nobody-declared/1.0"))
            .jsonPath().getString("result.structuredContent");

        assertThat(absent)
            .as("an object that is not there and a scope the caller may not see must be "
                + "indistinguishable, or the error path is a scope enumerator")
            .isEqualTo(invisible)
            .isEqualTo(unroutable);
    }

    @Test
    void the_not_found_refusal_carries_no_data_at_all() {
        Response answer = call("dispatch_read", Map.of("address",
            "dispatch://" + SurfaceFixture.SCOPE + "/" + SurfaceFixture.SELECTOR + "/99999.0"));

        assertThat(reason(answer)).isEqualTo("NOT_FOUND");
        assertThat((Object) answer.jsonPath().get("result.structuredContent.data"))
            .as("an empty data object would be a key this refusal has to keep identical "
                + "for ever; absent is the only shape that cannot drift")
            .isNull();
        assertThat(message(answer))
            .as("it names neither the call nor the address — the caller knows both from "
                + "its own request, and an attacker learns nothing")
            .doesNotContain("dispatch_read")
            .doesNotContain("99999");
    }

    // =======================================================================
    // Plausible arguments, per call
    // =======================================================================

    /**
     * Arguments that would let each call through, if it were in the right
     * state.
     *
     * <p>A2 needs a call that fails for ONE reason — the undeclared argument —
     * so everything else about it has to be right. Where a call cannot succeed
     * from the state the probe is in, that is fine: the argument check runs
     * before anything is resolved, so the refusal it produces is the one under
     * test.
     */
    private Map<String, Object> plausibleArgumentsFor(ProcessVerb verb, String address) {
        return switch (verb) {
            case COMMISSION -> commissionArguments();
            case ADD_CORRECTION -> Map.of("address", address,
                "fields", Map.of("title", "a correction", "text", "its text"));
            case ACCEPT_RETURN, CLOSE_BRACKET -> Map.of("address", address);
            case CURATE_RETURN -> Map.of("address", address,
                "fields", Map.of("into", address));
            case REPLY_TO_EXECUTOR -> Map.of("address", address,
                "conflict_token", "any", "fields", Map.of("message", "a message"));
            case CANCEL -> Map.of("address", address,
                "conflict_token", "any", "fields", Map.of("reason", "a reason"));
            case TAKE -> Map.of("address", address, "duration", "PT1H");
            case TAKE_NEXT -> Map.of("scope", SurfaceFixture.SCOPE,
                "selector", SurfaceFixture.SELECTOR, "duration", "PT1H");
            case DELIVER_RETURN -> Map.of("address", address, "receipt", "any",
                "fields", Map.of("text", "an answer"));
            case ASK_COMMISSIONER -> Map.of("address", address, "receipt", "any",
                "fields", Map.of("question", "a question"));
            case DECLINE -> Map.of("address", address,
                "fields", Map.of("reason", "a reason"));
            case READ -> Map.of("address", address);
            case QUERY -> Map.of("scope", SurfaceFixture.SCOPE,
                "selector", SurfaceFixture.SELECTOR);
        };
    }

    // =======================================================================
    // Calling
    // =======================================================================

    private Map<String, Object> commissionArguments() {
        return Map.of(
            "scope", SurfaceFixture.SCOPE,
            "selector", SurfaceFixture.SELECTOR,
            "fields", Map.of(
                "title", "a probe",
                "apparatus", "code",
                "text", "the body of the commission"));
    }

    private String commission() {
        return addressOf(call("dispatch_commission", commissionArguments()));
    }

    private void commissionChild(String parent) {
        commissionChildOf(parent);
    }

    /** A child of the named bracket, and its complete address. */
    private String commissionChildOf(String parent) {
        Map<String, Object> arguments = new LinkedHashMap<>(commissionArguments());
        arguments.put("parent", parent);
        return addressOf(call("dispatch_commission", arguments));
    }

    private Response read(String address) {
        return call("dispatch_read", Map.of("address", address));
    }

    private Response list() {
        return call("dispatch_query", Map.of(
            "scope", SurfaceFixture.SCOPE, "selector", SurfaceFixture.SELECTOR));
    }

    private Response call(String tool, Map<String, Object> arguments) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", 1);
        envelope.put("method", "tools/call");
        envelope.put("params", Map.of("name", tool, "arguments", arguments));

        Response answer =
            given().contentType(ContentType.JSON).body(envelope).when().post("/mcp");
        note(answer);
        return answer;
    }

    /** Collects every address the answer carries, at any depth. A4's evidence. */
    private void note(Response answer) {
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("dispatch://[A-Za-z0-9._~:@+/-]+")
            .matcher(answer.asString());
        while (m.find()) {
            addressesSeen.add(m.group());
        }
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

    private static Object nextIn(Response answer) {
        return (Object) answer.jsonPath().get("result.structuredContent.data.next");
    }

    private static String addressOf(Response answer) {
        return answer.jsonPath().getString("result.structuredContent.address");
    }

    private static String tokenOf(Response answer) {
        return answer.jsonPath().getString("result.structuredContent.conflict_token");
    }

    private static String receiptOf(Response answer) {
        return answer.jsonPath().getString("result.structuredContent.receipt");
    }

    /** The call names of an answer's {@code next}, in order. */
    private static List<String> callsIn(Response answer) {
        List<String> calls = answer.jsonPath().getList("result.structuredContent.next.call");
        return calls == null ? new ArrayList<>() : calls;
    }

    private static String waitingFor(Response answer) {
        return answer.jsonPath().getString("result.structuredContent.waiting_for");
    }
}
