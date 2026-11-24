/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.platform.PlatformFixture;
import ai.kumbuka.dispatch.surface.InstanceWire;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Closing an instance with unfinished tasks (TAR-0004 2.3, REA-0010 S5, and
 * the operator's answer of 2026-11-24), rebuilt from the bracket of the old
 * kernel onto the instance: the first {@code conclude} or {@code withdraw} is
 * refused with every unfinished task named and a confirmation computed from
 * their set; the same call with it writes the closing act, then a {@code
 * withdraw} for each unfinished task that refers to the closing act, closes
 * those tasks as withdrawn, and closes the instance, in one transaction; a
 * confirmation is void once the set changed or for the other closing.
 *
 * <p>The tasks are planted by SQL, since no call of this change creates one.
 * Every expectation is written here and read back from the store by SQL.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class TaskBracketIT {

    private static final String ADDRESS = "address";
    private static final String TOKEN = "conflict_token";
    private static final String FIELDS = "fields";
    private static final String CONFIRMATION = "confirmation";
    private static final String UNFINISHED = "CHILDREN_UNFINISHED";
    private static final String CLOSED = "closed";
    private static final String WITHDRAW = "withdraw";
    private static final String REASON = "reason";

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        PlatformFixture.grantDirectoryAccess();
        InstanceWire.as(identity, InstanceWire.CONSOLE);
    }

    @Test
    void closing_an_instance_with_unfinished_tasks_needs_a_confirmation_and_then_withdraws_them() {
        Map<String, Object> started = started("unfinished");
        String address = String.valueOf(started.get(ADDRESS));
        long instance = InstanceWire.instanceId(address);
        long open = InstanceWire.plantTask(instance, 1, "open");
        long held = InstanceWire.plantTask(instance, 2, "on_hold");
        long done = InstanceWire.plantTask(instance, 3, CLOSED);

        Map<String, Object> refused = InstanceWire.refusal(conclude(started, null));
        assertThat(refused.get(REASON)).isEqualTo(UNFINISHED);
        Map<String, Object> data = InstanceWire.data(refused);
        assertThat(data.get("children")).as("each unfinished task, by its complete address")
            .isEqualTo(List.of(address + ".1", address + ".2"));
        assertThat(data.get(CONFIRMATION)).as("and a confirmation handed out now").isNotNull();
        assertThat(states(instance)).as("RED STATE, observed: refused, nothing was closed")
            .isEqualTo(Map.of(open, "open", held, "on_hold", done, CLOSED));

        Map<String, Object> closed = InstanceWire.answer(conclude(started,
            String.valueOf(data.get(CONFIRMATION))));
        assertThat(closed.get(FIELDS)).asInstanceOf(
                org.assertj.core.api.InstanceOfAssertFactories.MAP)
            .containsEntry("state", CLOSED).containsEntry("resolution", "concluded");

        assertThat(states(instance)).as("no closed instance holds an unfinished task")
            .isEqualTo(Map.of(open, CLOSED, held, CLOSED, done, CLOSED));
        assertThat(InstanceWire.rows("SELECT resolution, hold_reason FROM dispatch.task "
                + "WHERE id = ?", held))
            .as("the task on hold is withdrawn and holds no reason any more")
            .containsExactly(row("resolution", "withdrawn", "hold_reason", null));
        long closing = InstanceWire.single("SELECT id FROM dispatch.act WHERE instance_id = ? "
            + "AND kind = 'conclude'", instance);
        assertThat(InstanceWire.rows("SELECT task_id, kind, to_state, refers_to_act_id, "
                + "permission FROM dispatch.act WHERE instance_id = ? AND task_id IS NOT NULL "
                + "ORDER BY sequence", instance))
            .as("a withdraw for each unfinished task, referring to the closing act, under the "
                + "permission of the closing call")
            .containsExactly(
                row("task_id", open, "kind", WITHDRAW, "to_state", CLOSED,
                    "refers_to_act_id", closing, "permission", "dispatch.instance.conclude"),
                row("task_id", held, "kind", WITHDRAW, "to_state", CLOSED,
                    "refers_to_act_id", closing, "permission", "dispatch.instance.conclude"));
    }

    @Test
    void a_confirmation_is_void_once_the_set_of_unfinished_tasks_changed() {
        Map<String, Object> started = started("changed");
        long instance = InstanceWire.instanceId(String.valueOf(started.get(ADDRESS)));
        InstanceWire.plantTask(instance, 1, "open");
        String handedOut = String.valueOf(InstanceWire.data(InstanceWire.refusal(
            conclude(started, null))).get(CONFIRMATION));
        long added = InstanceWire.plantTask(instance, 2, "active");

        Map<String, Object> stale = InstanceWire.refusal(conclude(started, handedOut));
        assertThat(stale.get(REASON)).isEqualTo(UNFINISHED);
        assertThat(InstanceWire.data(stale).get(CONFIRMATION))
            .as("a new confirmation, for the new set").isNotEqualTo(handedOut);
        assertThat(states(instance).get(added)).as("and nothing was closed").isEqualTo("active");

        assertThat(conclude(started, String.valueOf(InstanceWire.data(stale).get(CONFIRMATION)))
                .jsonPath().getBoolean("result.isError"))
            .as("RED STATE, observed: the confirmation of the set as it stands closes")
            .isFalse();
    }

    @Test
    void a_confirmation_is_bound_to_the_closing_it_was_handed_out_for() {
        Map<String, Object> started = started("bound");
        String address = String.valueOf(started.get(ADDRESS));
        long instance = InstanceWire.instanceId(address);
        InstanceWire.plantTask(instance, 1, "open");
        String ofConclude = String.valueOf(InstanceWire.data(InstanceWire.refusal(
            conclude(started, null))).get(CONFIRMATION));

        Response overRest = InstanceWire.rest(address, WITHDRAW, Map.of(CONFIRMATION, ofConclude,
            FIELDS, Map.of("text", "given up")), InstanceWire.token(started));
        assertThat(overRest.statusCode()).as(overRest.asString()).isEqualTo(409);
        assertThat(overRest.jsonPath().getString(REASON)).isEqualTo(UNFINISHED);
        String ofWithdraw = overRest.jsonPath().getString("data.confirmation");
        assertThat(ofWithdraw).as("the withdraw hands out its own").isNotEqualTo(ofConclude);

        Response withdrawn = InstanceWire.rest(address, WITHDRAW, Map.of(CONFIRMATION,
            ofWithdraw, FIELDS, Map.of("text", "given up")), InstanceWire.token(started));
        assertThat(withdrawn.statusCode())
            .as("RED STATE, observed: the withdraw with its own confirmation closes")
            .isEqualTo(200);
        assertThat(withdrawn.jsonPath().getString("fields.resolution")).isEqualTo("withdrawn");
    }

    @Test
    void an_instance_whose_tasks_are_all_closed_closes_without_a_confirmation() {
        Map<String, Object> started = started("finished");
        long instance = InstanceWire.instanceId(String.valueOf(started.get(ADDRESS)));
        InstanceWire.plantTask(instance, 1, CLOSED);

        assertThat(conclude(started, null).jsonPath().getBoolean("result.isError"))
            .as("no unfinished task, no confirmation").isFalse();
        assertThat(InstanceWire.single("SELECT count(*) FROM dispatch.act WHERE instance_id = ? "
                + "AND kind = 'withdraw'", instance))
            .as("and no task is withdrawn").isZero();
    }

    // -----------------------------------------------------------------------

    private static Map<String, Object> started(String prefix) {
        return InstanceWire.answer(InstanceWire.mcp("dispatch_start", InstanceWire.startArguments(
            InstanceWire.plant(prefix).selector(), InstanceWire.minimalFields("a title"))));
    }

    private static Response conclude(Map<String, Object> started, @Nullable String confirmation) {
        Map<String, Object> arguments = new LinkedHashMap<>();
        arguments.put(ADDRESS, started.get(ADDRESS));
        arguments.put(TOKEN, InstanceWire.token(started));
        arguments.put(FIELDS, Map.of("text", "done"));
        if (confirmation != null) {
            arguments.put(CONFIRMATION, confirmation);
        }
        return InstanceWire.mcp("dispatch_conclude", arguments);
    }

    /** The state of each task of an instance, by its surrogate. */
    private static Map<Long, String> states(long instance) {
        Map<Long, String> states = new LinkedHashMap<>();
        InstanceWire.rows("SELECT id, state FROM dispatch.task WHERE instance_id = ? ORDER BY sub",
                instance)
            .forEach(row -> states.put(((Number) row.get("id")).longValue(),
                String.valueOf(row.get("state"))));
        return states;
    }

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }
}
