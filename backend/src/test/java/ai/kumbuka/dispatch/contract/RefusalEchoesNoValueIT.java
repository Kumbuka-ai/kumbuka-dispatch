/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.contract;

import ai.kumbuka.dispatch.surface.Mcp;
import ai.kumbuka.dispatch.surface.RefusalCode;
import ai.kumbuka.dispatch.surface.SurfaceFixture;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * No refusal of the catalogue returns a value the caller sent (DEC-0042), in
 * its message or in its {@code data}.
 *
 * <p>Every code is raised on the wire with one marked value in the arguments
 * of the refused call — where the code is about that value, as the value it
 * refuses — and the whole answer is searched for it. The marker is a DNS
 * label, a bracket kind's shape and an apparatus pattern at once, so it
 * passes every check of form it need not fail and reaches the refusal the
 * case is about.
 *
 * <p>The cases are collected by code and the collection is held against
 * {@link RefusalCode#values()}: a code added to the catalogue without a case
 * here turns this red. {@code UNEXPECTED_FAILURE} has no rule a caller can
 * break and is probed in {@code CallRouterTest}, under a kernel that fails
 * with the marker in its arguments and in its own message.
 *
 * <p>Red probe, observed: against the catalogue before the amendment of
 * 2026-10-13, which filled {@code {value}} into five patterns, this turns red
 * on {@code CLAIM_DURATION_INVALID}, {@code PAGE_SIZE_REJECTED}, {@code
 * CURSOR_MALFORMED}, {@code SORT_UNDECLARED} and {@code ARGUMENT_INVALID}.
 *
 * <p>Every refusal raised here is also held against the declaration on the
 * REST surface: its code is one the declaration names for the call it
 * refused. RED STATE, observed 2026-10-15: {@code SORT_UNDECLARED} left out
 * of the declared set of {@code dispatch_query} turns this red on exactly
 * that pair.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class RefusalEchoesNoValueIT {

    /** The value no refusal may give back. */
    private static final String MARKER = "zzecho7319";

    @Inject TestIdentityAssociation identity;

    private final Map<RefusalCode, List<String>> raised = new EnumMap<>(RefusalCode.class);

    /** Each refusal raised, as the call it refused and its code. */
    private final List<String[]> refusedCalls = new ArrayList<>();

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        SurfaceFixture.asConsole(identity);
    }

    @Test
    void no_refusal_of_the_catalogue_returns_a_value_the_caller_sent() {
        Map<String, Object> collection = Map.of("scope", SurfaceFixture.SCOPE,
            "selector", SurfaceFixture.SELECTOR);

        // -- decided before a task is read --------------------------------
        mcp(RefusalCode.NOT_FOUND, "dispatch_read",
            Map.of("address", "dispatch://" + MARKER + "/sprint/1.0"));
        mcp(RefusalCode.PAGE_SIZE_REJECTED, "dispatch_query", with(collection,
            "page_size", MARKER));
        mcp(RefusalCode.CURSOR_MALFORMED, "dispatch_query", with(collection, "after", MARKER));
        mcp(RefusalCode.SORT_UNDECLARED, "dispatch_query", with(collection, "sort", MARKER));
        mcp(RefusalCode.ARGUMENT_UNKNOWN, "dispatch_query", with(collection,
            "zz_unknown", MARKER));
        mcp(RefusalCode.ARGUMENT_MISSING, "dispatch_create", with(collection,
            "fields", Map.of("title", MARKER)));
        mcp(RefusalCode.ARGUMENT_INVALID, "dispatch_read", Map.of("address", MARKER));
        mcp(RefusalCode.ARGUMENT_INVALID, "dispatch_query", with(collection,
            "state", MARKER));
        mcp(RefusalCode.SELECTOR_UNKNOWN, "dispatch_create", Map.of(
            "scope", SurfaceFixture.SCOPE, "selector", MARKER,
            "fields", Map.of("title", MARKER, "apparatus", "code")));
        mcp(RefusalCode.SCOPE_KIND_UNSUPPORTED, "dispatch_create", Map.of(
            "scope", SurfaceFixture.PRIVATE_SCOPE, "selector", SurfaceFixture.SELECTOR,
            "fields", Map.of("title", MARKER, "apparatus", "code")));
        mcp(RefusalCode.SCOPE_LOCKED, "dispatch_create", Map.of(
            "scope", SurfaceFixture.LOCKED_SCOPE, "selector", SurfaceFixture.SELECTOR,
            "fields", Map.of("title", MARKER, "apparatus", "code")));

        Map<String, Object> keyed = Map.of("scope", SurfaceFixture.SCOPE,
            "selector", SurfaceFixture.SELECTOR, "idempotency_key", MARKER,
            "fields", Map.of("title", "first", "apparatus", "code"));
        Mcp.answer(Mcp.call("dispatch_create", keyed));
        mcp(RefusalCode.IDEMPOTENCY_KEY_REUSED, "dispatch_create", with(keyed,
            "fields", Map.of("title", "second", "apparatus", "code")));

        SurfaceFixture.open("one", "code");
        SurfaceFixture.open("two", "code");
        String cursor = String.valueOf(Mcp.answer(Mcp.call("dispatch_query",
            with(collection, "page_size", 1))).get("next_cursor"));
        mcp(RefusalCode.CURSOR_FOREIGN, "dispatch_query", with(with(collection,
            "after", cursor), "apparatus", MARKER));

        Response notHere = given().contentType(ContentType.JSON)
            .queryParam("remark", MARKER)
            .post(SurfaceFixture.collection() + ":send");
        recordRaised(RefusalCode.CALL_NOT_AT_THIS_ADDRESS, notHere.jsonPath().getString("reason"),
            notHere.asString());
        refusedCalls.add(new String[] {"dispatch_send", "CALL_NOT_AT_THIS_ADDRESS"});

        // -- about a task the caller may see ------------------------------
        String open = SurfaceFixture.address(SurfaceFixture.open("open", "code"));
        mcp(RefusalCode.STATE_DOES_NOT_ALLOW, "dispatch_send", Map.of("address", open,
            "conflict_token", token(open)));
        mcp(RefusalCode.CONFLICT_TOKEN_STALE, "dispatch_withdraw", Map.of("address", open,
            "conflict_token", MARKER));

        String root = SurfaceFixture.address(SurfaceFixture.open("a root", "code"));
        Mcp.answer(Mcp.call("dispatch_create", Map.of("scope", SurfaceFixture.SCOPE,
            "selector", SurfaceFixture.SELECTOR, "parent", root,
            "fields", Map.of("title", "a child", "apparatus", "code"))));
        mcp(RefusalCode.CHILDREN_NOT_FINISHED, "dispatch_withdraw", Map.of("address", root,
            "conflict_token", token(root), "confirmation", MARKER));

        SurfaceFixture.asMutedConsole(identity);
        mcp(RefusalCode.SCOPE_READ_ONLY, "dispatch_create", with(collection,
            "fields", Map.of("title", MARKER, "apparatus", "code")));

        SurfaceFixture.asExecutor(identity);
        mcp(RefusalCode.ROLE_DOES_NOT_ALLOW, "dispatch_withdraw", Map.of("address", open,
            "conflict_token", MARKER));
        mcp(RefusalCode.NOTHING_TO_TAKE, "dispatch_claim_next", with(collection,
            "apparatus", List.of(MARKER)));
        mcp(RefusalCode.CLAIM_DURATION_INVALID, "dispatch_claim", Map.of("address", open,
            "duration", MARKER));
        mcp(RefusalCode.ARGUMENT_INVALID, "dispatch_claim_next", with(collection,
            "apparatus", List.of(MARKER + "_")));
        String receipt = String.valueOf(Mcp.answer(Mcp.call("dispatch_claim",
            Map.of("address", open))).get("receipt"));
        mcp(RefusalCode.RECEIPT_WRONG, "dispatch_renew", Map.of("address", open,
            "receipt", MARKER));
        mcp(RefusalCode.ARGUMENT_INVALID, "dispatch_defer", Map.of("address", open,
            "receipt", receipt, "fields", Map.of("not_before", MARKER)));

        SurfaceFixture.asOtherExecutor(identity);
        mcp(RefusalCode.NOT_THE_HOLDER, "dispatch_deliver", Map.of("address", open,
            "receipt", MARKER, "fields", Map.of("text", MARKER)));

        SurfaceFixture.asExecutor(identity);
        String later = Instant.now().plus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.SECONDS)
            .toString();
        Mcp.answer(Mcp.call("dispatch_defer", Map.of("address", open, "receipt", receipt,
            "fields", Map.of("not_before", later))));
        SurfaceFixture.asOtherExecutor(identity);
        mcp(RefusalCode.DEFERRAL_PENDING, "dispatch_claim", Map.of("address", open,
            "idempotency_key", MARKER));

        // -- the verdict --------------------------------------------------
        List<RefusalCode> uncovered = new ArrayList<>(List.of(RefusalCode.values()));
        uncovered.removeAll(raised.keySet());
        uncovered.remove(RefusalCode.UNEXPECTED_FAILURE);
        assertThat(uncovered).as("every code of the catalogue is raised here").isEmpty();

        Map<RefusalCode, List<String>> echoing = new LinkedHashMap<>();
        raised.forEach((code, answers) -> {
            List<String> echoed = answers.stream().filter(a -> a.contains(MARKER)).toList();
            if (!echoed.isEmpty()) {
                echoing.put(code, echoed);
            }
        });
        assertThat(echoing).as("refusals that give back the value the caller sent").isEmpty();

        // -- every code raised is one the declaration names for its call --
        Map<String, Object> declaration = given().get("/api/declaration").jsonPath().getMap("$");
        Map<String, List<String>> declared = new LinkedHashMap<>();
        for (Object call : (List<?>) declaration.get("calls")) {
            Map<?, ?> each = (Map<?, ?>) call;
            declared.put(String.valueOf(each.get("identity")),
                ((List<?>) each.get("refusals")).stream().map(String::valueOf).toList());
        }
        List<String> undeclared = refusedCalls.stream()
            .filter(pair -> !declared.get(pair[0]).contains(pair[1]))
            .map(pair -> pair[0] + " " + pair[1])
            .toList();
        assertThat(undeclared).as("refusals the declaration does not name for their call")
            .isEmpty();
    }

    // =======================================================================
    // DEC-0043 R18, R19: the marker in every place a caller can put one,
    // every hint provoked, and neither the scope nor the bracket kind named
    // =======================================================================

    /**
     * The caller's input and the tenant's vocabulary are in no refusal and no
     * hint, wherever the caller put them.
     *
     * <p>The first half places the marker where the cases above do not: as
     * the name of an undeclared argument at both levels, of an undeclared
     * call on both surfaces and of an undeclared protocol method, as a scope,
     * a bracket kind and a path segment, as the value of every closed set
     * ({@code sort}, {@code direction}, {@code part}, {@code reason}), as one
     * of several patterns and filter values, and as every transport artefact.
     * The second half gives a task a title, a text, an apparatus, metadata
     * and question options that carry the marker, moves it through the
     * states in which {@code next} and {@code waiting_for} speak, and reads
     * the hints of every one. The scope's and the bracket kind's own names
     * are the caller's input too: no message carries them, and no member of
     * {@code data} but the addresses of {@code data.children}, which are the
     * answer a read would give (DEC-0043, "What R18 does not bind").
     *
     * <p>RED STATE, observed 2026-10-15: the protocol's refusal of an unknown
     * method changed for the run to quote the method that arrived turns this
     * red on exactly the two answers that carry it, the marker as a method and
     * as {@code tools/<marker>}.
     */
    @Test
    void no_refusal_and_no_hint_repeats_the_callers_input_or_the_tenants_vocabulary() {
        List<String> answers = new ArrayList<>();
        Map<String, Object> collection = Map.of("scope", SurfaceFixture.SCOPE,
            "selector", SurfaceFixture.SELECTOR);

        // -- names the caller made up ------------------------------------
        answers.add(refused("dispatch_query", with(collection, MARKER, "x")));
        String draft = SurfaceFixture.address(SurfaceFixture.idOf(SurfaceFixture.create(
            Map.of("title", "a draft", "apparatus", "code"))));
        answers.add(refused("dispatch_update", Map.of("address", draft,
            "conflict_token", token(draft), "fields", Map.of(MARKER, "x"))));
        answers.add(refused(MARKER, Map.of()));
        answers.add(refused("dispatch_" + MARKER, Map.of()));
        answers.add(Mcp.rpc(MARKER, Map.of()).asString());
        answers.add(Mcp.rpc("tools/" + MARKER, Map.of()).asString());
        answers.add(given().contentType(ContentType.JSON)
            .post(SurfaceFixture.collection() + ":" + MARKER).asString());
        answers.add(given().contentType(ContentType.JSON)
            .post(SurfaceFixture.item("1.0") + ":" + MARKER).asString());
        answers.add(given().get("/api/" + MARKER + "/" + MARKER + ":claim_next").asString());
        answers.add(given().contentType(ContentType.JSON).body(Map.of("scope", MARKER))
            .post(SurfaceFixture.collection()).asString());
        answers.add(given().contentType(ContentType.JSON).header("Kumbuka-Surface", MARKER)
            .post(SurfaceFixture.collection()).asString());

        // -- a scope, a bracket kind, an address -------------------------
        answers.add(refused("dispatch_query", Map.of("scope", MARKER,
            "selector", SurfaceFixture.SELECTOR)));
        answers.add(refused("dispatch_query", Map.of("scope", SurfaceFixture.SCOPE,
            "selector", MARKER)));
        answers.add(refused("dispatch_claim_next", Map.of("scope", SurfaceFixture.SCOPE,
            "selector", MARKER, "apparatus", List.of("code"))));
        answers.add(refused("dispatch_read", Map.of("address",
            "dispatch://" + SurfaceFixture.SCOPE + "/" + MARKER + "/1.0")));
        answers.add(refused("dispatch_read", Map.of("address",
            SurfaceFixture.address("1.0") + MARKER)));
        answers.add(refused("dispatch_create", with(collection, "parent", MARKER)));

        // -- every closed set, every list, every transport artefact ------
        answers.add(refused("dispatch_query", with(collection, "sort", MARKER)));
        answers.add(refused("dispatch_query", with(collection, "direction", MARKER)));
        answers.add(refused("dispatch_query", with(collection, "page_size", MARKER)));
        answers.add(refused("dispatch_query", with(collection, "after", MARKER)));
        answers.add(refused("dispatch_query", with(collection, "state", "open," + MARKER)));
        answers.add(refused("dispatch_query", with(collection, "bracket", "1," + MARKER)));
        answers.add(refused("dispatch_query", with(collection, "apparatus", "code," + MARKER
            + "_")));
        answers.add(refused("dispatch_query", with(collection, "address",
            SurfaceFixture.address("1.0") + "," + MARKER)));
        String open = SurfaceFixture.address(SurfaceFixture.open("open " + MARKER, MARKER));
        answers.add(refused("dispatch_read_text", Map.of("address", open, "part", MARKER)));
        answers.add(refused("dispatch_withdraw", Map.of("address", open,
            "conflict_token", MARKER)));
        answers.add(refused("dispatch_withdraw", Map.of("address", open,
            "conflict_token", token(open), "fields", Map.of("remark", List.of(MARKER)))));

        SurfaceFixture.asExecutor(identity);
        answers.add(refused("dispatch_claim_next", with(collection, "apparatus",
            List.of("code", "review", MARKER + "%"))));
        answers.add(refused("dispatch_claim_next", with(collection, "apparatus",
            List.of("code", "*"))));
        answers.add(refused("dispatch_claim", Map.of("address", open, "duration", MARKER)));
        String receipt = String.valueOf(Mcp.answer(Mcp.call("dispatch_claim",
            Map.of("address", open, "idempotency_key", MARKER))).get("receipt"));
        answers.add(refused("dispatch_hold", Map.of("address", open, "receipt", receipt,
            "fields", Map.of("reason", MARKER))));
        answers.add(refused("dispatch_ask", Map.of("address", open, "receipt", receipt,
            "fields", Map.of("question", MARKER, "options", List.of(MARKER, 7)))));
        answers.add(refused("dispatch_renew", Map.of("address", open, "receipt", MARKER)));
        answers.add(refused("dispatch_annotate", Map.of("address", open,
            "fields", Map.of("text", MARKER, "part", MARKER))));

        // -- the hints of a task that carries the marker everywhere ------
        Mcp.answer(Mcp.call("dispatch_ask", Map.of("address", open, "receipt", receipt,
            "fields", Map.of("question", MARKER, "options", List.of(MARKER, "other")))));
        answers.add(hints(Mcp.answer(Mcp.call("dispatch_read", Map.of("address", open)))));
        SurfaceFixture.asConsole(identity);
        answers.add(hints(Mcp.answer(Mcp.call("dispatch_read", Map.of("address", open)))));
        answers.add(refused("dispatch_answer", Map.of("address", open,
            "conflict_token", token(open), "fields", Map.of("option", MARKER + "x"))));
        Mcp.answer(Mcp.call("dispatch_answer", Map.of("address", open,
            "conflict_token", token(open), "fields", Map.of("option", MARKER))));
        SurfaceFixture.asExecutor(identity);
        answers.add(hints(Mcp.answer(Mcp.call("dispatch_deliver", Map.of("address", open,
            "receipt", receipt, "fields", Map.of("text", MARKER,
                "metadata", Map.of(MARKER, MARKER)))))));
        answers.add(hints(Mcp.answer(Mcp.call("dispatch_read", Map.of("address", open)))));
        SurfaceFixture.asConsole(identity);
        answers.add(hints(Mcp.answer(Mcp.call("dispatch_read", Map.of("address", open)))));

        // -- the verdict --------------------------------------------------
        List<String> echoing = answers.stream()
            .filter(answer -> answer.contains(MARKER))
            .toList();
        assertThat(echoing).as("answers that repeat what the caller put in").isEmpty();
        assertThat(raised.values().stream().flatMap(List::stream).toList())
            .as("refusals of the first test that name the scope or the bracket kind")
            .allSatisfy(RefusalEchoesNoValueIT::namesNeitherScopeNorBracketKind);
        assertThat(answers).as("and neither do these")
            .allSatisfy(RefusalEchoesNoValueIT::namesNeitherScopeNorBracketKind);
    }

    /** The answer of a call that is refused, as the caller receives it. */
    private static String refused(String tool, Map<String, Object> arguments) {
        Response answer = Mcp.call(tool, arguments);
        assertThat(answer.jsonPath().getBoolean("result.isError"))
            .as("%s is refused: %s", tool, answer.asString()).isTrue();
        return answer.asString();
    }

    /** What an answer says of its own accord: its next steps and what it waits for. */
    @SuppressWarnings("unchecked")
    private static String hints(Map<String, Object> answer) {
        return String.valueOf(answer.get("next")) + " " + answer.get("waiting_for");
    }

    /**
     * Neither the scope nor the bracket kind the caller named stands in a
     * message, nor in any member of {@code data} but the addresses of the
     * unfinished children.
     */
    private static void namesNeitherScopeNorBracketKind(String answer) {
        if (!answer.startsWith("{")) {
            // The hints of an answer, as hints() renders them: no member of an envelope.
            assertThat(answer).doesNotContain(SurfaceFixture.SCOPE);
            return;
        }
        io.restassured.path.json.JsonPath json = io.restassured.path.json.JsonPath.from(answer);
        Object structured = json.get("result.structuredContent");
        Map<String, Object> envelope = json.getMap(structured instanceof Map<?, ?>
            ? "result.structuredContent" : "$");
        String message = String.valueOf(envelope.get("message"));
        assertThat(message).as(answer)
            .doesNotContain(SurfaceFixture.SCOPE)
            .doesNotContain("/" + SurfaceFixture.SELECTOR)
            .doesNotContainIgnoringCase(" " + SurfaceFixture.SELECTOR + " ");
        Object data = envelope.get("data");
        if (data instanceof Map<?, ?> members) {
            Map<Object, Object> rest = new LinkedHashMap<>(members);
            rest.remove("children");
            assertThat(String.valueOf(rest)).as(answer).doesNotContain(SurfaceFixture.SCOPE);
        }
    }

    // -----------------------------------------------------------------------

    private void mcp(RefusalCode expected, String tool, Map<String, Object> arguments) {
        Response answer = Mcp.call(tool, arguments);
        recordRaised(expected, Mcp.reason(answer), answer.asString());
        refusedCalls.add(new String[] {tool, expected.name()});
    }

    private void recordRaised(RefusalCode expected, String reason, String answer) {
        assertThat(reason).as("the case for %s raises it: %s", expected, answer)
            .isEqualTo(expected.name());
        raised.computeIfAbsent(expected, c -> new ArrayList<>()).add(answer);
    }

    private static Map<String, Object> with(Map<String, Object> arguments, String name,
                                            Object value) {
        Map<String, Object> more = new LinkedHashMap<>(arguments);
        more.put(name, value);
        return more;
    }

    private static String token(String address) {
        return String.valueOf(Mcp.answer(Mcp.call("dispatch_read",
            Map.of("address", address))).get("conflict_token"));
    }
}
