/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.tenancy;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static ai.kumbuka.dispatch.tenancy.CutoverStock.ACTOR;
import static ai.kumbuka.dispatch.tenancy.CutoverStock.LEGACY;
import static ai.kumbuka.dispatch.tenancy.CutoverStock.NOT_REFUSED;
import static ai.kumbuka.dispatch.tenancy.CutoverStock.OLD_SCHEMA;
import static ai.kumbuka.dispatch.tenancy.CutoverStock.SCOPE_A;
import static ai.kumbuka.dispatch.tenancy.CutoverStock.SCOPE_B;
import static ai.kumbuka.dispatch.tenancy.CutoverStock.SPRINT;
import static ai.kumbuka.dispatch.tenancy.CutoverStock.TENANT_A;
import static ai.kumbuka.dispatch.tenancy.CutoverStock.TENANT_B;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The refusals of the copy that the stock itself causes, each on a database
 * of its own: a derived act without its recorded actor, return_metadata on a
 * task without a return text, a bracket without its root task, an apparatus
 * the addressed role refuses, an addendum without a base text of its type;
 * and those of the elicitation complex: an answer without an ask act of its
 * task not after it, a question text of 2001 characters, an addendum to a
 * question without a base text to supplement.
 *
 * <p>Each case copies the stock of {@link CutoverStock#stage} first, which
 * runs: that is the red state of the refusal, observed on every build, since
 * the same stock without the offence is taken. Then it plants the one offence
 * into dispatch_legacy and runs the copy again. The refusal must name the
 * reason and the address, leave the new store holding exactly what the first
 * copy wrote, and say nothing of a title, a text or a value.
 */
class CutoverRefusalIT {

    private static final String LEAD = "lead-a";
    private static final String EXEC = "exec-a";

    /** A planting into tenant A of the renamed old store. */
    @FunctionalInterface
    interface Planting {
        void into(CutoverStock.Legacy store) throws SQLException;
    }

    /** The offences, each with the reason the copy names and the address of the task. */
    enum Offence {
        DERIVED_ACT_WITHOUT_ACTOR("an act the copy derives has no recorded actor", "1.1",
            l -> l.text(l.taskId(SPRINT, 1, 1), CutoverStock.ANSWER, null, 25, null)),
        RETURN_METADATA_WITHOUT_RETURN("return_metadata stands on a task without a return text",
            "1.1", l -> l.returningId("UPDATE dispatch_legacy.task SET return_metadata = "
                + "'{\"handover-pr\": \"https://example.org/acme/pull/99\"}'::jsonb "
                + "WHERE id = ? RETURNING id", l.taskId(SPRINT, 1, 1))),
        BRACKET_WITHOUT_ROOT("a bracket has no root task <number>.0", "9.1",
            l -> l.text(l.task(l.selectorId(SPRINT), 9, 1).in(CutoverStock.OPEN, 25, LEAD)
                .insert(), CutoverStock.DISPATCH, null, 25, LEAD)),
        APPARATUS_NOT_A_ROLE("an apparatus is not admitted as addressed_role", "1.13",
            l -> l.task(l.selectorId(SPRINT), 1, 13).apparatus("reviewer lead")
                .in(CutoverStock.DRAFT, 25, LEAD).insert()),
        ADDENDUM_WITHOUT_BASE("an addendum has no base text of its type", "1.1a",
            l -> l.text(l.taskId(SPRINT, 1, 1), CutoverStock.RETURN, "a", 25, EXEC)),
        ANSWER_WITHOUT_ASK("an answer has no ask act of its task that is not after it", "1.1",
            l -> l.text(l.taskId(SPRINT, 1, 1), CutoverStock.ANSWER, null, 25, LEAD)),
        QUESTION_TEXT_TOO_LONG("a question text has more than 2000 characters", "1.1",
            l -> l.text(l.taskId(SPRINT, 1, 1), CutoverStock.QUESTION, null, 25, EXEC,
                "q".repeat(2001))),
        ADDENDUM_TO_A_QUESTION_WITHOUT_BASE(
            "an addendum to a question or an answer has no base text to supplement", "1.1a",
            l -> l.text(l.taskId(SPRINT, 1, 1), CutoverStock.QUESTION, "a", 25, EXEC));

        private final String reason;
        private final String id;
        private final Planting planting;

        Offence(String reason, String id, Planting planting) {
            this.reason = reason;
            this.id = id;
            this.planting = planting;
        }
    }

    private static MigrationHarness harness;

    @BeforeAll
    static void start() throws SQLException {
        harness = MigrationHarness.start();
        harness.createMigrator(CutoverStock.MIGRATOR, CutoverStock.MIGRATOR_PASSWORD,
            "CREATEROLE NOSUPERUSER NOBYPASSRLS");
    }

    @AfterAll
    static void stop() {
        if (harness != null) {
            harness.close();
        }
    }

    @ParameterizedTest
    @EnumSource(Offence.class)
    void the_copy_refuses_the_offence_without_writing_and_names_its_address(Offence offence)
            throws SQLException {
        String url = CutoverStock.oldStore(harness,
            "refusal_" + offence.name().toLowerCase(Locale.ROOT));
        try (Connection admin = harness.adminConnection(url)) {
            CutoverStock.stage(admin, TENANT_A, SCOPE_A, "a");
            CutoverStock.stage(admin, TENANT_B, SCOPE_B, "b");
        }
        try (Connection owner = CutoverStock.asMigrator(url)) {
            assertThat(CutoverStock.outcome(owner, CutoverStock.RENAME)).as("the rename ran")
                .isEqualTo(NOT_REFUSED);
        }
        CutoverStock.newStore(harness, url);

        try (Connection admin = harness.adminConnection(url)) {
            String clean = CutoverStock.copy(admin, ACTOR);
            Map<String, String> copied = CutoverStock.rows(admin, OLD_SCHEMA);

            offence.planting.into(new CutoverStock.Legacy(admin, LEGACY, TENANT_A, SCOPE_A, "a"));
            Set<String> planted = CutoverStock.planted(admin, LEGACY);
            String refused = CutoverStock.copy(admin, ACTOR);
            Map<String, String> after = CutoverStock.rows(admin, OLD_SCHEMA);

            assertThat(clean)
                .as("RED STATE, observed: the same stock without the offence is copied")
                .isEqualTo(NOT_REFUSED);
            assertThat(refused)
                .as("the copy refuses %s, naming the reason and the address", offence)
                .isEqualTo("cutover copy refused: " + offence.reason + ", at "
                    + CutoverStock.address(SCOPE_A, SPRINT, offence.id));
            assertThat(after)
                .as("and writes nothing: the new store holds exactly what the first copy wrote")
                .isEqualTo(copied);
            assertThat(planted.stream().filter(refused::contains).toList())
                .as("the refusal carries no title, text or metadata value of the stock")
                .isEmpty();
        }
    }
}
