/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.tenancy;

import org.postgresql.util.PSQLException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * The stock the cut-over probes plant in the old store, and the way they run
 * the three scripts of {@code migration/} against it.
 *
 * <p>The scripts are read from the repository, as the operator runs them; a
 * missing file stops the probe instead of passing it. They run through one
 * JDBC statement each, with their own BEGIN and COMMIT, and a refusal is
 * answered as its message, so a case can assert on the reason and on what
 * the message leaves out.
 *
 * <p>The stock holds every state and every kind of text the old store knows,
 * in two tenants of an invented scope, with addresses of an invented tenant
 * acme in its metadata. Every value the probes expect is written here or in
 * the probe, from the order; none is read from the new store.
 */
final class CutoverStock {

    static final String MIGRATOR = "cutover_migrator";
    static final String MIGRATOR_PASSWORD = "test-only-cutover-password";

    /** The subject the operator names for the copy. */
    static final String ACTOR = "cutover-operator-subject";

    static final String RENAME = "cutover-rename.sql";
    static final String REVERSE = "cutover-rename-reverse.sql";
    static final String COPY = "cutover-copy.sql";

    /** What {@link #outcome} answers for a script that ran to its end. */
    static final String NOT_REFUSED = "not refused: the script ran";

    static final String OLD_SCHEMA = "dispatch";
    static final String LEGACY = "dispatch_legacy";
    static final String HISTORY = "flyway_schema_history";

    static final UUID TENANT_A = UUID.fromString("0000000a-0000-4000-8000-00000000000a");
    static final UUID SCOPE_A = UUID.fromString("0000000a-0000-4000-8000-0000000000a5");
    static final UUID TENANT_B = UUID.fromString("0000000b-0000-4000-8000-00000000000b");
    static final UUID SCOPE_B = UUID.fromString("0000000b-0000-4000-8000-0000000000b5");

    static final String SPRINT = "sprint";
    static final String SATELLITE = "satellite";

    static final String DISPATCH = "dispatch";
    static final String RETURN = "return";
    static final String QUESTION = "question";
    static final String ANSWER = "answer";
    static final String REMARK = "remark";

    static final String OPEN = "open";
    static final String ACTIVE = "active";
    static final String DELIVERED = "delivered";
    static final String CLOSED = "closed";
    static final String DRAFT = "draft";
    static final String ACCEPTED = "accepted";
    static final String WITHDRAWN = "withdrawn";

    static final String CODE = "code";
    static final String CONCEPT = "concept";

    static final OffsetDateTime T0 = OffsetDateTime.parse("2026-10-01T08:00:00Z");
    static final OffsetDateTime LEASE_END = OffsetDateTime.parse("2026-12-01T08:00:00Z");
    static final OffsetDateTime NOT_BEFORE = OffsetDateTime.parse("2026-12-15T08:00:00Z");

    static final String ROOT_DISPATCH_METADATA = """
        {"items": ["worklist://acme/item/7", "worklist://acme/item/8"],
         "origin": "dispatch://acme/satellite/3.0",
         "custom-key": "a value nobody declared",
         "review-verdict": "approved"}""";
    static final String ROOT_RETURN_METADATA = """
        {"items": ["worklist://acme/item/8"],
         "handover-pr": "https://example.org/acme/pull/1",
         "review-verdict": "changes-required",
         "review-round": "second round"}""";
    static final String CHILD_DISPATCH_METADATA = """
        {"items": ["worklist://acme/item/9"]}""";
    static final String DRAFT_METADATA = """
        {"items": ["worklist://acme/item/11"], "draft-key": "a value of a draft"}""";
    static final String QUESTION_OPTIONS = """
        {"options": ["left", "right"], "free_text": false}""";

    private CutoverStock() {
    }

    // -----------------------------------------------------------------------
    // Databases.
    // -----------------------------------------------------------------------

    /** A database of its own with the old line, db/migration V1 to V25, applied. */
    static String oldStore(MigrationHarness harness, String name) throws SQLException {
        String url = harness.freshDatabase(name, MIGRATOR);
        harness.migrate(url, MIGRATOR, MIGRATOR_PASSWORD, new TenantMigrationCallback());
        return url;
    }

    /** The line db/store, V1 to its latest version, in the empty schema dispatch after the rename. */
    static void newStore(MigrationHarness harness, String url) {
        harness.migrateLine(url, MIGRATOR, MIGRATOR_PASSWORD, "classpath:db/store",
            new TenantMigrationCallback());
    }

    static Connection asMigrator(String url) throws SQLException {
        return DriverManager.getConnection(url, MIGRATOR, MIGRATOR_PASSWORD);
    }

    // -----------------------------------------------------------------------
    // The scripts.
    // -----------------------------------------------------------------------

    /**
     * Runs a script of migration/ and answers {@link #NOT_REFUSED}, or the
     * message it was refused with. A refused script leaves its transaction
     * aborted; it is rolled back here, so the connection serves the next case.
     */
    static String outcome(Connection c, String script) throws SQLException {
        String sql = read(script);
        try (Statement s = c.createStatement()) {
            // The script runs as psql runs it: no JDBC escape is rewritten.
            s.setEscapeProcessing(false);
            s.execute(sql);
            return NOT_REFUSED;
        } catch (SQLException refused) {
            try (Statement s = c.createStatement()) {
                s.execute("ROLLBACK");
            }
            return messageOf(refused);
        }
    }

    /** The primary message the server raised, without the context PgJDBC appends. */
    private static String messageOf(SQLException refused) {
        if (refused instanceof PSQLException server && server.getServerErrorMessage() != null) {
            return String.valueOf(server.getServerErrorMessage().getMessage());
        }
        return String.valueOf(refused.getMessage());
    }

    /** The copy, with the actor set for the session where one is given. */
    static String copy(Connection c, String actor) throws SQLException {
        try (PreparedStatement st = c.prepareStatement(
                "SELECT set_config('kumbuka.cutover_actor', ?, false)")) {
            st.setString(1, actor);
            st.execute();
        }
        return outcome(c, COPY);
    }

    /** The report of the last copy in this session, one line per row. */
    static List<String> report(Connection c) throws SQLException {
        List<String> lines = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                "SELECT section, subject, amount FROM pg_temp.cutover_copy_report ORDER BY ordinal")) {
            while (rs.next()) {
                lines.add(rs.getString(1) + " | " + rs.getString(2) + " | " + rs.getLong(3));
            }
        }
        return lines;
    }

    private static String read(String script) {
        Path file = Path.of("..", "migration", script);
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("the script " + file.toAbsolutePath()
                + " is not there; the probe runs the file the operator runs");
        }
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // -----------------------------------------------------------------------
    // Snapshots.
    // -----------------------------------------------------------------------

    /**
     * Every base table of a schema with a digest of its rows. The rows of act
     * are read without the time of an import act: that time is the time of
     * the run, which the order names as such. The rows of draft are read
     * without their token, changed_at: the copy names no token, so the store
     * gives each draft the time of the run by the default of V6.
     */
    static Map<String, String> rows(Connection c, String schema) throws SQLException {
        Map<String, String> digests = new TreeMap<>();
        for (String table : tables(c, schema)) {
            boolean store = OLD_SCHEMA.equals(schema);
            String row;
            if (store && "act".equals(table)) {
                row = "(to_jsonb(t) - 'at')::text || CASE WHEN t.kind = 'import' THEN '' "
                    + "ELSE t.at::text END";
            } else if (store && "draft".equals(table)) {
                row = "(to_jsonb(t) - 'changed_at')::text";
            } else {
                row = "t::text";
            }
            digests.put(table, scalar(c, "SELECT count(*) || ':' || coalesce(md5(string_agg("
                + row + ", '|' ORDER BY " + row + ")), '') FROM " + schema + "." + table + " t"));
        }
        return digests;
    }

    /** The base tables of a schema, by name. */
    static Set<String> tables(Connection c, String schema) throws SQLException {
        Set<String> tables = new TreeSet<>();
        try (PreparedStatement st = c.prepareStatement("""
                SELECT cl.relname FROM pg_class cl
                JOIN pg_namespace n ON n.oid = cl.relnamespace
                WHERE n.nspname = ? AND cl.relkind IN ('r', 'p')
                """)) {
            st.setString(1, schema);
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
        }
        return tables;
    }

    /**
     * What the catalog says about a schema: its owner and privileges, every
     * relation with its owner, security and privileges, every column,
     * constraint, index, policy, trigger and function. One fact per line.
     */
    static List<String> catalogue(Connection c, String schema) throws SQLException {
        List<String> facts = new ArrayList<>();
        try (PreparedStatement st = c.prepareStatement("""
                SELECT f FROM (
                  SELECT 'schema owner=' || pg_get_userbyid(n.nspowner)
                         || ' acl=' || coalesce(n.nspacl::text, '') AS f
                    FROM pg_namespace n WHERE n.nspname = ?1
                  UNION ALL
                  SELECT 'relation ' || cl.relname || ' kind=' || cl.relkind::text
                         || ' owner=' || pg_get_userbyid(cl.relowner)
                         || ' rls=' || cl.relrowsecurity || ' force=' || cl.relforcerowsecurity
                         || ' acl=' || coalesce(cl.relacl::text, '')
                    FROM pg_class cl JOIN pg_namespace n ON n.oid = cl.relnamespace
                   WHERE n.nspname = ?1
                  UNION ALL
                  SELECT 'column ' || cl.relname || '.' || a.attname || ' '
                         || format_type(a.atttypid, a.atttypmod) || ' notnull=' || a.attnotnull
                         || ' acl=' || coalesce(a.attacl::text, '')
                    FROM pg_attribute a JOIN pg_class cl ON cl.oid = a.attrelid
                    JOIN pg_namespace n ON n.oid = cl.relnamespace
                   WHERE n.nspname = ?1 AND a.attnum > 0 AND NOT a.attisdropped
                  UNION ALL
                  SELECT 'constraint ' || cl.relname || ' ' || k.conname || ' '
                         || pg_get_constraintdef(k.oid)
                    FROM pg_constraint k JOIN pg_class cl ON cl.oid = k.conrelid
                    JOIN pg_namespace n ON n.oid = cl.relnamespace
                   WHERE n.nspname = ?1
                  UNION ALL
                  SELECT 'index ' || pg_get_indexdef(i.indexrelid)
                    FROM pg_index i JOIN pg_class cl ON cl.oid = i.indrelid
                    JOIN pg_namespace n ON n.oid = cl.relnamespace
                   WHERE n.nspname = ?1
                  UNION ALL
                  SELECT 'policy ' || p.tablename || ' ' || p.policyname || ' '
                         || coalesce(p.qual, '') || ' ' || coalesce(p.with_check, '')
                         || ' ' || p.roles::text
                    FROM pg_policies p WHERE p.schemaname = ?1
                  UNION ALL
                  SELECT 'trigger ' || cl.relname || ' ' || t.tgname || ' ' || t.tgenabled::text
                         || ' ' || pg_get_triggerdef(t.oid)
                    FROM pg_trigger t JOIN pg_class cl ON cl.oid = t.tgrelid
                    JOIN pg_namespace n ON n.oid = cl.relnamespace
                   WHERE n.nspname = ?1 AND NOT t.tgisinternal
                  UNION ALL
                  SELECT 'function ' || p.proname || '(' || pg_get_function_identity_arguments(p.oid)
                         || ') owner=' || pg_get_userbyid(p.proowner)
                         || ' acl=' || coalesce(p.proacl::text, '') || ' ' || p.prosrc
                    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
                   WHERE n.nspname = ?1
                ) facts
                ORDER BY f
                """.replace("?1", "?"))) {
            for (int i = 1; i <= 8; i++) {
                st.setString(i, schema);
            }
            try (ResultSet rs = st.executeQuery()) {
                while (rs.next()) {
                    facts.add(rs.getString(1));
                }
            }
        }
        return facts;
    }

    /**
     * Every title, text and metadata value of the stock as planted, read from
     * the old store: what neither the report nor a refusal may contain.
     */
    static Set<String> planted(Connection c, String schema) throws SQLException {
        Set<String> values = new TreeSet<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                "SELECT t.title FROM " + schema + ".task t "
                    + "UNION SELECT x.text FROM " + schema + ".task_text x "
                    + "UNION SELECT v #>> '{}' FROM " + schema + ".task t, "
                    + "LATERAL (SELECT jsonb_path_query(coalesce(t.dispatch_metadata, '{}'), "
                    + "'strict $.**') UNION ALL SELECT jsonb_path_query("
                    + "coalesce(t.return_metadata, '{}'), 'strict $.**')) AS m(v) "
                    + "WHERE jsonb_typeof(v) = 'string'")) {
            while (rs.next()) {
                values.add(rs.getString(1));
            }
        }
        return values;
    }

    static String scalar(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }

    static void exec(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    // -----------------------------------------------------------------------
    // The stock.
    // -----------------------------------------------------------------------

    /**
     * One tenant's stock in the old store under schema {@code dispatch}:
     *
     * <pre>
     *   sprint/1.0   root, closed accepted, two returns, a dispatch addendum,
     *                the metadata the order names on both sides
     *   sprint/1.1   open, items in its metadata, a tag, an idempotency key
     *   sprint/1.2   active, holder with receipt and lease
     *   sprint/1.3   on hold for a dependency, holder, no first take-up
     *   sprint/1.4   on hold external, no holder, three lapses: parked
     *   sprint/1.5   on hold for a question with options, an answered one before
     *                without options, with an addendum to it
     *   sprint/1.6   delivered, holder, a return addendum
     *   sprint/1.7   closed rejected, a remark
     *   sprint/1.8   closed failed, a remark
     *   sprint/1.9   closed withdrawn
     *   sprint/1.10  closed accepted, curated into 1.0
     *   sprint/1.11  never sent, metadata and a tag
     *   sprint/1.12  open, deferred
     *   sprint/2.0   root, closed withdrawn
     *   sprint/3.0   root, never sent
     *   satellite/3.0 root, open
     * </pre>
     *
     * The old counter of sprint stands at 2, behind its highest number; the one
     * of satellite at 40, before it.
     */
    static void stage(Connection c, UUID tenant, UUID scope, String tag) throws SQLException {
        Legacy l = new Legacy(c, OLD_SCHEMA, tenant, scope, tag);
        String lead = "lead-" + tag;
        String exec = "exec-" + tag;
        long sprint = l.selector(SPRINT, 2);
        long satellite = l.selector(SATELLITE, 40);

        long root = l.task(sprint, 1, 0).apparatus(CONCEPT).closed(ACCEPTED, 30, lead)
            .dispatchMetadata(ROOT_DISPATCH_METADATA).returnMetadata(ROOT_RETURN_METADATA)
            .insert();
        l.text(root, DISPATCH, null, 1, lead);
        l.text(root, DISPATCH, "a", 5, lead);
        l.text(root, RETURN, null, 10, exec);
        l.text(root, RETURN, null, 20, exec);

        long open = l.task(sprint, 1, 1).dispatchMetadata(CHILD_DISPATCH_METADATA).insert();
        l.text(open, DISPATCH, null, 2, lead);
        l.tag(open, tagOf(tag, 1));
        l.idempotencyKey(open);

        long active = l.task(sprint, 1, 2).in(ACTIVE, 6, exec)
            .holder(exec, receiptOf("1.2", tag)).lease(LEASE_END).firstTakenAt(6).insert();
        l.text(active, DISPATCH, null, 2, lead);

        long dependency = l.task(sprint, 1, 3).onHold("dependency", 7, exec)
            .holder(exec, receiptOf("1.3", tag)).insert();
        l.text(dependency, DISPATCH, null, 2, lead);

        long parked = l.task(sprint, 1, 4).onHold("external", 8, exec).lapses(3).insert();
        l.text(parked, DISPATCH, null, 2, lead);

        long asked = l.task(sprint, 1, 5).onHold(QUESTION, 12, exec)
            .holder(exec, receiptOf("1.5", tag)).firstTakenAt(4)
            .questionOptions(QUESTION_OPTIONS).insert();
        l.text(asked, DISPATCH, null, 3, lead);
        l.text(asked, QUESTION, null, 9, exec);
        l.text(asked, QUESTION, "a", 10, exec);
        l.text(asked, ANSWER, null, 11, lead);
        l.text(asked, QUESTION, null, 12, exec);

        long delivered = l.task(sprint, 1, 6).in(DELIVERED, 13, exec)
            .holder(exec, receiptOf("1.6", tag)).firstTakenAt(5).insert();
        l.text(delivered, DISPATCH, null, 2, lead);
        l.text(delivered, RETURN, null, 13, exec);
        l.text(delivered, RETURN, "a", 14, exec);

        long rejected = l.task(sprint, 1, 7).closed("rejected", 15, exec).insert();
        l.text(rejected, DISPATCH, null, 2, lead);
        l.text(rejected, REMARK, null, 15, exec);

        long failed = l.task(sprint, 1, 8).closed("failed", 16, exec).insert();
        l.text(failed, DISPATCH, null, 2, lead);
        l.text(failed, REMARK, null, 16, exec);

        long withdrawn = l.task(sprint, 1, 9).closed(WITHDRAWN, 17, lead).insert();
        l.text(withdrawn, DISPATCH, null, 2, lead);

        long curated = l.task(sprint, 1, 10).closed(ACCEPTED, 19, lead).curatedIn(root).insert();
        l.text(curated, DISPATCH, null, 2, lead);
        l.text(curated, RETURN, null, 18, exec);

        long draft = l.task(sprint, 1, 11).in(DRAFT, 2, lead).dispatchMetadata(DRAFT_METADATA)
            .insert();
        l.text(draft, DISPATCH, null, 2, lead);
        l.tag(draft, tagOf(tag, 2));

        long deferred = l.task(sprint, 1, 12).in(OPEN, 20, lead).notBefore(NOT_BEFORE).insert();
        l.text(deferred, DISPATCH, null, 2, lead);

        long second = l.task(sprint, 2, 0).apparatus(CONCEPT).closed(WITHDRAWN, 22, lead)
            .insert();
        l.text(second, DISPATCH, null, 21, lead);

        long draftRoot = l.task(sprint, 3, 0).apparatus(CONCEPT).in(DRAFT, 23, lead).insert();
        l.text(draftRoot, DISPATCH, null, 23, lead);

        long satelliteRoot = l.task(satellite, 3, 0).apparatus(CONCEPT).in(OPEN, 24, lead)
            .insert();
        l.text(satelliteRoot, DISPATCH, null, 24, lead);
    }

    /** The hash of the receipt the holder of a task carries, as planted. */
    static String receiptOf(String id, String tag) {
        return "receipt-hash-" + id + "-" + tag;
    }

    /** A tag of the platform, one per tenant and number. */
    static UUID tagOf(String tag, int n) {
        return UUID.nameUUIDFromBytes(("tag-" + tag + "-" + n).getBytes(StandardCharsets.UTF_8));
    }

    /** The address the copy names: {@code <scope_id>/<selector>/<number>.<sub>}. */
    static String address(UUID scope, String selector, String id) {
        return scope + "/" + selector + "/" + id;
    }

    /** Writes into one tenant and scope of the old store, as the superuser. */
    static final class Legacy {

        private final Connection c;
        private final String schema;
        private final UUID tenant;
        private final UUID scope;
        private final String tag;

        Legacy(Connection c, String schema, UUID tenant, UUID scope, String tag) {
            this.c = c;
            this.schema = schema;
            this.tenant = tenant;
            this.scope = scope;
            this.tag = tag;
        }

        long selector(String name, int nextNumber) throws SQLException {
            return returningId("INSERT INTO " + schema + ".selector (tenant_id, scope_id, name, "
                + "next_number) VALUES (?, ?, ?, ?) RETURNING id", tenant, scope, name, nextNumber);
        }

        /** The id of a selector already there. */
        long selectorId(String name) throws SQLException {
            return returningId("SELECT id FROM " + schema + ".selector "
                + "WHERE tenant_id = ? AND scope_id = ? AND name = ?", tenant, scope, name);
        }

        /** The id of a task already there, by its address. */
        long taskId(String selector, int number, int sub) throws SQLException {
            return returningId("SELECT t.id FROM " + schema + ".task t JOIN " + schema
                + ".selector s ON s.id = t.selector_id WHERE t.tenant_id = ? AND t.scope_id = ? "
                + "AND s.name = ? AND t.number = ? AND t.sub = ?",
                tenant, scope, selector, number, sub);
        }

        LegacyTask task(long selector, int number, int sub) {
            return new LegacyTask(this, selector, number, sub);
        }

        /** A text of a task, written at a minute after {@link #T0} by a subject. */
        void text(long task, String type, String suffix, int minute, String by)
                throws SQLException {
            text(task, type, suffix, minute, by,
                type + " text " + (suffix == null ? "" : suffix + " ") + "of task " + task
                    + " at minute " + minute + ", tenant " + tag);
        }

        /** A text of a task with the body given, at a minute after {@link #T0} by a subject. */
        void text(long task, String type, String suffix, int minute, String by, String body)
                throws SQLException {
            returningId("INSERT INTO " + schema + ".task_text (tenant_id, scope_id, task_id, "
                    + "text_type, addendum_suffix, text, created_at, created_by) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id",
                tenant, scope, task, type, suffix, body, T0.plusMinutes(minute), by);
        }

        void tag(long task, UUID tagId) throws SQLException {
            returningId("INSERT INTO " + schema + ".task_tag (tenant_id, task_id, tag_id, "
                + "attached_by) VALUES (?, ?, ?, 'tagger') RETURNING task_id", tenant, task, tagId);
        }

        void idempotencyKey(long task) throws SQLException {
            returningId("INSERT INTO " + schema + ".task_idempotency_key (tenant_id, scope_id, "
                    + "task_id, caller_subject, idempotency_key, call_name, argument_digest) "
                    + "VALUES (?, ?, ?, 'caller', ?, 'dispatch_create', 'digest') RETURNING id",
                tenant, scope, task, "key-" + task);
        }

        long returningId(String sql, Object... args) throws SQLException {
            try (PreparedStatement st = c.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    st.setObject(i + 1, args[i]);
                }
                try (ResultSet rs = st.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        }
    }

    /** A task of the old store before its insert, with every column the copy reads. */
    static final class LegacyTask {

        private final Legacy store;
        private final long selector;
        private final int number;
        private final int sub;
        private String state = OPEN;
        private String holdReason;
        private String outcome;
        private int changedAt;
        private String changedBy;
        private String questionOptions;
        private OffsetDateTime notBefore;
        private int lapses;
        private String holder;
        private String receiptHash;
        private OffsetDateTime leaseEnd;
        private Integer firstTakenAt;
        private Long curatedIn;
        private String apparatus = CODE;
        private String dispatchMetadata;
        private String returnMetadata;

        private LegacyTask(Legacy store, long selector, int number, int sub) {
            this.store = store;
            this.selector = selector;
            this.number = number;
            this.sub = sub;
        }

        LegacyTask in(String newState, int minute, String by) {
            this.state = newState;
            this.changedAt = minute;
            this.changedBy = by;
            return this;
        }

        LegacyTask onHold(String reason, int minute, String by) {
            this.holdReason = reason;
            return in("on_hold", minute, by);
        }

        LegacyTask closed(String result, int minute, String by) {
            this.outcome = result;
            return in(CLOSED, minute, by);
        }

        LegacyTask holder(String subject, String hash) {
            this.holder = subject;
            this.receiptHash = hash;
            return this;
        }

        LegacyTask lease(OffsetDateTime end) {
            this.leaseEnd = end;
            return this;
        }

        LegacyTask firstTakenAt(int minute) {
            this.firstTakenAt = minute;
            return this;
        }

        LegacyTask questionOptions(String json) {
            this.questionOptions = json;
            return this;
        }

        LegacyTask notBefore(OffsetDateTime instant) {
            this.notBefore = instant;
            return this;
        }

        LegacyTask lapses(int count) {
            this.lapses = count;
            return this;
        }

        LegacyTask curatedIn(long task) {
            this.curatedIn = task;
            return this;
        }

        LegacyTask apparatus(String value) {
            this.apparatus = value;
            return this;
        }

        LegacyTask dispatchMetadata(String json) {
            this.dispatchMetadata = json;
            return this;
        }

        LegacyTask returnMetadata(String json) {
            this.returnMetadata = json;
            return this;
        }

        long insert() throws SQLException {
            return store.returningId("INSERT INTO " + store.schema + ".task (tenant_id, scope_id, "
                    + "selector_id, number, sub, state, hold_reason, outcome, state_changed_at, "
                    + "state_changed_by, question_options, not_before, lapse_count, holder_subject, "
                    + "holder_receipt_hash, lease_expires_at, curated_in_id, title, apparatus, "
                    + "dispatch_metadata, return_metadata, first_taken_at, created_at, created_by, "
                    + "updated_at, updated_by) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?, "
                    + "?::jsonb, ?::jsonb, ?, ?, 'creator', ?, 'creator') RETURNING id",
                store.tenant, store.scope, selector, number, sub, state, holdReason, outcome,
                T0.plusMinutes(changedAt), changedBy, questionOptions, notBefore, lapses, holder,
                receiptHash, leaseEnd, curatedIn,
                "title of " + number + "." + sub + " in selector " + selector + ", tenant "
                    + store.tag,
                apparatus, dispatchMetadata, returnMetadata,
                firstTakenAt == null ? null : T0.plusMinutes(firstTakenAt), T0,
                T0.plusMinutes(changedAt));
        }
    }
}
