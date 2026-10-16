/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.platform.PlatformFixture;
import ai.kumbuka.dispatch.tenancy.SubstrateDatabaseResource;
import ai.kumbuka.dispatch.tenancy.TenantContext;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static ai.kumbuka.dispatch.domain.TaskHoldingIT.assertRefused;
import static ai.kumbuka.dispatch.domain.TaskStage.C;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * A listing pages by keyset in the database, against a running database under
 * the service role: the walk over every page hands out every task once, in
 * the order asked for, across page boundaries, ties, inserts and a state
 * filter, and a cursor continues only the listing that handed it out.
 *
 * <p>Every walk is bounded. A listing whose keyset bound did not hold would
 * hand out its first page again and again; the bound turns that into a red
 * assertion instead of a test that never ends.
 *
 * <p>Red probes, each observed 2026-10-11 against the running database:
 * <ul>
 *   <li>the keyset comparison removed from the statement: four walks hand out
 *       their first page until the bound;
 *   <li>the sub compared and sorted as text: {@code 1.10} comes before
 *       {@code 1.2};
 *   <li>the sort by creation without the address as its second key: three of
 *       five tied tasks are skipped at the page boundary;
 *   <li>an offset in the cursor instead of the keyset: after an insert the
 *       descending walk hands out one task twice;
 *   <li>the state predicate removed: a page of {@code state=open} holds a
 *       draft; the lapsed lease left out of {@code open}: the walk misses
 *       the lapsed tasks;
 *   <li>each of the five comparisons of the cursor's binding removed in turn:
 *       the cursor of another scope, selector, filter, sort or direction is
 *       answered instead of refused.
 * </ul>
 */
@QuarkusTest
@QuarkusTestResource(value = SubstrateDatabaseResource.class, restrictToAnnotatedClass = true)
class TaskPagingIT {

    static final UUID SCOPE = UUID.fromString("00000000-0000-0000-0000-000000000010");
    static final UUID OTHER_SCOPE = UUID.fromString("00000000-0000-0000-0000-000000000011");
    static final String SELECTOR = "sprint";
    static final String OTHER_SELECTOR = "satellite";

    /** More pages than any walk here needs; a walk that reaches it repeats itself. */
    private static final int MOST_PAGES = 50;

    @Inject TaskService tasks;
    @Inject TenantContext tenantContext;

    private AutoCloseable binding;
    private TaskStage stage;

    @BeforeEach
    void freshTenant() {
        PlatformFixture.grantDirectoryAccess();
        UUID tenant = UUID.randomUUID();
        DomainFixture.declareSelector(tenant, SCOPE, SELECTOR);
        DomainFixture.declareSelector(tenant, SCOPE, OTHER_SELECTOR);
        DomainFixture.declareSelector(tenant, OTHER_SCOPE, SELECTOR);
        binding = tenantContext.bind(tenant);
        stage = new TaskStage(tasks, SCOPE);
    }

    @AfterEach
    void unbind() throws Exception {
        binding.close();
    }

    // =======================================================================
    // Order across page boundaries
    // =======================================================================

    /**
     * {@code x.2} before {@code x.10}: the address sorts as two numbers, and a
     * page of two cuts the bracket at every second child, so a textual order
     * would surface at the first boundary it crosses.
     */
    @Test
    void the_address_order_is_numeric_across_every_page_boundary() {
        TaskView root = root("the bracket");
        // A bracket opens when its root is sent (TAR-0004 section 2).
        tasks.act(SCOPE, root.address(), TaskVerb.SEND,
            TaskCall.by(C).withConflictToken(root.conflictToken()));
        List<String> expected = new ArrayList<>(List.of(root.address().toString()));
        for (int i = 1; i <= 12; i++) {
            expected.add(child(root, "child " + i).address().toString());
        }

        List<List<String>> pages = walk(TaskFilter.none(), SortKey.ID, SortKey.Direction.ASC, 2);

        assertThat(pages).as("thirteen tasks, two to a page").hasSize(7)
            .allSatisfy(page -> assertThat(page).hasSizeLessThanOrEqualTo(2));
        assertThat(flat(pages)).as("x.0, x.1, ... x.12, numerically")
            .containsExactlyElementsOf(expected);
        assertThat(flat(walk(TaskFilter.none(), SortKey.ID, SortKey.Direction.DESC, 2)))
            .as("descending is the same order reversed")
            .containsExactlyElementsOf(expected.reversed());
    }

    /**
     * Five tasks created in one transaction carry one {@code created_at}: the
     * database stamps the transaction's start. The address is the second key,
     * so the tie is broken the same way on every page and every walk.
     */
    @Test
    void a_tie_on_the_instant_of_creation_is_broken_by_the_address() {
        List<TaskView> tied = QuarkusTransaction.requiringNew().call(() -> {
            List<TaskView> made = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                made.add(root("tied " + i));
            }
            return made;
        });
        String later = root("later").address().toString();

        assertThat(tied.stream().map(t -> createdAt(t.identity())).distinct())
            .as("the five share one instant").hasSize(1);
        List<String> expected = new ArrayList<>(tied.stream()
            .map(t -> t.address().toString()).toList());
        expected.add(later);
        assertThat(flat(walk(TaskFilter.none(), SortKey.CREATED_AT, SortKey.Direction.ASC, 2)))
            .as("by creation, then by address, across pages that cut the tie")
            .containsExactlyElementsOf(expected);
        assertThat(flat(walk(TaskFilter.none(), SortKey.CREATED_AT, SortKey.Direction.DESC, 2)))
            .containsExactlyElementsOf(expected.reversed());
    }

    /**
     * A task created between two pages: the walk hands out every task that
     * existed when it began exactly once, and the new one where the order puts
     * it. An offset would hand out the last task of the first page again.
     */
    @Test
    void a_task_inserted_during_a_walk_shifts_nothing_already_handed_out() {
        List<String> before = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            before.add(root("before " + i).address().toString());
        }

        for (SortKey.Direction direction : SortKey.Direction.values()) {
            Page first = tasks.query(SCOPE, SELECTOR, TaskFilter.none(),
                new PageRequest(2, SortKey.CREATED_AT, direction, Optional.empty()), C);
            String inserted = root("inserted " + direction).address().toString();
            List<String> seen = new ArrayList<>(addresses(first));
            seen.addAll(flat(walkFrom(Optional.of(first.nextCursor().orElseThrow()),
                TaskFilter.none(), SortKey.CREATED_AT, direction, 2)));

            assertThat(seen).as("no task twice (%s)", direction).doesNotHaveDuplicates();
            assertThat(seen).as("every task that was there (%s)", direction)
                .containsAll(before);
            if (direction == SortKey.Direction.ASC) {
                assertThat(seen.getLast()).as("the new task comes last, after the cursor")
                    .isEqualTo(inserted);
            } else {
                assertThat(seen).as("descending, the new task lies before the first page")
                    .doesNotContain(inserted);
            }
            before.add(inserted);
        }
    }

    // =======================================================================
    // The state filter across page boundaries
    // =======================================================================

    /**
     * The effective state is in the statement: a page holds no task in another
     * state and the walk misses none in this one, including a lapsed lease,
     * which the row stores as {@code active} and which is {@code open}.
     */
    @Test
    void the_state_filter_holds_on_every_page_and_misses_nothing() {
        List<String> open = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            open.add(switch (i % 3) {
                case 0 -> stage.open(SELECTOR).address().toString();
                case 1 -> stage.lapse(stage.active(SELECTOR)).address().toString();
                default -> stage.deferred(SELECTOR).address().toString();
            });
            stage.draft(SELECTOR);
            stage.active(SELECTOR);
            stage.closed(SELECTOR);
        }
        TaskFilter onlyOpen = TaskFilter.of(Map.of("state", "open"));

        List<List<String>> pages = walk(onlyOpen, SortKey.ID, SortKey.Direction.ASC, 2);

        for (List<String> page : pages) {
            assertThat(page).as("a page holds only open tasks").allSatisfy(address ->
                assertThat(tasks.read(SCOPE, addressOf(address), C).state())
                    .isEqualTo(TaskState.OPEN));
        }
        assertThat(pages).as("seven open tasks among twenty-eight, two to a page").hasSize(4);
        assertThat(flat(pages)).as("and every open task, in address order")
            .containsExactlyElementsOf(open);
    }

    // =======================================================================
    // The cursor binds its listing
    // =======================================================================

    @Test
    void a_cursor_of_another_listing_is_refused_as_foreign_naming_what_differs() {
        for (int i = 0; i < 3; i++) {
            stage.open(SELECTOR);
        }
        PageRequest first = new PageRequest(1, SortKey.ID, SortKey.Direction.ASC,
            Optional.empty());
        String cursor = tasks.query(SCOPE, SELECTOR, TaskFilter.none(), first, C)
            .nextCursor().orElseThrow();
        PageRequest next = new PageRequest(1, SortKey.ID, SortKey.Direction.ASC,
            Optional.of(cursor));

        assertForeign(() -> tasks.query(OTHER_SCOPE, SELECTOR, TaskFilter.none(), next, C),
            "scope");
        assertForeign(() -> tasks.query(SCOPE, OTHER_SELECTOR, TaskFilter.none(), next, C),
            "selector");
        assertForeign(() -> tasks.query(SCOPE, SELECTOR, TaskFilter.of(Map.of("state", "open")),
            next, C), "state", "apparatus", "bracket", "address");
        assertForeign(() -> tasks.query(SCOPE, SELECTOR, TaskFilter.none(),
            new PageRequest(1, SortKey.CREATED_AT, SortKey.Direction.ASC, Optional.of(cursor)),
            C), "sort");
        assertForeign(() -> tasks.query(SCOPE, SELECTOR, TaskFilter.none(),
            new PageRequest(1, SortKey.ID, SortKey.Direction.DESC, Optional.of(cursor)), C),
            "direction");
        assertThat(tasks.query(SCOPE, SELECTOR, TaskFilter.none(), next, C).tasks())
            .as("the same listing continues").hasSize(1);
        assertThat(tasks.query(SCOPE, SELECTOR, TaskFilter.of(Map.of("apparatus", "code,x*")),
                new PageRequest(1, SortKey.ID, SortKey.Direction.ASC, Optional.of(
                    tasks.query(SCOPE, SELECTOR, TaskFilter.of(Map.of("apparatus", "x*,code")),
                        first, C).nextCursor().orElseThrow())), C).tasks())
            .as("alternatives written in another order are the same filter").hasSize(1);
    }

    /**
     * A scope's name recurs across tenants and its identity does not, so the
     * cursor binds the identity (ADR-0049). The kernel is handed the scope
     * only by identity, which is what the platform's directory resolves a
     * name to in each tenant: a scope of the same name in another tenant is a
     * scope with another identity here.
     *
     * <p>Red probe, observed 2026-10-12: the scope taken out of the cursor's
     * binding answers the cursor of tenant A with a page of tenant B.
     */
    @Test
    void a_cursor_of_a_scope_of_the_same_name_in_another_tenant_is_foreign() throws Exception {
        for (int i = 0; i < 2; i++) {
            stage.open(SELECTOR);
        }
        PageRequest first = new PageRequest(1, SortKey.ID, SortKey.Direction.ASC,
            Optional.empty());
        String cursor = tasks.query(SCOPE, SELECTOR, TaskFilter.none(), first, C)
            .nextCursor().orElseThrow();

        UUID tenantB = UUID.randomUUID();
        UUID sameNameInB = UUID.randomUUID();
        DomainFixture.declareSelector(tenantB, sameNameInB, SELECTOR);
        try (AutoCloseable inB = tenantContext.bind(tenantB)) {
            TaskStage stageB = new TaskStage(tasks, sameNameInB);
            for (int i = 0; i < 2; i++) {
                stageB.open(SELECTOR);
            }
            assertForeign(() -> tasks.query(sameNameInB, SELECTOR, TaskFilter.none(),
                new PageRequest(1, SortKey.ID, SortKey.Direction.ASC, Optional.of(cursor)), C),
                "scope");
        }
    }

    @Test
    void a_cursor_this_service_did_not_hand_out_is_refused_as_malformed() {
        stage.open(SELECTOR);
        String earlierVersion = Base64.getUrlEncoder().withoutPadding().encodeToString(
            "v0|a|sprint|b|id|asc|1|0|2026-10-11T00:00:00Z".getBytes(StandardCharsets.UTF_8));
        String unknownSort = Base64.getUrlEncoder().withoutPadding().encodeToString(
            "v1|a|sprint|b|title|asc|1|0|2026-10-11T00:00:00Z".getBytes(StandardCharsets.UTF_8));
        for (String malformed : List.of("not a cursor", "", "djE", earlierVersion,
                unknownSort)) {
            assertRefused(() -> tasks.query(SCOPE, SELECTOR, TaskFilter.none(),
                    new PageRequest(1, SortKey.ID, SortKey.Direction.ASC, Optional.of(malformed)),
                    C),
                DispatchException.Reason.CURSOR_MALFORMED);
        }
    }

    @Test
    void the_page_size_and_the_sort_are_refused_outside_what_is_declared() {
        for (long size : new long[] {0, -1, 201, Long.MAX_VALUE}) {
            assertRefused(() -> PageRequest.of(size, null, null, null),
                DispatchException.Reason.PAGE_SIZE_REJECTED);
        }
        assertThat(PageRequest.of(null, null, null, null).pageSize()).isEqualTo(50);
        assertThat(PageRequest.of(200L, null, null, null).pageSize()).isEqualTo(200);
        assertRefused(() -> PageRequest.of(null, "title", null, null),
            DispatchException.Reason.SORT_UNDECLARED);
        assertRefused(() -> PageRequest.of(null, "uuid", null, null),
            DispatchException.Reason.SORT_UNDECLARED);
    }

    // =======================================================================
    // What a page carries
    // =======================================================================

    /**
     * The heads of a page carry the list of texts, by type and suffix, as a
     * read does, read for the whole page at once. The curation target is
     * held the same way in {@code TaskCurationIT}, where a subject is bound.
     */
    @Test
    void a_page_carries_the_text_heads_a_read_carries() {
        TaskStage.Staged annotated = stage.open(SELECTOR);
        tasks.annotate(SCOPE, annotated.address(), TextType.DISPATCH, "an addendum", C,
            IdempotencyKey.NONE);
        stage.draft(SELECTOR);
        tasks.create(SCOPE, SELECTOR, null, new TaskService.Draft("no text", "code", null, null),
            C, IdempotencyKey.NONE);

        List<TaskView> heads = tasks.query(SCOPE, SELECTOR, TaskFilter.none(),
            TaskStage.page(200), C).tasks();
        assertThat(heads).hasSize(3);
        for (TaskView head : heads) {
            assertThat(head.texts()).as("texts of %s", head.address())
                .isEqualTo(tasks.read(SCOPE, head.address(), C).texts());
        }
        assertThat(heads.getFirst().texts()).extracting(TaskView.TextHead::addendumSuffix)
            .as("the commission and its addendum").containsExactly(null, "a");
    }

    // =======================================================================

    private TaskView root(String title) {
        return tasks.create(SCOPE, SELECTOR, null,
            new TaskService.Draft(title, "code", "the commission", null), C,
            IdempotencyKey.NONE);
    }

    private TaskView child(TaskView root, String title) {
        return tasks.create(SCOPE, SELECTOR, root.address().number(),
            new TaskService.Draft(title, "code", null, null), C, IdempotencyKey.NONE);
    }

    /** Every page of a listing from its first, as short addresses. */
    private List<List<String>> walk(TaskFilter filter, SortKey sort,
                                    SortKey.Direction direction, int size) {
        return walkFrom(Optional.empty(), filter, sort, direction, size);
    }

    /** Every page of a listing from a cursor on, or from its first without one. */
    private List<List<String>> walkFrom(Optional<String> cursor, TaskFilter filter,
                                        SortKey sort, SortKey.Direction direction, int size) {
        List<List<String>> pages = new ArrayList<>();
        Optional<String> after = cursor;
        do {
            Page page = tasks.query(SCOPE, SELECTOR, filter,
                new PageRequest(size, sort, direction, after), C);
            pages.add(addresses(page));
            after = page.nextCursor();
            assertThat(pages).as("a walk that does not end repeats a page: %s", pages)
                .hasSizeLessThan(MOST_PAGES);
        } while (after.isPresent());
        return pages;
    }

    private static List<String> addresses(Page page) {
        return page.tasks().stream().map(t -> t.address().toString()).toList();
    }

    private static List<String> flat(List<List<String>> pages) {
        return pages.stream().flatMap(List::stream).toList();
    }

    private static ExchangeAddress addressOf(String shortForm) {
        int slash = shortForm.indexOf('/');
        int dot = shortForm.indexOf('.');
        return new ExchangeAddress(shortForm.substring(0, slash),
            Integer.parseInt(shortForm.substring(slash + 1, dot)),
            Integer.parseInt(shortForm.substring(dot + 1)));
    }

    /** A task's instant of creation as the row stores it, read as the administrator. */
    private static String createdAt(UUID identity) {
        var config = ConfigProvider.getConfig();
        try (Connection c = DriverManager.getConnection(
                config.getValue("test.db.url", String.class),
                config.getValue("test.db.admin.username", String.class),
                config.getValue("test.db.admin.password", String.class));
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT created_at::text FROM dispatch.task "
                 + "WHERE uuid = '" + identity + "'")) {
            assertThat(rs.next()).as("the row of %s", identity).isTrue();
            return rs.getString(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * A foreign cursor is refused naming what differs, by the declared
     * arguments of the listing that carry it: a filter that differs is named
     * as the four filters, since its fingerprint does not say which.
     */
    private static void assertForeign(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                      String... differs) {
        DispatchException refused = catchThrowableOfType(DispatchException.class, call);
        assertThat(refused).as("refused as foreign: %s", List.of(differs)).isNotNull();
        assertThat(refused.reason()).isEqualTo(DispatchException.Reason.CURSOR_FOREIGN);
        assertThat(refused.offenders()).as("and it names what differs by declared argument")
            .containsExactly(differs);
    }
}
