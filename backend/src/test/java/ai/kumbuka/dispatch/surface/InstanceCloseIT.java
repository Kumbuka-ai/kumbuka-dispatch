/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import ai.kumbuka.dispatch.platform.PlatformFixture;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code conclude} and {@code withdraw} without unfinished tasks: each closes
 * the instance with its return, read back by SQL; the return is checked
 * against the version -- a verdict and an outcome label where the version
 * requires them, a label of the version's labels only, a label only with its
 * translation basis -- and the closing is fenced by the conflict token, the
 * time of the instance's last act. Each refusal stands beside its red state,
 * the same call corrected, which closes.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class InstanceCloseIT {

    private static final String MARKED = "zz-marked-close-7319";
    private static final String CONCLUDE = "dispatch_conclude";
    private static final String CONCLUDE_VERB = "conclude";
    private static final String WITHDRAW_VERB = "withdraw";
    private static final String ADDRESS = "address";
    private static final String TOKEN = "conflict_token";
    private static final String FIELDS = "fields";
    private static final String REASON = "reason";
    private static final String OFFENDERS = "offenders";
    private static final String TEXT = "text";
    private static final String OUTCOME = "outcome";
    private static final String BASIS = "translation_basis";
    private static final String APPROVED = "approved";
    private static final String IS_ERROR = "result.isError";
    private static final String KIND = "kind";
    private static final String BODY = "body";
    private static final String STATE = "state";
    private static final String VERDICT = "verdict";
    private static final String CLOSING_ACT = "SELECT id FROM dispatch.act WHERE instance_id = ? "
        + "AND task_id IS NULL AND to_state = 'closed'";
    private static final String CONTENT_OF = "SELECT kind, body FROM dispatch.content "
        + "WHERE act_id = ? ORDER BY id";
    private static final String RETURN_OF = "SELECT verdict, outcome_label_id FROM "
        + "dispatch.instance_return WHERE act_id = ?";

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        PlatformFixture.grantDirectoryAccess();
        InstanceWire.as(identity, InstanceWire.CONSOLE);
    }

    @Test
    void conclude_closes_the_instance_with_its_return() {
        InstanceWire.Planted planted = InstanceWire.plant(CONCLUDE_VERB, true, true);
        Map<String, Object> started = start(planted);
        String address = String.valueOf(started.get(ADDRESS));

        Map<String, Object> closed = InstanceWire.answer(InstanceWire.mcp(CONCLUDE, Map.of(
            ADDRESS, address, TOKEN, InstanceWire.token(started), FIELDS, Map.of(TEXT,
                "the return text", VERDICT, "green", OUTCOME, APPROVED, BASIS, "the basis"))));

        assertThat(closed.get(FIELDS)).as("the answer is the closed instance")
            .isEqualTo(Map.of("selector", planted.selector(), "number", 1, "version", 1,
                "title", "a title", STATE, "closed", "resolution", "concluded"));
        assertThat(closed.get("next")).as("a closed instance has no call open")
            .isEqualTo(List.of());
        long instance = InstanceWire.instanceId(address);
        assertThat(InstanceWire.rows("SELECT state, resolution FROM dispatch.instance "
                + "WHERE id = ?", instance))
            .containsExactly(Map.of(STATE, "closed", "resolution", "concluded"));
        long act = InstanceWire.single(CLOSING_ACT, instance);
        assertThat(InstanceWire.rows("SELECT kind FROM dispatch.act WHERE id = ?", act))
            .containsExactly(Map.of(KIND, CONCLUDE_VERB));
        assertThat(InstanceWire.rows(RETURN_OF, act))
            .as("its return in instance_return, hung on the closing act")
            .containsExactly(Map.of(VERDICT, "green", "outcome_label_id", planted.approved()));
        assertThat(InstanceWire.rows(CONTENT_OF, act))
            .as("its text and the translation basis of its label as content of the act")
            .containsExactly(Map.of(KIND, "instance_return", BODY, "the return text"),
                Map.of(KIND, "instance_translation_basis", BODY, "the basis"));
    }

    @Test
    void withdraw_closes_the_instance_with_its_return_over_rest() {
        InstanceWire.Planted planted = InstanceWire.plant(WITHDRAW_VERB);
        Map<String, Object> started = start(planted);
        String address = String.valueOf(started.get(ADDRESS));

        Response withdrawn = InstanceWire.rest(address, WITHDRAW_VERB,
            Map.of(FIELDS, Map.of(TEXT, "given up")), InstanceWire.token(started));

        assertThat(withdrawn.statusCode()).as(withdrawn.asString()).isEqualTo(200);
        assertThat(withdrawn.jsonPath().getString("fields.resolution")).isEqualTo("withdrawn");
        assertThat(withdrawn.header("ETag")).as("the new token as ETag")
            .isEqualTo("\"" + withdrawn.jsonPath().getString(TOKEN) + "\"");
        long act = InstanceWire.single(CLOSING_ACT, InstanceWire.instanceId(address));
        assertThat(InstanceWire.rows("SELECT kind FROM dispatch.act WHERE id = ?", act))
            .containsExactly(Map.of(KIND, WITHDRAW_VERB));
        assertThat(InstanceWire.rows(RETURN_OF, act))
            .as("a version that requires neither takes a return of its text alone")
            .containsExactly(nulls(VERDICT, "outcome_label_id"));
        assertThat(InstanceWire.rows(CONTENT_OF, act))
            .containsExactly(Map.of(KIND, "instance_return", BODY, "given up"));
    }

    @Test
    void a_verdict_the_version_requires_is_needed() {
        InstanceWire.Planted planted = InstanceWire.plant(VERDICT, true, false);
        Map<String, Object> started = start(planted);

        Map<String, Object> refused = InstanceWire.refusal(close(started, Map.of(TEXT, MARKED)));
        assertThat(refused.get(REASON)).isEqualTo("ARGUMENT_MISSING");
        assertThat(InstanceWire.data(refused).get(OFFENDERS)).isEqualTo(List.of("fields.verdict"));
        assertThat(close(started, Map.of(TEXT, MARKED, VERDICT, "red"))
                .jsonPath().getBoolean(IS_ERROR))
            .as("RED STATE, observed: the same closing with a verdict closes").isFalse();
    }

    @Test
    void a_label_is_one_of_the_versions_and_comes_with_its_basis() {
        InstanceWire.Planted planted = InstanceWire.plant("label", false, true);
        Map<String, Object> started = start(planted);

        Map<String, Object> missing = InstanceWire.refusal(close(started, Map.of(TEXT, MARKED)));
        assertThat(InstanceWire.data(missing).get(OFFENDERS))
            .as("a version that requires a label refuses a return without one")
            .isEqualTo(List.of("fields.outcome"));

        Response undeclared = close(started, Map.of(TEXT, "t", OUTCOME, MARKED, BASIS, "b"));
        Map<String, Object> refused = InstanceWire.refusal(undeclared);
        assertThat(refused.get(REASON)).isEqualTo("ARGUMENT_INVALID");
        assertThat(InstanceWire.data(refused).get(OFFENDERS)).isEqualTo(List.of("fields.outcome"));
        assertThat(undeclared.asString()).as("and gives back no label sent")
            .doesNotContain(MARKED);

        Map<String, Object> withoutBasis = InstanceWire.refusal(close(started,
            Map.of(TEXT, "t", OUTCOME, APPROVED)));
        assertThat(withoutBasis.get(REASON)).isEqualTo("ARGUMENT_MISSING");
        assertThat(InstanceWire.data(withoutBasis).get(OFFENDERS))
            .isEqualTo(List.of("fields.translation_basis"));

        assertThat(close(started, Map.of(TEXT, "t", OUTCOME, APPROVED, BASIS, "b"))
                .jsonPath().getBoolean(IS_ERROR))
            .as("RED STATE, observed: a label of the version with its basis closes").isFalse();
    }

    @Test
    void a_closing_without_a_token_or_with_a_stale_one_is_refused() {
        InstanceWire.Planted planted = InstanceWire.plant("token");
        Map<String, Object> started = start(planted);
        String address = String.valueOf(started.get(ADDRESS));

        Map<String, Object> missing = InstanceWire.refusal(InstanceWire.mcp(CONCLUDE,
            Map.of(ADDRESS, address, FIELDS, Map.of(TEXT, "t"))));
        assertThat(missing.get(REASON)).isEqualTo("CONFLICT_TOKEN_MISSING");
        Response missingOverRest = InstanceWire.rest(address, CONCLUDE_VERB,
            Map.of(FIELDS, Map.of(TEXT, "t")), null);
        assertThat(missingOverRest.statusCode()).as(missingOverRest.asString()).isEqualTo(428);

        Map<String, Object> attached = InstanceWire.answer(InstanceWire.mcp(
            "dispatch_reference_attach", Map.of(ADDRESS, address, FIELDS,
                Map.of(KIND, "reference", "value", "doc-1"))));
        assertThat(InstanceWire.token(attached))
            .as("an act on the instance moves its token on")
            .isNotEqualTo(InstanceWire.token(started));

        Map<String, Object> refused = InstanceWire.refusal(close(started, Map.of(TEXT, "t")));
        assertThat(refused.get(REASON)).isEqualTo("CONFLICT_TOKEN_STALE");
        assertThat(InstanceWire.data(refused))
            .as("carrying the instance as it stands (DEC-0041)")
            .containsEntry(ADDRESS, address)
            .containsEntry(TOKEN, InstanceWire.token(attached))
            .containsEntry(STATE, "running")
            .containsEntry("service", "dispatch");
        Response staleOverRest = InstanceWire.rest(address, CONCLUDE_VERB,
            Map.of(FIELDS, Map.of(TEXT, "t")), InstanceWire.token(started));
        assertThat(staleOverRest.statusCode()).as(staleOverRest.asString()).isEqualTo(412);
        assertThat(InstanceWire.single("SELECT count(*) FROM dispatch.act WHERE instance_id = ? "
                + "AND to_state = 'closed'", InstanceWire.instanceId(address)))
            .as("nothing was closed").isZero();

        assertThat(close(attached, Map.of(TEXT, "t")).jsonPath().getBoolean(IS_ERROR))
            .as("RED STATE, observed: the same closing with the current token closes").isFalse();
    }

    // -----------------------------------------------------------------------

    private static Map<String, Object> start(InstanceWire.Planted planted) {
        return InstanceWire.answer(InstanceWire.mcp("dispatch_start",
            InstanceWire.startArguments(planted.selector(), InstanceWire.minimalFields("a title"))));
    }

    /** A conclude of the instance an answer names, under the token that answer handed out. */
    private static Response close(Map<String, Object> answer, Map<String, Object> fields) {
        return InstanceWire.mcp(CONCLUDE, Map.of(ADDRESS, answer.get(ADDRESS), TOKEN,
            InstanceWire.token(answer), FIELDS, fields));
    }

    private static Map<String, Object> nulls(String... columns) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String column : columns) {
            row.put(column, null);
        }
        return row;
    }
}
