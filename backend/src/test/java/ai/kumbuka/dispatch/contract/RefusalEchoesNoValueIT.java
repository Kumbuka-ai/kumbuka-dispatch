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
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class RefusalEchoesNoValueIT {

    /** The value no refusal may give back. */
    private static final String MARKER = "zzecho7319";

    @Inject TestIdentityAssociation identity;

    private final Map<RefusalCode, List<String>> raised = new EnumMap<>(RefusalCode.class);

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
        record(RefusalCode.CALL_NOT_AT_THIS_ADDRESS, notHere.jsonPath().getString("reason"),
            notHere.asString());

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
    }

    // -----------------------------------------------------------------------

    private void mcp(RefusalCode expected, String tool, Map<String, Object> arguments) {
        Response answer = Mcp.call(tool, arguments);
        record(expected, Mcp.reason(answer), answer.asString());
    }

    private void record(RefusalCode expected, String reason, String answer) {
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
