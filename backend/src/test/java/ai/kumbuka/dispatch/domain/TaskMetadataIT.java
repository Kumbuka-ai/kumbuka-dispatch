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
import ai.kumbuka.dispatch.surface.TaskWire;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The free fields of a task -- the caller's own keys, stored and returned and
 * never evaluated (DEC-0056/C15) -- through the draft into the commission and
 * back out of a real database: a list stays a list and a string stays a
 * string, from the draft workspace to the free fields of the send act, and a
 * document that is no JSON object is refused before anything is written.
 *
 * <p>The successor of the cardinality of the metadata of the old kernel: the
 * service no longer checks the values, so what is proven is that the store
 * keeps their shape. The executor's free fields on a delivery stand below,
 * deactivated with the order that binds the delivery (T9).
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class TaskMetadataIT {

    private static final String FREE_FIELDS = "free_fields";

    @Inject TestIdentityAssociation identity;

    @BeforeEach
    void stage() {
        PlatformFixture.grantDirectoryAccess();
        InstanceWire.as(identity, InstanceWire.CONSOLE);
    }

    @Test
    void a_list_returns_as_the_list_and_a_string_as_the_string() {
        TaskWire.Planted planted = TaskWire.plant("free-fields", "review");
        Map<String, Object> created = InstanceWire.answer(InstanceWire.mcp(TaskWire.CREATE,
            TaskWire.create(planted.selector(), TaskWire.started(planted.selector()), Map.of(
                "title", "a title", FREE_FIELDS, Map.of("one", List.of("a"), "two", "b")))));
        long task = TaskWire.taskId(String.valueOf(created.get(InstanceWire.ADDRESS)));

        assertThat(InstanceWire.rows("SELECT jsonb_typeof(f.document -> 'one') AS one, "
                + "jsonb_typeof(f.document -> 'two') AS two FROM dispatch.draft_free_fields f "
                + "JOIN dispatch.draft d ON d.id = f.draft_id WHERE d.task_id = ?", task))
            .as("a list of one stays a list, and a string a string, in the draft")
            .containsExactly(Map.of("one", "array", "two", "string"));

        InstanceWire.answer(InstanceWire.mcp(TaskWire.SEND, TaskWire.on(created, Map.of())));

        assertThat(InstanceWire.rows("SELECT jsonb_typeof(f.document -> 'one') AS one, "
                + "jsonb_typeof(f.document -> 'two') AS two FROM dispatch.free_fields f "
                + "JOIN dispatch.act a ON a.id = f.act_id WHERE a.task_id = ?", task))
            .as("and so in the commission the send wrote")
            .containsExactly(Map.of("one", "array", "two", "string"));
    }

    @Test
    void a_document_that_is_no_object_is_refused_and_nothing_is_written() {
        TaskWire.Planted planted = TaskWire.plant("free-fields-form", "review");
        String instance = TaskWire.started(planted.selector());

        Response refused = InstanceWire.mcp(TaskWire.CREATE, TaskWire.create(planted.selector(),
            instance, Map.of("title", "a title", FREE_FIELDS, List.of("zz-not-an-object"))));

        Map<String, Object> envelope = InstanceWire.refusal(refused);
        assertThat(envelope.get("reason")).isEqualTo("ARGUMENT_INVALID");
        assertThat(InstanceWire.data(envelope).get("offenders"))
            .isEqualTo(List.of("fields.free_fields"));
        assertThat(refused.asString()).doesNotContain("zz-not-an-object");
        assertThat(InstanceWire.single("SELECT count(*) FROM dispatch.task WHERE instance_id = ?",
                InstanceWire.instanceId(instance)))
            .as("nothing was written").isZero();
        assertThat(InstanceWire.mcp(TaskWire.CREATE, TaskWire.create(planted.selector(), instance,
                Map.of("title", "a title", FREE_FIELDS, Map.of("k", List.of("v"))))).jsonPath()
                .getBoolean("result.isError"))
            .as("RED STATE, observed: an object is taken").isFalse();
    }

    @Test
    @Disabled("rebuilt in T9: the executor's free fields on a delivery keep their shape")
    void the_executor_s_free_fields_keep_their_shape_too() {
        fail("rebuilt in T9");
    }
}
