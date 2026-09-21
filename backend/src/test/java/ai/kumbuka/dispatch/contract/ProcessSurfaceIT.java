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
import java.util.function.Supplier;

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
    // A3 — next is exactly what succeeds, read from the contract's own table
    // =======================================================================

    /**
     * One row of section 6, made real: an exchange in that state, read by a
     * caller taking that part.
     *
     * <p>The builder returns a fresh exchange every time it is called, which is
     * what lets the second half of A3 exercise each listed call on its own copy
     * of the state. Calling them all on one exchange would mean the first one
     * that ends it makes the rest untestable — which is how the predecessor
     * came to assert five calls and exercise two.
     *
     * @param state   the row's first column
     * @param caller  the row's second column, as this probe names the part
     * @param build   produces the exchange in that state and returns its address
     * @param become  puts the identity into the part the row describes
     * @param root    whether the exchange built is a bracket root
     * @param childrenFinished whether every child of that bracket is terminal
     */
    private record Situation(String state, String caller, Supplier<String> build,
                             Runnable become, boolean root, boolean childrenFinished) {
    }

    /**
     * Every row of section 6 this service can put a caller into, and the rows
     * it cannot.
     *
     * <p><strong>The unreachable rows are named rather than skipped.</strong>
     * Section 2 separates the candidate from the bystander, and this service
     * decides the commissioner from the realm role — so every identity that can
     * see an open exchange is either a console identity (commissioner) or an
     * executor (candidate), and no caller is a bystander at an OPEN exchange.
     * The row exists in the contract and cannot be exercised here; that is a
     * finding about the open point in section 8, not a gap in the probe, and
     * {@link #the_contract_rows_this_service_cannot_reach_are_exactly_these}
     * goes red if the set ever changes.
     */
    private List<Situation> situations() {
        return List.of(
            new Situation("open", "commissioner",
                this::commissionChildOfAFreshBracket, this::asCommissioner, false, true),
            new Situation("open", "candidate",
                this::commissionChildOfAFreshBracket, this::asCandidate, false, true),

            new Situation("active", "holder",
                this::anActiveChild, this::asCandidate, false, true),
            new Situation("active", "commissioner",
                this::anActiveChild, this::asCommissioner, false, true),
            new Situation("active", "candidate or bystander",
                this::anActiveChild, this::asOtherExecutor, false, true),

            new Situation("needs_input, answer delivered", "commissioner",
                this::aChildWithAnAnswer, this::asCommissioner, false, true),
            new Situation("needs_input, question asked", "commissioner",
                this::aChildWithAQuestion, this::asCommissioner, false, true),
            new Situation("needs_input", "holder, candidate or bystander",
                this::aChildWithAnAnswer, this::asCandidate, false, true),

            new Situation("any terminal", "anyone",
                this::aClosedChild, this::asCommissioner, false, true));
    }

    /**
     * Every {@code next} the service answers with is the list its row names.
     *
     * <p>The expectation is read from {@link Contract#nextTable()} — the copied
     * contract document — and never from the service, its declaration or this
     * file. The predecessor asserted hardcoded literals while calling
     * {@code Contract.nextTable()} only to check it was non-empty, so the
     * document it named as its source could have changed under it without one
     * assertion moving.
     */
    @Test
    void next_is_the_list_the_contract_s_table_names() {
        List<Contract.Row> table = Contract.nextTable();
        assertThat(table).as("the contract's section 6 table must be readable").isNotEmpty();

        for (Situation situation : situations()) {
            Contract.Row row = rowFor(table, situation);
            String address = situation.build().get();
            situation.become().run();

            List<String> expected = situation.root()
                ? row.atRoot(situation.childrenFinished())
                : row.atChild();

            assertThat(callsIn(read(address)))
                .as("section 6, row '%s' / '%s': next is read from the contract's table "
                    + "and is what the service must offer", situation.state(),
                    situation.caller())
                .containsExactlyInAnyOrderElementsOf(expected);

            assertThat(waitingFor(read(address)))
                .as("section 3: waiting_for is present only where next is empty")
                .satisfies(waiting -> {
                    if (expected.isEmpty()) {
                        assertThat(waiting).isNotNull();
                    } else {
                        assertThat(waiting).isNull();
                    }
                });
        }
    }

    /**
     * Every call {@code next} lists succeeds — each on its own fresh copy of
     * the state.
     *
     * <p>This is the half of A3 that the list's promise actually rests on, and
     * it is the one the predecessor left mostly unexercised: in the row with
     * five listed calls it made two of them, and one of the three it skipped
     * was {@code dispatch_curate_return}, whose success path had never run at
     * all — the finding that made the new reference column and its query
     * untested.
     *
     * <p>A fresh exchange per call, because several of the listed calls are
     * terminal: made on one exchange, the first would take the rest out of the
     * state the row is about.
     */
    @Test
    void every_call_next_lists_succeeds_on_a_fresh_copy_of_its_state() {
        List<Contract.Row> table = Contract.nextTable();

        for (Situation situation : situations()) {
            Contract.Row row = rowFor(table, situation);
            List<String> listed = situation.root()
                ? row.atRoot(situation.childrenFinished())
                : row.atChild();

            for (String call : listed) {
                String address = situation.build().get();
                situation.become().run();

                Response answer = call(call, argumentsThatWouldWork(call, address));

                assertThat(isError(answer))
                    .as("section 3: every call next lists succeeds for this caller. "
                        + "Row '%s' / '%s' lists %s, and it was refused: %s",
                        situation.state(), situation.caller(), call, message(answer))
                    .isFalse();
            }
        }
    }

    /**
     * The rows of section 6 this service cannot put a caller into.
     *
     * <p>Stated as an assertion rather than left as a silence. Two rows name a
     * bystander at an exchange that is open or returned, and in this service
     * every identity that can see one is either the commissioner or a
     * candidate — a consequence of "every console identity may act as
     * commissioner", which section 8 records as open. When that narrows, this
     * probe goes red and the rows become exercisable.
     */
    @Test
    void the_contract_rows_this_service_cannot_reach_are_exactly_these() {
        List<String> reachable = situations().stream()
            .map(s -> s.state() + " / " + s.caller())
            .toList();

        List<String> unreachable = Contract.nextTable().stream()
            .map(r -> r.state() + " / " + r.caller())
            .filter(name -> !reachable.contains(name))
            .toList();

        assertThat(unreachable)
            .as("a row this probe cannot reach is reported, never skipped: a silent "
                + "omission reads as coverage")
            .containsExactlyInAnyOrder(
                "open / bystander",
                "returned / commissioner",
                "returned / anyone else");
    }

    /**
     * Arguments that make one call succeed against the exchange at
     * {@code address}.
     *
     * <p>Different from {@link #plausibleArgumentsFor}, which builds a call
     * that is right in every respect EXCEPT the one thing A2 is testing. These
     * have to actually work, so they carry the live conflict token and the
     * live receipt rather than the string "any".
     */
    private Map<String, Object> argumentsThatWouldWork(String call, String address) {
        return switch (call) {
            case "dispatch_add_correction" -> Map.of("address", address,
                "fields", Map.of("title", "a correction", "text", "its text"));
            case "dispatch_accept_return", "dispatch_close_bracket" ->
                Map.of("address", address);
            case "dispatch_curate_return" -> Map.of("address", address,
                "fields", Map.of("into", aCuratableTarget()));
            case "dispatch_reply_to_executor" -> Map.of("address", address,
                "conflict_token", tokenOf(read(address)),
                "fields", Map.of("message", "another round, please"));
            case "dispatch_cancel" -> Map.of("address", address,
                "conflict_token", tokenOf(read(address)),
                "fields", Map.of("reason", "no longer wanted"));
            case "dispatch_take" -> Map.of("address", address, "duration", "PT1H");
            case "dispatch_deliver_return" -> Map.of("address", address,
                "receipt", receiptHeld, "fields", Map.of("text", "the answer"));
            case "dispatch_ask_commissioner" -> Map.of("address", address,
                "receipt", receiptHeld, "fields", Map.of("question", "which of the two?"));
            case "dispatch_decline" -> declineArguments(address);
            default -> throw new IllegalStateException(
                call + " is listed in the contract's table and this probe does not know "
                    + "how to make it succeed. A call the probe cannot exercise is a call "
                    + "the promise is not checked for.");
        };
    }

    /**
     * A decline carries the receipt only where the caller holds the exchange.
     *
     * <p>Section 5.2's two cases in one call: a candidate declines an open
     * exchange without one, and the holder declines the exchange it holds with
     * it. Sending the receipt in the first case would be sending an argument
     * the caller does not have.
     */
    private Map<String, Object> declineArguments(String address) {
        if (receiptHeld == null) {
            return Map.of("address", address, "fields", Map.of("reason", "not this one"));
        }
        return Map.of("address", address, "receipt", receiptHeld,
            "fields", Map.of("reason", "cannot finish it"));
    }

    /** The row of the table this situation is about. */
    private static Contract.Row rowFor(List<Contract.Row> table, Situation situation) {
        return table.stream()
            .filter(r -> r.state().equals(situation.state())
                && r.caller().equals(situation.caller()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "the contract's table has no row '" + situation.state() + "' / '"
                    + situation.caller() + "'. The table changed and this probe did not, "
                    + "which is the probe going quiet rather than red."));
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
        commissionChildOf(root);
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

    // =======================================================================
    // Building one row's state
    // =======================================================================

    /**
     * The receipt of the exchange the current situation was built around, or
     * null where nobody took it up.
     *
     * <p>Held as state of the probe rather than threaded through, because the
     * calls that need it are chosen from the contract's table at a point where
     * the take has already happened. Null is the honest value for a situation
     * with no claim in it, and {@link #declineArguments} reads it as exactly
     * that.
     */
    private String receiptHeld;

    private void asCommissioner() {
        SurfaceFixture.asConsole(identity);
    }

    private void asCandidate() {
        SurfaceFixture.asExecutor(identity);
    }

    private void asOtherExecutor() {
        SurfaceFixture.asOtherExecutor(identity);
    }

    /** A child of a fresh bracket, open, with nobody holding it. */
    private String commissionChildOfAFreshBracket() {
        asCommissioner();
        receiptHeld = null;
        return commissionChildOf(commission());
    }

    /** The same child, taken up by the executor. */
    private String anActiveChild() {
        String address = commissionChildOfAFreshBracket();
        asCandidate();
        receiptHeld = receiptOf(call("dispatch_take",
            Map.of("address", address, "duration", "PT1H")));
        return address;
    }

    /** The same child again, with an answer delivered on it. */
    private String aChildWithAnAnswer() {
        String address = anActiveChild();
        call("dispatch_deliver_return", Map.of("address", address,
            "receipt", receiptHeld, "fields", Map.of("text", "the answer")));
        return address;
    }

    /** And with a question asked on it instead. */
    private String aChildWithAQuestion() {
        String address = anActiveChild();
        call("dispatch_ask_commissioner", Map.of("address", address,
            "receipt", receiptHeld, "fields", Map.of("question", "which of the two?")));
        return address;
    }

    /** Finished: an answer delivered and accepted. */
    private String aClosedChild() {
        String address = aChildWithAnAnswer();
        asCommissioner();
        call("dispatch_accept_return", Map.of("address", address));
        return address;
    }

    /**
     * An exchange a curation can be carried into.
     *
     * <p>A fresh bracket root, and deliberately not the exchange's own bracket
     * root: the contract admits any exchange the caller may see, and using a
     * sibling here would leave the cross-bracket case — which is the one the
     * predecessor refused outright — unexercised by this probe as well.
     */
    private String aCuratableTarget() {
        asCommissioner();
        return commission();
    }
}
