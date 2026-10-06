package ai.kumbuka.dispatch.tenancy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * The two tables V17 copies from, {@code exchange} and {@code idempotency_key},
 * captured so that a state after V17 can be compared with the state before it:
 * every row, and what the catalogue says about them.
 *
 * <p>V17 lifts {@code FORCE ROW LEVEL SECURITY} from both for the copy. Whether
 * the copy succeeds or a stop check aborts it, the source must come out as it
 * went in, and this is the one comparison both cases make.
 */
final class SourceTables {

    private SourceTables() {
    }

    /** Rows and catalogue of the two source tables at one moment. */
    record Snapshot(String exchange, String idempotencyKey, String catalogue) {
    }

    static Snapshot snapshot(Connection c) throws SQLException {
        return new Snapshot(digest(c, "dispatch.exchange"), digest(c, "dispatch.idempotency_key"),
            catalogue(c));
    }

    /** Every row of a table as text, in id order, folded into one digest. */
    static String digest(Connection c, String table) throws SQLException {
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) || ':' || coalesce(md5(string_agg("
                 + "t::text, '|' ORDER BY t.id)), '') FROM " + table + " t")) {
            rs.next();
            return rs.getString(1);
        }
    }

    /** What the catalogue says about the two source tables, as one comparable text. */
    static String catalogue(Connection c) throws SQLException {
        List<String> facts = new ArrayList<>();
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("""
                SELECT 'table ' || relname || ' rls=' || relrowsecurity || ' force=' || relforcerowsecurity AS f
                  FROM pg_class WHERE oid IN ('dispatch.exchange'::regclass, 'dispatch.idempotency_key'::regclass)
                UNION ALL
                SELECT 'policy ' || tablename || ' ' || policyname || ' ' || coalesce(qual, '')
                       || ' ' || coalesce(with_check, '')
                  FROM pg_policies WHERE schemaname = 'dispatch'
                   AND tablename IN ('exchange', 'idempotency_key')
                UNION ALL
                SELECT 'trigger ' || tgrelid::regclass || ' ' || tgname || ' ' || tgenabled::text
                  FROM pg_trigger WHERE tgrelid IN ('dispatch.exchange'::regclass,
                                                    'dispatch.idempotency_key'::regclass)
                   AND NOT tgisinternal
                UNION ALL
                SELECT 'constraint ' || conrelid::regclass || ' ' || conname || ' '
                       || pg_get_constraintdef(oid)
                  FROM pg_constraint WHERE conrelid IN ('dispatch.exchange'::regclass,
                                                        'dispatch.idempotency_key'::regclass)
                UNION ALL
                SELECT 'grant ' || table_name || ' ' || grantee || ' ' || privilege_type
                  FROM information_schema.role_table_grants
                 WHERE table_schema = 'dispatch' AND table_name IN ('exchange', 'idempotency_key')
                ORDER BY 1
                """)) {
            while (rs.next()) {
                facts.add(rs.getString(1).replace("table dispatch.", "").replace("table ", ""));
            }
        }
        return String.join("\n", facts);
    }
}
