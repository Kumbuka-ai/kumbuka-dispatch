/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.payload;

import ai.kumbuka.dispatch.domain.ExchangeAddress;
import ai.kumbuka.dispatch.domain.HolderState;
import ai.kumbuka.dispatch.domain.TaskState;
import ai.kumbuka.dispatch.domain.TaskView;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An answer as the service writes it: an instant to the microsecond, as the
 * database holds it, and a member with no value absent (C7.2, C7.3).
 *
 * <p>Measured on the CI runner, 2026-10-06: the answer to a claim carried the
 * lease end from the clock, {@code …42.538822988Z}, while every later read
 * carried the stored {@code …42.538823Z}, so a repeated claim looked as if it
 * had moved the lease. A clock with microsecond resolution, as on macOS, hides
 * the difference; this case does not depend on the clock. The rendering is the
 * serialiser's now, and the serialiser is what this writes with.
 */
class PayloadsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    static {
        new SerialisationCustomizer().customize(JSON);
    }

    @Test
    void an_instant_is_written_to_the_microsecond_as_it_is_stored() {
        Instant fromTheClock = Instant.parse("2026-10-06T11:05:42.538822988Z");
        TaskView held = new TaskView(UUID.randomUUID(), new ExchangeAddress("sprint", 7, 0),
            TaskState.ACTIVE, null, null, null, HolderState.SELF, fromTheClock, fromTheClock, null, 0,
            "t", "code", null, null, "token", null, List.of(), List.of());

        JsonNode answer = JSON.valueToTree(Payloads.task(new CallBinding.Answered(
            "dispatch://probe/sprint/7.0", held, false, List.of(), null, "receipt")));

        assertThat(answer.at("/fields/lease_expires_at").asText())
            .isEqualTo("2026-10-06T11:05:42.538822Z");
        assertThat(answer.at("/fields/not_before").asText())
            .isEqualTo("2026-10-06T11:05:42.538822Z");
    }

    @Test
    void a_member_with_no_value_is_absent() {
        TaskView open = new TaskView(UUID.randomUUID(), new ExchangeAddress("sprint", 7, 0),
            TaskState.OPEN, null, null, null, HolderState.NOBODY, null, null, null, 0,
            "t", "code", null, null, "token", null, List.of(), List.of());

        JsonNode answer = JSON.valueToTree(Payloads.task(new CallBinding.Answered(
            "dispatch://probe/sprint/7.0", open, false, List.of(), null, null)));

        assertThat(answer.has("receipt")).as("no receipt outside a claim").isFalse();
        assertThat(answer.has("waiting_for")).isFalse();
        assertThat(answer.get("fields").has("lease_expires_at")).isFalse();
        assertThat(answer.get("fields").has("hold_reason")).isFalse();
    }
}
