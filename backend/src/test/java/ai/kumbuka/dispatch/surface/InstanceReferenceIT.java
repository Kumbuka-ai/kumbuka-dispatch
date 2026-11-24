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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code reference_attach} and {@code reference_detach} on a running instance
 * (D12, D22): a reference of each kind is set by an act {@code refer} and
 * ended by an act {@code unrefer}, which stands in the reference's column;
 * setting the same reference twice leaves one reference and one act; ending
 * a reference the instance does not carry is refused, and an ended reference
 * stays with its act and is not ended a second time; the trigger is no kind
 * a call ends. Each refusal stands beside its red state.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class InstanceReferenceIT {

    private static final String ATTACH = "dispatch_reference_attach";
    private static final String DETACH = "dispatch_reference_detach";
    private static final String ADDRESS = "address";
    private static final String FIELDS = "fields";
    private static final String KIND = "kind";
    private static final String VALUE = "value";
    private static final String ITEM = "item-ref-42";
    private static final String DOCUMENT = "https://example.org/doc/42";
    private static final String OF_KIND_REFERENCE = "reference";
    private static final String REFER = "refer";
    private static final String REASON = "reason";
    private static final String INVALID = "ARGUMENT_INVALID";
    private static final String REFERENCES = "SELECT count(*) FROM dispatch.external_reference ";
    private static final String ACTS = "SELECT count(*) FROM dispatch.act WHERE instance_id = ? ";

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        PlatformFixture.grantDirectoryAccess();
        InstanceWire.as(identity, InstanceWire.CONSOLE);
    }

    @Test
    void a_reference_of_each_kind_is_set_and_ended_by_an_act_each() {
        String address = InstanceWire.started(InstanceWire.plant("refer").selector());
        long instance = InstanceWire.instanceId(address);

        InstanceWire.answer(InstanceWire.mcp(ATTACH, reference(address, "item", ITEM)));
        Response overRest = InstanceWire.rest(address, "reference_attach",
            Map.of(FIELDS, Map.of(KIND, OF_KIND_REFERENCE, VALUE, DOCUMENT)), null);
        assertThat(overRest.statusCode()).as(overRest.asString()).isEqualTo(200);

        assertThat(InstanceWire.rows("SELECT r.kind, r.value, a.kind AS set_by, "
                + "(r.ended_by_act_id IS NULL) AS holds FROM dispatch.external_reference r "
                + "JOIN dispatch.act a ON a.id = r.set_by_act_id "
                + "WHERE r.instance_id = ? AND r.kind <> 'trigger' ORDER BY r.id", instance))
            .as("each reference set by an act refer of its own, and holding")
            .containsExactly(
                Map.of(KIND, "item", VALUE, ITEM, "set_by", REFER, "holds", true),
                Map.of(KIND, OF_KIND_REFERENCE, VALUE, DOCUMENT, "set_by", REFER, "holds", true));

        InstanceWire.answer(InstanceWire.mcp(DETACH, reference(address, "item", ITEM)));
        assertThat(InstanceWire.rows("SELECT a.kind, (a.task_id IS NULL) AS on_the_instance "
                + "FROM dispatch.external_reference r "
                + "JOIN dispatch.act a ON a.id = r.ended_by_act_id WHERE r.instance_id = ? "
                + "AND r.kind = 'item'", instance))
            .as("the end of the item stands as an act unrefer in its column (D22)")
            .containsExactly(Map.of(KIND, "unrefer", "on_the_instance", true));
        assertThat(InstanceWire.single(REFERENCES
                + "WHERE instance_id = ? AND kind = 'item'", instance))
            .as("and the ended reference stays").isEqualTo(1);
        assertThat(InstanceWire.rows("SELECT kind FROM dispatch.act WHERE instance_id = ? "
                + "ORDER BY sequence", instance))
            .as("one act each in the trace")
            .extracting(row -> row.get(KIND))
            .containsExactly("start", REFER, REFER, "unrefer");
    }

    @Test
    void the_same_reference_set_twice_leaves_one_reference_and_one_act() {
        String address = InstanceWire.started(InstanceWire.plant("twice").selector());
        long instance = InstanceWire.instanceId(address);

        Map<String, Object> first = InstanceWire.answer(InstanceWire.mcp(ATTACH,
            reference(address, "item", ITEM)));
        Map<String, Object> second = InstanceWire.answer(InstanceWire.mcp(ATTACH,
            reference(address, "item", ITEM)));

        assertThat(InstanceWire.single(REFERENCES
                + "WHERE instance_id = ? AND kind = 'item'", instance))
            .as("one reference").isEqualTo(1);
        assertThat(InstanceWire.single(ACTS
                + "AND kind = 'refer'", instance))
            .as("and one act").isEqualTo(1);
        assertThat(InstanceWire.token(second)).as("the second wrote nothing, so the token stands")
            .isEqualTo(InstanceWire.token(first));

        InstanceWire.answer(InstanceWire.mcp(ATTACH, reference(address, OF_KIND_REFERENCE, ITEM)));
        assertThat(InstanceWire.single(REFERENCES
                + "WHERE instance_id = ? AND value = ?", instance, ITEM))
            .as("RED STATE, observed: the same value under the other kind is another reference, "
                + "set by an act of its own")
            .isEqualTo(2);
    }

    @Test
    void a_reference_the_instance_does_not_carry_is_not_ended_and_an_ended_one_not_twice() {
        String address = InstanceWire.started(InstanceWire.plant("absent").selector());
        long instance = InstanceWire.instanceId(address);

        Map<String, Object> refused = InstanceWire.refusal(InstanceWire.mcp(DETACH,
            reference(address, "item", ITEM)));
        assertThat(refused.get(REASON)).isEqualTo(INVALID);
        assertThat(InstanceWire.data(refused).get("offenders")).isEqualTo(List.of("fields.value"));
        assertThat(String.valueOf(refused)).as("naming no value sent").doesNotContain(ITEM);

        InstanceWire.answer(InstanceWire.mcp(ATTACH, reference(address, "item", ITEM)));
        assertThat(InstanceWire.mcp(DETACH, reference(address, "item", ITEM))
                .jsonPath().getBoolean("result.isError"))
            .as("RED STATE, observed: once the instance carries it, the same ending is taken")
            .isFalse();

        assertThat(InstanceWire.refusal(InstanceWire.mcp(DETACH,
                reference(address, "item", ITEM))).get(REASON))
            .as("a second ending of the ended reference is refused")
            .isEqualTo(INVALID);
        assertThat(InstanceWire.single(ACTS
                + "AND kind = 'unrefer'", instance))
            .as("and writes no second act").isEqualTo(1);
    }

    @Test
    void the_trigger_is_no_kind_a_call_ends() {
        InstanceWire.Planted planted = InstanceWire.plant("started");
        Map<String, Object> fields = new java.util.HashMap<>(InstanceWire.minimalFields("t"));
        fields.put("trigger", ITEM);
        String address = String.valueOf(InstanceWire.answer(InstanceWire.mcp("dispatch_start",
            InstanceWire.startArguments(planted.selector(), fields))).get(ADDRESS));

        Map<String, Object> refused = InstanceWire.refusal(InstanceWire.mcp(DETACH,
            reference(address, "trigger", ITEM)));
        assertThat(refused.get(REASON)).isEqualTo(INVALID);
        assertThat(InstanceWire.data(refused).get("offenders")).isEqualTo(List.of("fields.kind"));
        assertThat(InstanceWire.refusal(InstanceWire.mcp(DETACH,
                reference(address, "item", ITEM))).get(REASON))
            .as("RED STATE, observed: the value of the trigger under a kind a call ends is a "
                + "reference the instance does not carry")
            .isEqualTo(INVALID);
        assertThat(InstanceWire.rows("SELECT ended_by_act_id FROM dispatch.external_reference "
                + "WHERE instance_id = ? AND kind = 'trigger'", InstanceWire.instanceId(address)))
            .as("the trigger stands, never ended")
            .singleElement().satisfies(row -> assertThat(row.get("ended_by_act_id")).isNull());
    }

    private static Map<String, Object> reference(String address, String kind, String value) {
        return Map.of(ADDRESS, address, FIELDS, Map.of(KIND, kind, VALUE, value));
    }
}
