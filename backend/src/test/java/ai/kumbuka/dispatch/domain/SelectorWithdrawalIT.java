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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a selector takes, rebuilt from the withdrawal of a selector of the old
 * kernel onto the store line, which has no withdrawal: a selector is declared
 * by a migration together with its definition (REA-0010 D10), and it takes an
 * instance only under a version that is active and seeded. A selector without
 * such a version takes none, answers {@code NOT_FOUND} as an unknown one does,
 * and its counter stands: nothing is numbered under it.
 *
 * <p>Every version is planted by SQL; the red state of each case is the same
 * start once the version it lacked stands, which is taken and numbered.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class SelectorWithdrawalIT {

    private static final String REASON = "result.structuredContent.reason";
    private static final String NOT_FOUND = "NOT_FOUND";

    @Inject TestIdentityAssociation identity;

    private final DefinitionFixture plant = new DefinitionFixture(InstanceWire.TENANT,
        InstanceWire.SCOPE);

    @BeforeEach
    void stage() {
        PlatformFixture.grantDirectoryAccess();
        InstanceWire.as(identity, InstanceWire.CONSOLE);
    }

    @Test
    void a_selector_without_a_version_takes_no_instance_and_numbers_nothing() {
        String name = "bare-" + InstanceWire.suffix();
        long selector = plant.selector(name);

        assertNothingStarted(name, "a selector with no version at all");

        plant.version(selector, 1, "active", true, false, false);
        assertStarted(name, "RED STATE, observed: once an active seeded version stands, the "
            + "same start is taken and numbered");
    }

    @Test
    void a_selector_whose_versions_are_retired_or_unseeded_takes_no_instance() {
        String name = "retired-" + InstanceWire.suffix();
        long selector = plant.selector(name);
        plant.version(selector, 1, "retired", true, false, false);
        long unseeded = plant.version(selector, 2, "active", false, false, false);

        assertNothingStarted(name, "a retired version and an active one that is no seed");

        DefinitionFixture.markSeed(unseeded, true);
        assertStarted(name, "RED STATE, observed: with the active version marked as a seed, "
            + "the same start is taken and numbered");
    }

    @Test
    void an_unknown_selector_answers_as_a_selector_without_a_version_does() {
        String bare = "unversioned-" + InstanceWire.suffix();
        plant.selector(bare);

        Response unknown = start("unknown-" + InstanceWire.suffix());
        Response unversioned = start(bare);
        assertThat(List.of(unknown.jsonPath().getString(REASON),
                unversioned.jsonPath().getString(REASON)))
            .containsExactly(NOT_FOUND, NOT_FOUND);
        assertThat(unknown.jsonPath().getString("result.content[0].text"))
            .as("byte for byte, saying nothing of which it was")
            .isEqualTo(unversioned.jsonPath().getString("result.content[0].text"));
    }

    // -----------------------------------------------------------------------

    private static Response start(String selector) {
        return InstanceWire.mcp("dispatch_start",
            InstanceWire.startArguments(selector, java.util.Map.of("title", "a title")));
    }

    private static void assertNothingStarted(String selector, String why) {
        Response refused = start(selector);
        assertThat(refused.jsonPath().getString(REASON))
            .as("%s: NOT_FOUND", why).isEqualTo(NOT_FOUND);
        assertThat(InstanceWire.single("SELECT count(*) FROM dispatch.instance i "
                + "JOIN dispatch.selector s ON s.id = i.selector_id WHERE s.name = ?", selector))
            .as("no instance under it").isZero();
        assertThat(InstanceWire.single("SELECT next_number FROM dispatch.selector "
                + "WHERE name = ?", selector))
            .as("and its counter stands").isEqualTo(1);
    }

    private static void assertStarted(String selector, String redState) {
        Response taken = start(selector);
        assertThat(taken.jsonPath().getBoolean("result.isError")).as(redState).isFalse();
        assertThat(InstanceWire.single("SELECT next_number FROM dispatch.selector "
                + "WHERE name = ?", selector))
            .as("and the counter moved on").isEqualTo(2);
    }
}
