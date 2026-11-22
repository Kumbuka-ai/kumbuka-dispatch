/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * The nine calls that are not transitions (TAR-0004 section 3), and the
 * separation of process and text (section 6, acceptance criterion 6),
 * against a running database under the service role.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
@Disabled(TaskCallsIT.REBUILT)
class TaskCallsIT {

    static final String REBUILT = "rebuilt in T4: a draft is created, updated and deleted under "
        + "its idempotency key and no writing call answers with its text";

    @Test
    void create_begins_in_draft_numbers_the_bracket_and_numbers_children_within_it() {
        fail(REBUILT);
    }

    /**
     * The number is taken in the creating transaction, so a create refused after
     * it was taken gives it back. The kernel refuses nothing of its own after
     * the number is taken; the database does, and a text with a NUL character
     * is what it refuses there.
     */
    @Test
    void a_refused_create_leaves_no_gap_in_the_numbers() {
        fail(REBUILT);
    }

    @Test
    void a_repeat_under_one_key_creates_nothing_and_the_key_on_another_call_is_refused() {
        fail(REBUILT);
    }

    @Test
    void update_changes_a_draft_and_is_refused_empty_and_once_sent() {
        fail(REBUILT);
    }

    @Test
    void delete_removes_a_draft_outright_and_is_refused_once_sent() {
        fail(REBUILT);
    }

    @Test
    void no_transition_and_no_writing_call_answers_with_a_text() {
        fail(REBUILT);
    }

    @Test
    void read_text_gives_the_commissioner_every_part_and_an_executor_only_what_it_holds() {
        fail(REBUILT);
    }

    @Test
    void read_text_answers_one_part_and_counts_the_addenda_of_it() {
        fail(REBUILT);
    }

    @Test
    void annotate_is_the_writer_of_the_text_and_never_on_a_draft() {
        fail(REBUILT);
    }

    @Test
    void query_filters_on_the_effective_state_apparatus_bracket_and_address() {
        fail(REBUILT);
    }

    @Test
    void query_cuts_at_the_page_size_and_hands_out_a_cursor_only_while_more_follow() {
        fail(REBUILT);
    }

    @Test
    void relate_records_a_curation_on_a_closed_task_and_unrelate_removes_it() {
        fail(REBUILT);
    }

    @Test
    void send_freezes_the_draft_s_metadata_and_metadata_is_validated_everywhere() {
        fail(REBUILT);
    }

    @Test
    void create_needs_a_declared_selector_and_an_existing_root() {
        fail(REBUILT);
    }

    @Test
    void the_stamps_name_the_caller_and_reading_writes_nothing() {
        fail(REBUILT);
    }

    @Test
    void a_suffix_past_z_is_refused_and_never_wrapped() {
        fail(REBUILT);
    }

    @Test
    void a_repeated_annotation_under_one_key_attaches_once() {
        fail(REBUILT);
    }

    @Test
    void a_spent_key_is_remembered_for_twenty_four_hours_and_no_longer() {
        fail(REBUILT);
    }
}
