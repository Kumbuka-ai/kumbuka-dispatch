/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.surface.InstanceWire;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The time of an act rises strictly within its instance, also where the clock
 * does not move, and with it the conflict token of the instance (C10.1, in the
 * form the operator decided on 2026-11-24: the token is the time of the
 * instance's last act about no task, written as {@code max(now, previous + 1
 * µs)} under the lock that hands out the sequence).
 *
 * <p>The clock is a {@link SettableClock} the test holds still, so every act
 * of this class is written within one instant of the clock: the store's times
 * are read back by SQL, and the token is held against a closing on the wire of
 * the kernel.
 */
@QuarkusTest
@TestProfile(SettableClock.Profile.class)
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class ActTimeIT {

    private static final Instant STILL = Instant.parse("2031-03-04T05:06:07.123456Z");
    private static final Actor CONSOLE = new Actor(InstanceWire.SUBJECT,
        DispatchPermission.INSTANCE_START, DispatchPermission.INSTANCE_CONCLUDE,
        DispatchPermission.INSTANCE_REFER);

    @Inject ActService acts;
    @Inject InstanceService instances;
    @Inject SettableClock clock;

    @Test
    void two_acts_within_one_microsecond_take_different_rising_times() {
        clock.set(STILL);
        UUID scope = UUID.randomUUID();
        InstanceFixture.Planted planted = InstanceFixture.plant(InstanceWire.TENANT, scope,
            "still");
        Provenance provenance = new Provenance("ada", null, DispatchPermission.INSTANCE_REFER,
            Provenance.Path.INTERFACE, null);

        Act first = acts.write(scope, planted.instance(), Act.Kind.REFER, Act.State.RUNNING, null,
            provenance);
        Act second = acts.write(scope, planted.instance(), Act.Kind.UNREFER, Act.State.RUNNING,
            null, provenance);

        assertThat(clock.instant())
            .as("RED STATE, observed: the clock stood still through both acts, so the clock "
                + "alone would give both the one time")
            .isEqualTo(STILL);
        assertThat(List.of(timeOf(first.id()), timeOf(second.id())))
            .as("the store holds two times, the second one microsecond after the first")
            .containsExactly(STILL, STILL.plusNanos(1000));
    }

    @Test
    void a_closing_under_the_token_of_an_earlier_act_in_the_same_microsecond_is_refused() {
        clock.set(STILL.plusSeconds(60));
        String selector = InstanceWire.plant("still-token").selector();
        InstanceView started = instances.start(CONSOLE, InstanceWire.SLUG, InstanceWire.SCOPE,
            selector, new InstanceService.Start("a title", null, null, List.of(), List.of(),
                Map.of("amount", 1)), provenance(DispatchPermission.INSTANCE_START));
        InstanceView attached = instances.attach(CONSOLE, started.address(), InstanceWire.SCOPE,
            ExternalReference.Kind.REFERENCE, "doc", provenance(DispatchPermission.INSTANCE_REFER));

        assertThat(attached.conflictToken())
            .as("within one instant of the clock, the act moved the token on")
            .isNotEqualTo(started.conflictToken());
        assertThatThrownBy(() -> close(started))
            .as("so a closing under the token of the start is refused as stale")
            .isInstanceOfSatisfying(DispatchException.class, refused -> assertThat(
                refused.reason()).isEqualTo(DispatchException.Reason.CONFLICT_TOKEN_STALE));
        assertThat(close(attached).state())
            .as("RED STATE, observed: the same closing under the current token closes")
            .isEqualTo("closed");
    }

    private InstanceView close(InstanceView standing) {
        return instances.close(CONSOLE, standing.address(), InstanceWire.SCOPE,
            Instance.Resolution.CONCLUDED, new InstanceService.Closing(standing.conflictToken(),
                null, "done", null, null, null), provenance(DispatchPermission.INSTANCE_CONCLUDE));
    }

    private static Provenance provenance(DispatchPermission permission) {
        return new Provenance(InstanceWire.SUBJECT, null, permission, Provenance.Path.INTERFACE,
            null);
    }

    private static Instant timeOf(long act) {
        return ((Timestamp) InstanceFixture.actRow(act).get("at")).toInstant();
    }
}
