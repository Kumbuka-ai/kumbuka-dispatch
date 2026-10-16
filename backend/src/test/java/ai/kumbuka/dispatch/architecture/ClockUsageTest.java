/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.architecture;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The current time comes from one injected {@code java.time.Clock} (C20.1).
 *
 * <p>Three shapes read the time past it, and each is refused in main code:
 * a {@code now()} with no clock handed in, {@code System.currentTimeMillis()},
 * and a system clock built anywhere but the one producer that hands the
 * service its clock. {@code Instant.now(clock)} is the admitted form: the
 * clock it reads is the injected one.
 *
 * <p>The database half of the rule: no trigger and no default sets a time the
 * application reads back as a conflict token. The migrations are replayed in
 * the order Flyway applies them, and what they leave on each column of
 * {@link #TOKEN_COLUMNS} is read: a column default that is a time function,
 * and a trigger on the column's table whose function assigns the column a
 * time. A trigger or a default dropped by a later migration is gone; one
 * created again is back. Both halves read text, the Java sources and the
 * migrations, and neither needs a database.
 */
class ClockUsageTest {

    /** The one class that may build the system clock: the CDI producer. */
    static final String PRODUCER = "ServiceClock";

    private static final Pattern NOW_WITHOUT_CLOCK = Pattern.compile(
        "\\b(Instant|LocalDateTime|LocalDate|LocalTime|OffsetDateTime|ZonedDateTime)"
            + "\\s*\\.\\s*now\\s*\\(\\s*\\)");
    private static final Pattern MILLIS = Pattern.compile(
        "\\bSystem\\s*\\.\\s*currentTimeMillis\\s*\\(");
    private static final Pattern SYSTEM_CLOCK = Pattern.compile(
        "\\bClock\\s*\\.\\s*(systemUTC|systemDefaultZone|system)\\s*\\(");

    /** The schema the migrations write. */
    static final String SCHEMA = "dispatch";

    /**
     * The columns the application reads back as a conflict token, as {@code
     * table.column}. The one place the rule names this service's tables.
     */
    static final List<String> TOKEN_COLUMNS = List.of("task.updated_at");

    /** The last migration before the token moved into the application. */
    private static final int BEFORE_THE_MOVE = 20;

    /** A time the database reads off its own clock. */
    private static final String TIME_FUNCTION = "(?:now\\s*\\(\\s*\\)|current_timestamp|"
        + "clock_timestamp\\s*\\(\\s*\\)|statement_timestamp\\s*\\(\\s*\\)|"
        + "transaction_timestamp\\s*\\(\\s*\\)|localtimestamp)";

    /** A time function, at the start of a value. */
    private static final Pattern TIME_VALUE = Pattern.compile("^\\s*" + TIME_FUNCTION,
        Pattern.CASE_INSENSITIVE);

    /** A column default that is a time function, in a column's definition. */
    private static final Pattern TIME_DEFAULT = Pattern.compile("\\bDEFAULT\\s+" + TIME_FUNCTION,
        Pattern.CASE_INSENSITIVE);

    private static final Pattern CREATE_TABLE = Pattern.compile(
        "CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?" + SCHEMA + "\\.(\\w+)\\s*\\(",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern ALTER_TABLE = Pattern.compile(
        "ALTER\\s+TABLE\\s+(?:IF\\s+EXISTS\\s+)?(?:ONLY\\s+)?" + SCHEMA + "\\.(\\w+)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern ALTER_COLUMN_DEFAULT = Pattern.compile(
        "\\bALTER\\s+(?:COLUMN\\s+)?(\\w+)\\s+(?:SET\\s+DEFAULT\\s+([^,]+)|DROP\\s+DEFAULT)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern CREATE_FUNCTION = Pattern.compile(
        "CREATE\\s+(?:OR\\s+REPLACE\\s+)?FUNCTION\\s+" + SCHEMA + "\\.(\\w+)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern ASSIGNED_TIME = Pattern.compile(
        "NEW\\.(\\w+)\\s*:?=\\s*" + TIME_FUNCTION, Pattern.CASE_INSENSITIVE);
    private static final Pattern CREATE_TRIGGER = Pattern.compile(
        "CREATE\\s+(?:OR\\s+REPLACE\\s+)?TRIGGER\\s+(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRIGGER_ON = Pattern.compile(
        "\\bON\\s+" + SCHEMA + "\\.(\\w+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRIGGER_EXECUTES = Pattern.compile(
        "\\bEXECUTE\\s+(?:FUNCTION|PROCEDURE)\\s+" + SCHEMA + "\\.(\\w+)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern DROP_TRIGGER = Pattern.compile(
        "DROP\\s+TRIGGER\\s+(?:IF\\s+EXISTS\\s+)?(\\w+)\\s+ON\\s+" + SCHEMA + "\\.(\\w+)",
        Pattern.CASE_INSENSITIVE);

    @Test
    void no_main_class_reads_the_time_past_the_injected_clock() {
        assertThat(offenders(SourceTree.root("main")))
            .as("a time read past the clock cannot be fixed by a test and drifts from the "
                + "time every other part of the call reads. Inject java.time.Clock and read "
                + "it (C20.1); only %s builds the system clock", PRODUCER)
            .isEmpty();
    }

    @Test
    void no_migration_leaves_a_trigger_or_default_that_sets_a_token() {
        assertThat(tokenStamps(SourceTree.migrations(), Integer.MAX_VALUE))
            .as("a time the database writes is not the injected clock's: no test can fix it, "
                + "and it is the transaction's start, so a later write can carry an earlier "
                + "token. The application writes the token from the clock (C10.1, C20.1); drop "
                + "the trigger and the default in a migration")
            .isEmpty();
    }

    /** The red state, observed on every build: the migrations before the move. */
    @Test
    void the_check_reports_the_trigger_and_the_default_the_migrations_before_the_move_left() {
        assertThat(tokenStamps(SourceTree.migrations(), BEFORE_THE_MOVE))
            .as("RED STATE, observed: up to V%d the column default and the trigger of V17 set "
                + "the token, and each must be reported", BEFORE_THE_MOVE)
            .containsExactly(
                "task.updated_at: a default sets it to a time (V17)",
                "task.updated_at: trigger task_stamp_updated_at sets it to a time (V17)");
    }

    /** The red state of the replay, observed on every build: drop, re-create, alter. */
    @Test
    void the_replay_reads_a_drop_a_second_creation_and_an_altered_default() {
        Map<String, String> planted = new LinkedHashMap<>();
        planted.put("V1__made.sql", """
            CREATE TABLE dispatch.task (
                id BIGINT,
                updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
            );
            CREATE FUNCTION dispatch.stamp() RETURNS TRIGGER AS $$
            BEGIN
                NEW.updated_at := clock_timestamp();
                RETURN NEW;
            END;
            $$ LANGUAGE plpgsql;
            CREATE TRIGGER task_stamp BEFORE UPDATE ON dispatch.task
                FOR EACH ROW EXECUTE FUNCTION dispatch.stamp();
            """);
        planted.put("V2__dropped.sql", """
            DROP TRIGGER task_stamp ON dispatch.task;
            ALTER TABLE dispatch.task ALTER COLUMN updated_at DROP DEFAULT;
            """);
        planted.put("V3__back.sql", """
            -- CREATE TRIGGER in_a_comment BEFORE UPDATE ON dispatch.task
            --     FOR EACH ROW EXECUTE FUNCTION dispatch.stamp();
            CREATE TRIGGER task_stamp_again BEFORE UPDATE ON dispatch.task
                FOR EACH ROW EXECUTE FUNCTION dispatch.stamp();
            ALTER TABLE dispatch.task ALTER COLUMN updated_at SET DEFAULT CURRENT_TIMESTAMP;
            """);

        assertThat(replay(planted))
            .as("RED STATE, observed: a trigger created again and a default set again after "
                + "both were dropped must each be reported, and a commented statement must not")
            .containsExactly(
                "task.updated_at: a default sets it to a time (V3)",
                "task.updated_at: trigger task_stamp_again sets it to a time (V3)");
        planted.remove("V3__back.sql");
        assertThat(replay(planted)).as("dropped is gone").isEmpty();
    }

    /** The red state, observed on every build. */
    @Test
    void the_check_reports_each_shape_that_reads_past_the_clock() {
        String planted = """
            class Lease {
                Instant a = Instant.now();
                long b = System.currentTimeMillis();
                Clock c = Clock.systemUTC();
                Instant d = Instant.now(clock);
            }
            """;

        assertThat(readsIn("Lease", planted))
            .as("RED STATE, observed: now() without a clock, the millisecond counter and a "
                + "system clock outside the producer must each be reported; now(clock) must "
                + "not")
            .containsExactly("Lease: Instant.now()", "Lease: System.currentTimeMillis(",
                "Lease: Clock.systemUTC(");
        assertThat(readsIn(PRODUCER, "class P { Clock c = Clock.systemUTC(); }"))
            .as("RED STATE, observed: the producer itself is admitted")
            .isEmpty();
    }

    private static List<String> offenders(Path root) {
        List<String> offenders = new ArrayList<>();
        for (Path file : SourceTree.files(root)) {
            String fqcn = SourceTree.fqcn(root, file);
            offenders.addAll(readsIn(fqcn.substring(fqcn.lastIndexOf('.') + 1),
                SourceTree.code(file)));
        }
        return offenders;
    }

    static List<String> readsIn(String name, String code) {
        List<String> found = new ArrayList<>();
        collect(found, name, NOW_WITHOUT_CLOCK.matcher(code));
        collect(found, name, MILLIS.matcher(code));
        if (!name.equals(PRODUCER)) {
            collect(found, name, SYSTEM_CLOCK.matcher(code));
        }
        return found;
    }

    /** What the migrations up to {@code lastVersion} leave stamping a token column. */
    private static List<String> tokenStamps(List<Path> migrations, int lastVersion) {
        Map<String, String> applied = new LinkedHashMap<>();
        for (Path migration : migrations) {
            if (SourceTree.versionOf(migration) <= lastVersion) {
                applied.put(migration.getFileName().toString(), SourceTree.text(migration));
            }
        }
        return replay(applied);
    }

    /**
     * Replays migrations, by file name and text, in the order given, and
     * answers each token column a default or a trigger leaves setting a time,
     * with the version that set it.
     *
     * <p>A migration is read statement by statement, each statement matched
     * at its start: a table created, a column's default set or dropped, a
     * function created, a trigger created or dropped. Comments are dropped
     * first, so a statement that is only described is no statement.
     */
    static List<String> replay(Map<String, String> migrations) {
        Map<String, String> defaults = new LinkedHashMap<>();
        Map<String, Set<String>> stampedBy = new LinkedHashMap<>();
        Map<String, String[]> triggers = new LinkedHashMap<>();
        for (Map.Entry<String, String> migration : migrations.entrySet()) {
            String version = migration.getKey().replaceFirst("__.*", "");
            for (String statement : statementsOf(migration.getValue().replaceAll("--[^\\n]*", " "))) {
                Matcher m;
                if ((m = CREATE_TABLE.matcher(statement)).lookingAt()) {
                    for (String line : statement.substring(m.end()).split(",\\s*\\n")) {
                        String[] words = line.strip().split("\\s+", 2);
                        if (words.length > 1 && TIME_DEFAULT.matcher(words[1]).find()) {
                            defaults.put(m.group(1) + "." + words[0], version);
                        }
                    }
                } else if ((m = ALTER_TABLE.matcher(statement)).lookingAt()) {
                    String table = m.group(1);
                    Matcher column = ALTER_COLUMN_DEFAULT.matcher(statement);
                    while (column.find()) {
                        String name = table + "." + column.group(1);
                        if (column.group(2) != null && TIME_VALUE.matcher(column.group(2)).find()) {
                            defaults.put(name, version);
                        } else {
                            defaults.remove(name);
                        }
                    }
                } else if ((m = CREATE_FUNCTION.matcher(statement)).lookingAt()) {
                    Set<String> columns = new LinkedHashSet<>();
                    Matcher assigned = ASSIGNED_TIME.matcher(statement);
                    while (assigned.find()) {
                        columns.add(assigned.group(1));
                    }
                    stampedBy.put(m.group(1), columns);
                } else if ((m = CREATE_TRIGGER.matcher(statement)).lookingAt()) {
                    Matcher on = TRIGGER_ON.matcher(statement);
                    Matcher executes = TRIGGER_EXECUTES.matcher(statement);
                    if (on.find() && executes.find()) {
                        triggers.put(on.group(1) + "/" + m.group(1),
                            new String[] {executes.group(1), version});
                    }
                } else if ((m = DROP_TRIGGER.matcher(statement)).lookingAt()) {
                    triggers.remove(m.group(2) + "/" + m.group(1));
                }
            }
        }
        List<String> found = new ArrayList<>();
        for (String column : TOKEN_COLUMNS) {
            if (defaults.containsKey(column)) {
                found.add(column + ": a default sets it to a time (" + defaults.get(column) + ")");
            }
            String table = column.substring(0, column.indexOf('.'));
            String name = column.substring(column.indexOf('.') + 1);
            triggers.forEach((key, fired) -> {
                if (key.startsWith(table + "/")
                        && stampedBy.getOrDefault(fired[0], Set.of()).contains(name)) {
                    found.add(column + ": trigger " + key.substring(table.length() + 1)
                        + " sets it to a time (" + fired[1] + ")");
                }
            });
        }
        return found;
    }

    /**
     * The statements of a migration, each stripped: its text cut at every
     * semicolon that stands outside a dollar-quoted body.
     */
    static List<String> statementsOf(String sql) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '$' && i + 1 < sql.length() && sql.charAt(i + 1) == '$') {
                quoted = !quoted;
                current.append("$$");
                i++;
            } else if (c == ';' && !quoted) {
                statements.add(current.toString().strip());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.toString().isBlank()) {
            statements.add(current.toString().strip());
        }
        return statements;
    }

    private static void collect(List<String> found, String name, Matcher m) {
        while (m.find()) {
            found.add(name + ": " + m.group().replaceAll("\\s+", ""));
        }
    }
}
