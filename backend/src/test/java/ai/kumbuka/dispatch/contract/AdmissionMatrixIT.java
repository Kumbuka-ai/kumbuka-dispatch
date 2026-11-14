/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.contract;

import ai.kumbuka.dispatch.platform.PlatformFixture;
import ai.kumbuka.dispatch.surface.SurfaceFixture;
import ai.kumbuka.dispatch.surface.VerbSurfaceSpecification;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestIdentityAssociation;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The admission of today's service, recorded before its calls are moved onto
 * named permissions: for every call of the surface, every combination of the
 * two realm roles and every relation of the caller to the task, whether the
 * call is admitted, and if not, with which code.
 *
 * <p>The expectation is {@code admission-matrix.tsv} in the test tree, written
 * cell by cell and never read from the service: it records what the service
 * does, including where that differs from what its transition table suggests.
 * Every row is staged afresh and made over the REST surface under the identity
 * its roles name, presenting what the call's proof asks, so that the caller
 * decides and not the request.
 *
 * <p>Three cases beside the comparison hold the table and the comparison to
 * account: the table carries every call of {@code verb-surface.tsv} with each of
 * the four role combinations, and both checks report what they exist for on
 * every build -- a planted table missing a combination, and a planted copy of
 * the table with cells written wrong.
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class AdmissionMatrixIT {

    /** The expectation, on the classpath. */
    private static final String TABLE = "admission-matrix.tsv";

    /** A test copy of the expectation with cells written wrong on purpose. */
    private static final String PLANTED = "admission-matrix-planted.tsv";

    private static final List<String> ROLES = List.of("console", "executor", "both", "none");

    private static final Set<String> RELATIONS =
        Set.of("commissioner", "holder", "candidate", "bystander", "none");

    private static final Set<String> STATES = Set.of("none", "draft", "open", "active",
        "on_hold (question)", "on_hold (dependency)", "delivered", "closed");

    private static final String ADMITTED = "admitted";
    private static final String REFUSED = "refused";

    private static final String IF_MATCH = "If-Match";
    private static final String FIELDS = "fields";
    private static final String RECEIPT = "receipt";

    @Inject TestIdentityAssociation identity;

    /** The prefix of every apparatus and tag this run stages, so no other probe draws them. */
    private String run;

    @BeforeEach
    void stage() {
        SurfaceFixture.stage();
        run = "admission-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** One row of the table. */
    record Cell(String call, String roles, String relation, String state, String outcome,
                String code) {

        String name() {
            return String.join(" | ", call, roles, relation, state);
        }

        String expected() {
            return ADMITTED.equals(outcome) ? ADMITTED : REFUSED + " " + code;
        }
    }

    /**
     * What the service answered one row.
     *
     * @param reason the refusal's code; absent where the call was answered
     */
    record Observed(Cell cell, int status, @Nullable String reason, String body) {

        String outcome() {
            if (status >= 200 && status < 300) {
                return ADMITTED;
            }
            return REFUSED + " " + (reason == null ? "with status " + status + " and no reason"
                : reason);
        }
    }

    /**
     * What a row is made on: the task it concerns, and what its proof and
     * payload need.
     *
     * @param id        the task's {@code <number>.<sub>}; absent where the call concerns none
     * @param receipt   the holder's receipt; absent where nobody holds the task
     * @param token     the task's conflict token; absent with the task
     * @param apparatus the apparatus a draw names; absent on every other call
     * @param target    the task a relation is curated into; absent on every other call
     * @param tag       the declared tag a call on the tags names; absent on every other call
     */
    record Staged(@Nullable String id, @Nullable String receipt, @Nullable String token,
                  @Nullable String apparatus, @Nullable String target, @Nullable String tag) {
    }

    // =======================================================================
    // The table covers the surface
    // =======================================================================

    @Test
    void the_table_carries_every_call_of_the_surface_with_every_role_combination() {
        assertThat(uncovered(VerbSurfaceSpecification.calls(), table(TABLE)))
            .as("every call of verb-surface.tsv stands in admission-matrix.tsv with console, "
                + "executor, both and none, and the table names no call beside them")
            .isEmpty();
    }

    /** The red state of the completeness check, observed on every build. */
    @Test
    void the_completeness_check_reports_a_missing_combination_and_a_foreign_call() {
        List<Cell> planted = List.of(
            new Cell("query", "console", "none", "none", ADMITTED, ""),
            new Cell("query", "executor", "none", "none", ADMITTED, ""),
            new Cell("query", "both", "none", "none", ADMITTED, ""),
            new Cell("invented", "none", "none", "none", REFUSED, "ROLE_INSUFFICIENT"));
        assertThat(uncovered(List.of("query"), planted))
            .as("RED STATE, observed: a call without a row for one role combination, and a "
                + "row naming no call of the surface, are reported")
            .containsExactly("query | none: no row",
                "invented | none | none | none: no call of verb-surface.tsv");
    }

    // =======================================================================
    // The service decides every cell as the table says
    // =======================================================================

    @Test
    void every_cell_is_admitted_or_refused_as_the_table_records_it() {
        List<Observed> observed = table(TABLE).stream().map(this::observe).toList();
        List<String> offences = offences(observed);
        assertThat(offences)
            .as("the service admits and refuses each cell as admission-matrix.tsv records it; "
                + "the answers of the cells that differ: %s", answersOf(observed))
            .isEmpty();
    }

    /** The red state of the comparison, observed on every build against the service. */
    @Test
    void the_comparison_reports_each_cell_the_service_decides_otherwise() {
        List<Observed> observed = table(PLANTED).stream().map(this::observe).toList();
        assertThat(offences(observed))
            .as("RED STATE, observed: in a test copy of the table, a cell written admitted "
                + "that the service refuses, and a cell written with another code than the "
                + "service answers, are reported; the cell written right beside them is not")
            .containsExactly(
                "claim | console | candidate | open: expected admitted, observed refused "
                    + "ROLE_INSUFFICIENT",
                "release | console | commissioner | active: expected refused "
                    + "ROLE_INSUFFICIENT, observed refused CLAIM_HELD");
    }

    // =======================================================================
    // The checks
    // =======================================================================

    /**
     * Every call of the surface without a row for one of the four role
     * combinations, and every row naming a call the surface does not carry;
     * empty where the table covers the surface.
     */
    static List<String> uncovered(List<String> calls, List<Cell> cells) {
        List<String> found = new ArrayList<>();
        for (String call : calls) {
            for (String roles : ROLES) {
                if (cells.stream().noneMatch(c -> c.call().equals(call) && c.roles().equals(roles))) {
                    found.add(call + " | " + roles + ": no row");
                }
            }
        }
        for (Cell cell : cells) {
            if (!calls.contains(cell.call())) {
                found.add(cell.name() + ": no call of verb-surface.tsv");
            }
        }
        return found;
    }

    /** Every row the service decided otherwise than it says; empty where none. */
    static List<String> offences(List<Observed> observed) {
        List<String> found = new ArrayList<>();
        for (Observed one : observed) {
            if (!one.outcome().equals(one.cell().expected())) {
                found.add(one.cell().name() + ": expected " + one.cell().expected()
                    + ", observed " + one.outcome());
            }
        }
        return found;
    }

    /** The answers of the rows decided otherwise, for the reader of a red run. */
    private static List<String> answersOf(List<Observed> observed) {
        return observed.stream()
            .filter(one -> !one.outcome().equals(one.cell().expected()))
            .map(one -> one.cell().name() + " -> " + one.status() + " " + one.body())
            .toList();
    }

    /** The rows of a table on the classpath, each checked for its form. */
    static List<Cell> table(String resource) {
        List<Cell> cells = new ArrayList<>();
        try (InputStream in = AdmissionMatrixIT.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertThat(in).as("%s must be on the classpath", resource).isNotNull();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .lines().toList()) {
                if (line.isBlank() || line.startsWith("#") || line.startsWith("call\t")) {
                    continue;
                }
                String[] c = line.split("\t", -1);
                assertThat(c.length)
                    .as("a row carries five cells, or six with a code: '%s'", line)
                    .isBetween(5, 6);
                Cell cell = new Cell(c[0], c[1], c[2], c[3], c[4], c.length == 6 ? c[5] : "");
                assertThat(ROLES).as("the roles of '%s'", line).contains(cell.roles());
                assertThat(RELATIONS).as("the relation of '%s'", line).contains(cell.relation());
                assertThat(STATES).as("the state of '%s'", line).contains(cell.state());
                assertThat(cell.outcome()).as("the outcome of '%s'", line)
                    .isIn(ADMITTED, REFUSED);
                assertThat(cell.code().isEmpty())
                    .as("a code stands exactly where the row is refused: '%s'", line)
                    .isEqualTo(ADMITTED.equals(cell.outcome()));
                assertThat(cells).extracting(Cell::name)
                    .as("every cell stands once: '%s'", line).doesNotContain(cell.name());
                cells.add(cell);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        assertThat(cells).as("an empty table would pass every comparison").isNotEmpty();
        return cells;
    }

    // =======================================================================
    // One row, staged and made
    // =======================================================================

    private Observed observe(Cell cell) {
        Staged at = staged(cell);
        as(cell.roles());
        Response answer = request(cell.call(), at);
        int status = answer.statusCode();
        return new Observed(cell, status, status >= 300 ? reasonOf(answer) : null,
            answer.asString());
    }

    private static @Nullable String reasonOf(Response answer) {
        try {
            return answer.jsonPath().getString("reason");
        } catch (RuntimeException notJson) {
            return null;
        }
    }

    /** The identity the roles of a row name. */
    private void as(String roles) {
        switch (roles) {
            case "console" -> SurfaceFixture.asConsole(identity);
            case "executor" -> SurfaceFixture.asExecutor(identity);
            case "both" -> SurfaceFixture.asBoth(identity);
            case "none" -> SurfaceFixture.asCapacitylessToken(identity);
            default -> throw new IllegalArgumentException("no identity for the roles " + roles);
        }
    }

    /**
     * Brings a task into the state of the row, under the commissioner and the
     * holder its relation names: the caller where the relation is its own,
     * another console identity and another executor otherwise.
     */
    private Staged staged(Cell cell) {
        if (cell.call().equals("claim_next")) {
            String apparatus = unique();
            SurfaceFixture.asOtherConsole(identity);
            sent(created(apparatus));
            return new Staged(null, null, null, apparatus, null, null);
        }
        if (cell.state().equals("none")) {
            return new Staged(null, null, null, null, null, null);
        }

        Runnable commissioner = cell.relation().equals("commissioner")
            ? () -> as(cell.roles())
            : () -> SurfaceFixture.asOtherConsole(identity);
        Runnable holder = cell.relation().equals("holder")
            ? () -> as(cell.roles())
            : () -> SurfaceFixture.asOtherExecutor(identity);

        commissioner.run();
        String id = created(run);
        @Nullable String receipt = null;
        switch (cell.state()) {
            case "draft" -> {
                // created is all a draft takes
            }
            case "open" -> sent(id);
            case "closed" -> {
                sent(id);
                step("withdraw", given().header(IF_MATCH, token(id))
                    .post(SurfaceFixture.item(id) + ":withdraw"));
            }
            default -> {
                sent(id);
                holder.run();
                receipt = step("claim", given().post(SurfaceFixture.item(id) + ":claim"))
                    .jsonPath().getString(RECEIPT);
                paused(cell.state(), id, receipt);
            }
        }

        SurfaceFixture.asOtherConsole(identity);
        @Nullable String target = null;
        if (cell.call().equals("relate")) {
            target = created(run);
        }
        @Nullable String tag = null;
        if (cell.call().startsWith("tag_")) {
            tag = declaredTag();
            if (cell.call().equals("tag_detach")) {
                step("tag_attach", json(Map.of(FIELDS, Map.of("tag", tag)))
                    .post(SurfaceFixture.item(id) + ":tag_attach"));
            }
        }
        return new Staged(id, receipt, token(id), null, target, tag);
    }

    /** Moves an active task on to the state named, as its holder. */
    private static void paused(String state, String id, String receipt) {
        String item = SurfaceFixture.item(id);
        switch (state) {
            case "active" -> {
                // claimed is all an active task takes
            }
            case "on_hold (question)" -> step("ask", json(withReceipt(receipt,
                    Map.of("question", "which way?", "options", List.of("yes", "no"))))
                .post(item + ":ask"));
            case "on_hold (dependency)" -> step("hold", json(withReceipt(receipt,
                    Map.of("reason", "dependency"))).post(item + ":hold"));
            case "delivered" -> step("deliver", json(withReceipt(receipt,
                    Map.of("text", "the answer"))).post(item + ":deliver"));
            default -> throw new IllegalArgumentException("no staging for the state " + state);
        }
    }

    /**
     * The request of one call, as the current identity, presenting what the
     * call's proof and payload ask.
     */
    private Response request(String call, Staged at) {
        String item = at.id() == null ? "" : SurfaceFixture.item(at.id());
        String collection = SurfaceFixture.collection();
        return switch (call) {
            case "send", "recall", "accept", "withdraw", "unrelate" ->
                given().header(IF_MATCH, at.token()).post(item + ":" + call);
            case "claim" -> given().post(item + ":claim");
            case "claim_next" -> json(Map.of("apparatus", List.of(at.apparatus())))
                .post(collection + ":claim_next");
            case "release", "renew", "resume" ->
                json(Map.of(RECEIPT, at.receipt())).post(item + ":" + call);
            case "defer" -> json(withReceipt(at.receipt(), Map.of("not_before",
                    Instant.now().plus(Duration.ofDays(1)).toString())))
                .post(item + ":defer");
            case "ask" -> json(withReceipt(at.receipt(),
                    Map.of("question", "which way?", "options", List.of("yes", "no"))))
                .post(item + ":ask");
            case "hold" -> json(withReceipt(at.receipt(), Map.of("reason", "dependency")))
                .post(item + ":hold");
            case "deliver" -> json(withReceipt(at.receipt(), Map.of("text", "the answer")))
                .post(item + ":deliver");
            case "fail" -> json(withReceipt(at.receipt(), Map.of("remark", "it failed")))
                .post(item + ":fail");
            case "answer" -> json(Map.of(FIELDS, Map.of("option", "yes")))
                .header(IF_MATCH, at.token()).post(item + ":answer");
            case "rework" -> json(Map.of(FIELDS, Map.of("remark", "once more")))
                .header(IF_MATCH, at.token()).post(item + ":rework");
            case "reject" -> json(Map.of(FIELDS, Map.of("remark", "not for us")))
                .post(item + ":reject");
            case "create" -> json(creation(at)).post(collection);
            case "update" -> json(Map.of(FIELDS, Map.of("title", "changed")))
                .header(IF_MATCH, at.token()).patch(item);
            case "delete" -> given().header(IF_MATCH, at.token()).delete(item);
            case "read" -> given().get(item);
            case "read_text" -> given().get(item + ":read_text?part=dispatch");
            case "query" -> given().get(collection);
            case "annotate" -> json(Map.of(FIELDS, Map.of("part", "dispatch",
                    "text", "an addendum"))).post(item + ":annotate");
            case "relate" -> json(Map.of(FIELDS,
                    Map.of("curated_in", SurfaceFixture.address(at.target()))))
                .header(IF_MATCH, at.token()).post(item + ":relate");
            case "tag_attach", "tag_detach" -> json(Map.of(FIELDS, Map.of("tag", at.tag())))
                .post(item + ":" + call);
            default -> throw new IllegalArgumentException("no request for the call " + call);
        };
    }

    /** The body of a create: a draft, under the parent the row staged where it names one. */
    private Map<String, Object> creation(Staged at) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (at.id() != null) {
            body.put("parent", SurfaceFixture.address(at.id()));
        }
        body.put(FIELDS, Map.of("title", "an admission cell", "apparatus", run,
            "text", "the commission"));
        return body;
    }

    // -----------------------------------------------------------------------

    /** Creates a draft as the current identity; answers its {@code <number>.<sub>}. */
    private static String created(String apparatus) {
        Response created = json(Map.of(FIELDS, Map.of("title", "an admission cell",
            "apparatus", apparatus, "text", "the commission"))).post(SurfaceFixture.collection());
        assertThat(created.statusCode()).as("staging: create: %s", created.asString())
            .isEqualTo(201);
        return SurfaceFixture.idOf(created);
    }

    /** Sends a draft as the current identity. */
    private static void sent(String id) {
        step("send", given().header(IF_MATCH, token(id)).post(SurfaceFixture.item(id) + ":send"));
    }

    /** The conflict token of a task, read as the current identity. */
    private static String token(String id) {
        return step("read", given().get(SurfaceFixture.item(id))).header("ETag");
    }

    /** Declares a tag in the probe's scope, as the platform does; answers its token. */
    private String declaredTag() {
        String token = unique();
        PlatformFixture.run("INSERT INTO platform.tag (id, tenant_id, scope_id, token, status, "
            + "created_by) VALUES ('" + UUID.randomUUID() + "', '"
            + SubstrateDatabaseResource.TENANT_ID + "', '" + SubstrateDatabaseResource.SCOPE_ID
            + "', '" + token + "', 'declared', 'platform-fixture')");
        return token;
    }

    private String unique() {
        return run + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static Response step(String what, Response answer) {
        assertThat(answer.statusCode()).as("staging: %s: %s", what, answer.asString())
            .isEqualTo(200);
        return answer;
    }

    private static RequestSpecification json(Map<String, ?> body) {
        return given().contentType(ContentType.JSON).body(body);
    }

    private static Map<String, Object> withReceipt(@Nullable String receipt,
                                                   Map<String, Object> fields) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(RECEIPT, receipt);
        body.put(FIELDS, fields);
        return body;
    }
}
