package ai.kumbuka.dispatch.domain;

import ai.kumbuka.dispatch.platform.PlatformFixture;
import org.eclipse.microprofile.config.ConfigProvider;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Brings a fresh task into one of the eleven situations of {@link
 * TransitionMatrixTest}, through the kernel itself.
 *
 * <p>Every step is a real call of {@link TaskService} under the service role,
 * except the one thing no call does: letting a lease run out. That is a write
 * of {@code lease_expires_at} into the past as the administrator, as {@code
 * LazyExpiryIT} does it for the exchange -- how a lease came to lapse is not
 * what is under test, what the kernel does with a lapsed one is.
 */
final class TaskStage {

    static final Actor C = TransitionMatrixTest.C;
    static final Actor H = TransitionMatrixTest.H;
    static final Actor K = TransitionMatrixTest.K;

    static final List<String> SITUATIONS = List.of("draft", "open", "deferred", "active",
        "lapsed", "asked", "waiting", "blocked", "parked", "delivered", "closed");

    private final TaskService tasks;
    private final UUID scope;

    TaskStage(TaskService tasks, UUID scope) {
        this.tasks = tasks;
        this.scope = scope;
    }

    /** A staged task: where it is, and H's receipt where H took it up. */
    record Staged(ExchangeAddress address, UUID identity, String selector, String receipt) {
    }

    /** A fresh task in {@code situation}, under {@code selector}. */
    Staged stage(String situation, String selector) {
        return switch (situation) {
            case "draft" -> draft(selector);
            case "open" -> open(selector);
            case "deferred" -> deferred(selector);
            case "active" -> active(selector);
            case "lapsed" -> lapse(active(selector));
            case "asked" -> after(active(selector), TaskVerb.ASK,
                new TaskInput.Question("which one?", List.of("yes", "no"), true));
            case "waiting" -> after(active(selector), TaskVerb.HOLD,
                new TaskInput.Pause(HoldReason.DEPENDENCY, null));
            case "blocked" -> after(active(selector), TaskVerb.HOLD,
                new TaskInput.Pause(HoldReason.EXTERNAL, null));
            case "parked" -> parked(selector);
            case "delivered" -> after(active(selector), TaskVerb.DELIVER,
                new TaskInput.Delivery("the answer", null));
            case "closed" -> closed(selector);
            default -> throw new IllegalArgumentException(situation);
        };
    }

    Staged draft(String selector) {
        TaskView v = tasks.create(scope, selector, null,
            new TaskService.Draft("a staged task", "code", "the commission", null), C,
            IdempotencyKey.NONE);
        return new Staged(v.address(), v.identity(), selector, null);
    }

    Staged open(String selector) {
        Staged d = draft(selector);
        tasks.act(scope, d.address(), TaskVerb.SEND, TaskCall.by(C).withConflictToken(token(d)));
        return d;
    }

    Staged active(String selector) {
        return claimedBy(open(selector), H);
    }

    Staged claimedBy(Staged s, Actor executor) {
        TaskClaim claim = tasks.claim(scope, s.address(), TaskCall.by(executor));
        return new Staged(s.address(), s.identity(), s.selector(), claim.receipt());
    }

    Staged deferred(String selector) {
        Staged a = active(selector);
        tasks.act(scope, a.address(), TaskVerb.DEFER, TaskCall.by(H).withReceipt(a.receipt())
            .with(new TaskInput.Deferral(Instant.now().plusSeconds(3600), null)));
        return a;
    }

    /**
     * Three lapses, as TAR-0004 section 3 says: "after the third lapse".
     *
     * <p>A literal, not the kernel's constant. Staged from the constant, a
     * kernel that parked after the fourth would stage four lapses and the
     * parking case would stay green.
     */
    Staged parked(String selector) {
        Staged s = open(selector);
        for (int lapse = 1; lapse <= 3; lapse++) {
            s = lapse(claimedBy(s, H));
        }
        return s;
    }

    Staged closed(String selector) {
        Staged o = open(selector);
        tasks.act(scope, o.address(), TaskVerb.WITHDRAW, TaskCall.by(C).withConflictToken(token(o)));
        return o;
    }

    Staged after(Staged s, TaskVerb verb, TaskInput payload) {
        tasks.act(scope, s.address(), verb, TaskCall.by(H).withReceipt(s.receipt()).with(payload));
        return s;
    }

    /** Lets the lease of a held task run out; writes nothing else. */
    Staged lapse(Staged s) {
        PlatformFixture.run("UPDATE dispatch.task SET lease_expires_at = now() - interval '1 minute' "
            + "WHERE uuid = '" + s.identity() + "'");
        return s;
    }

    /** The conflict token the task carries now, as any caller's read hands it out. */
    String token(Staged s) {
        return tasks.read(scope, s.address(), C).conflictToken();
    }

    /** What the row stores, read as the administrator: the stored side of the gap. */
    record Row(long id, String state, String holdReason, String outcome, String holder,
               Instant leaseExpiresAt, int lapseCount, Instant notBefore,
               String stateChangedBy, Long curatedIn, String receiptHash, String createdBy,
               String updatedBy) {
    }

    static Row row(UUID identity) {
        var config = ConfigProvider.getConfig();
        try (Connection c = DriverManager.getConnection(
                config.getValue("test.db.url", String.class),
                config.getValue("test.db.admin.username", String.class),
                config.getValue("test.db.admin.password", String.class));
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT id, state, hold_reason, outcome, holder_subject, "
                 + "lease_expires_at, lapse_count, not_before, state_changed_by, curated_in_id, "
                 + "holder_receipt_hash, created_by, updated_by "
                 + "FROM dispatch.task WHERE uuid = '" + identity + "'")) {
            if (!rs.next()) {
                return null;
            }
            return new Row(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getString(5), instant(rs.getTimestamp(6)), rs.getInt(7),
                instant(rs.getTimestamp(8)), rs.getString(9), (Long) rs.getObject(10),
                rs.getString(11), rs.getString(12), rs.getString(13));
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * The children of a bracket root that are not closed, as {@code sub:state},
     * read as the administrator: what every transaction has committed.
     */
    static List<String> unfinishedChildren(UUID rootIdentity) {
        var config = ConfigProvider.getConfig();
        try (Connection c = DriverManager.getConnection(
                config.getValue("test.db.url", String.class),
                config.getValue("test.db.admin.username", String.class),
                config.getValue("test.db.admin.password", String.class));
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT c.sub, c.state FROM dispatch.task r "
                 + "JOIN dispatch.task c ON c.tenant_id = r.tenant_id AND c.scope_id = r.scope_id "
                 + "AND c.selector_id = r.selector_id AND c.number = r.number AND c.sub > 0 "
                 + "WHERE r.uuid = '" + rootIdentity + "' AND c.state <> 'closed' ORDER BY c.sub")) {
            List<String> out = new java.util.ArrayList<>();
            while (rs.next()) {
                out.add(rs.getInt(1) + ":" + rs.getString(2));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** How many tasks exist across every tenant, read as the administrator. */
    static int rowCount() {
        var config = ConfigProvider.getConfig();
        try (Connection c = DriverManager.getConnection(
                config.getValue("test.db.url", String.class),
                config.getValue("test.db.admin.username", String.class),
                config.getValue("test.db.admin.password", String.class));
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT count(*) FROM dispatch.task")) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The bracket counter of a selector, read as the administrator. */
    static int nextNumber(UUID tenant, UUID scope, String selector) {
        var config = ConfigProvider.getConfig();
        try (Connection c = DriverManager.getConnection(
                config.getValue("test.db.url", String.class),
                config.getValue("test.db.admin.username", String.class),
                config.getValue("test.db.admin.password", String.class));
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT next_number FROM dispatch.selector "
                 + "WHERE tenant_id = '" + tenant + "' AND scope_id = '" + scope
                 + "' AND name = '" + selector + "'")) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The text rows of a task, as {@code type[suffix]=text}, in the order written. */
    static List<String> texts(UUID identity) {
        var config = ConfigProvider.getConfig();
        try (Connection c = DriverManager.getConnection(
                config.getValue("test.db.url", String.class),
                config.getValue("test.db.admin.username", String.class),
                config.getValue("test.db.admin.password", String.class));
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT x.text_type, x.addendum_suffix, x.text "
                 + "FROM dispatch.task_text x JOIN dispatch.task t ON t.id = x.task_id "
                 + "WHERE t.uuid = '" + identity + "' ORDER BY x.id")) {
            List<String> out = new java.util.ArrayList<>();
            while (rs.next()) {
                out.add(rs.getString(1) + (rs.getString(2) == null ? "" : "[" + rs.getString(2)
                    + "]") + "=" + rs.getString(3));
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Instant instant(java.sql.Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
