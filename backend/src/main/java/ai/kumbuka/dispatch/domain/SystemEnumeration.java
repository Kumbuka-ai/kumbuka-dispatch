/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import java.util.Arrays;
import java.util.List;

/**
 * The system fields of the platform that have a fixed set of values, offered
 * to every reader of a schema as enumerations of the same form as the
 * customer's (DEC-0056/C5).
 *
 * <p>The values are the ones the store line fixes for the system field by a
 * CHECK of its own, read off the migrations V1 to V5 of {@code db/store}, in
 * the order of that CHECK: the state of a task ({@code ck_task_state}), the
 * state of an instance ({@code ck_instance_state}), the kind of an act ({@code
 * ck_act_kind}), the verdict ({@code ck_delivery_verdict}), the outcome of a
 * closed task ({@code ck_task_resolution}), the reason of a hold ({@code
 * ck_task_hold_reason}), the status of a criterion ({@code
 * ck_delivery_criterion_status}) and the path of an act ({@code ck_act_path}).
 * They are constants here, and {@code ReadDefinitionIT}, in its test {@code
 * every_system_enumeration_of_the_answer_is_the_values_of_its_check_in_their_order},
 * holds each, as {@code read_definition} answers it, against the literals of
 * its CHECK read from {@code pg_constraint} of the migrated store, values and
 * order alike, so the expectation does not come from this class.
 */
public enum SystemEnumeration {

    TASK_STATE("task_state",
        List.of("draft", "recalled", "open", "active", "on_hold", "delivered", "closed")),
    INSTANCE_STATE("instance_state", List.of("running", "closed")),
    ACT_KIND("act_kind", Arrays.stream(Act.Kind.values()).map(Act.Kind::wireName).toList()),
    VERDICT("verdict", List.of("green", "red", "question")),
    TASK_RESOLUTION("task_resolution", List.of("accepted", "rejected", "failed", "withdrawn")),
    HOLD_REASON("hold_reason", List.of("question", "dependency", "external")),
    CRITERION_STATUS("criterion_status", List.of("met", "not_met", "not_applicable")),
    PATH("path", List.of("interface", "console"));

    private final String wireName;
    private final List<String> members;

    SystemEnumeration(String wireName, List<String> members) {
        this.wireName = wireName;
        this.members = List.copyOf(members);
    }

    /** The name the enumeration goes by in a schema, in the form of a customer's. */
    public String wireName() {
        return wireName;
    }

    /** The values of the system field, in the order of the store's CHECK. */
    public List<String> members() {
        return members;
    }
}
