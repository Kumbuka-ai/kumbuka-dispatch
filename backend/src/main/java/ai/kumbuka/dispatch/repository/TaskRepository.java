package ai.kumbuka.dispatch.repository;

import ai.kumbuka.dispatch.domain.ExchangeAddress;
import ai.kumbuka.dispatch.domain.Selector;
import ai.kumbuka.dispatch.domain.SpentTaskKey;
import ai.kumbuka.dispatch.domain.Task;
import ai.kumbuka.dispatch.domain.TaskText;
import ai.kumbuka.dispatch.domain.TextType;
import ai.kumbuka.dispatch.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Every statement the task kernel issues against {@code task}, {@code
 * task_text} and {@code task_idempotency_key}.
 *
 * <p>Placed as {@link ExchangeRepository} is, and for the same reasons: JPA
 * lives in this package, refusals stay above it, and an absent row is an empty
 * {@link Optional}. Its own repository rather than a share of the exchange's,
 * because that one is bound to {@code exchange} and goes when the old kernel
 * goes.
 */
@ApplicationScoped
@TenantBound
public class TaskRepository {

    private static final String P_SCOPE = "scope";
    private static final String P_SELECTOR = "sel";
    private static final String P_NUMBER = "num";
    private static final String P_SUB = "sub";
    private static final String P_TASK = "task";

    /** The prefix of the draw's apparatus parameters, numbered from zero. */
    private static final String P_APPARATUS = "ap";

    @Inject EntityManager em;

    // ----------------------------------------------------------------------
    // task
    // ----------------------------------------------------------------------

    /** The task at an address, if there is one. */
    @Transactional
    public Optional<Task> find(UUID scopeId, ExchangeAddress address) {
        return at(scopeId, address, LockModeType.NONE);
    }

    /**
     * The task at an address, locked for the caller's transaction.
     *
     * <p>A transition locks the row before it computes the effective state, so
     * two calls on one task decide one after the other and the second decides
     * on what the first wrote.
     */
    @Transactional
    public Optional<Task> lock(UUID scopeId, ExchangeAddress address) {
        return at(scopeId, address, LockModeType.PESSIMISTIC_WRITE);
    }

    private Optional<Task> at(UUID scopeId, ExchangeAddress address, LockModeType mode) {
        return em.createQuery("""
                SELECT t FROM Task t
                WHERE t.scopeId = :scope AND t.selector.name = :sel
                  AND t.number = :num AND t.sub = :sub
                """, Task.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_SELECTOR, address.selector())
            .setParameter(P_NUMBER, address.number())
            .setParameter(P_SUB, address.sub())
            .setLockMode(mode)
            .getResultList()
            .stream()
            .findFirst();
    }

    /** The task with this surrogate in one scope. */
    @Transactional
    public Optional<Task> findById(UUID scopeId, Long id) {
        return em.createQuery("""
                SELECT t FROM Task t WHERE t.id = :id AND t.scopeId = :scope
                """, Task.class)
            .setParameter("id", id)
            .setParameter(P_SCOPE, scopeId)
            .getResultList()
            .stream()
            .findFirst();
    }

    /**
     * The task with this surrogate in whatever scope of the tenant holds it.
     *
     * <p>For the target of {@code curated_in}, which may lie in any scope of
     * the tenant. The tenant filter and the row policy still bind it.
     */
    @Transactional
    public Optional<Task> findAnywhere(Long id) {
        return Optional.ofNullable(id).flatMap(key -> em.createQuery("""
                SELECT t FROM Task t WHERE t.id = :id
                """, Task.class)
            .setParameter("id", key)
            .getResultList()
            .stream()
            .findFirst());
    }

    /** The children of a bracket, in sub order. */
    @Transactional
    public List<Task> children(UUID scopeId, Long selectorId, int number) {
        return em.createQuery("""
                SELECT t FROM Task t
                WHERE t.scopeId = :scope AND t.selector.id = :sel AND t.number = :num
                  AND t.sub > 0
                ORDER BY t.sub
                """, Task.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_SELECTOR, selectorId)
            .setParameter(P_NUMBER, number)
            .getResultList();
    }

    /**
     * The tasks of a selector whose apparatus matches one of the patterns, in
     * address order. Every pattern list may be empty, which narrows nothing.
     */
    @Transactional
    public List<Task> listing(UUID scopeId, String selector, List<String> apparatusPatterns,
                              Collection<Integer> numbers) {
        StringBuilder jpql = new StringBuilder("""
            SELECT t FROM Task t
            WHERE t.scopeId = :scope AND t.selector.name = :sel
            """);
        appendApparatus(jpql, apparatusPatterns.size(), "t.apparatus");
        if (!numbers.isEmpty()) {
            jpql.append("  AND t.number IN :numbers\n");
        }
        jpql.append("ORDER BY t.number, t.sub");

        var query = em.createQuery(jpql.toString(), Task.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_SELECTOR, selector);
        bindApparatus(query, apparatusPatterns);
        if (!numbers.isEmpty()) {
            query.setParameter("numbers", numbers);
        }
        return query.getResultList();
    }

    /**
     * The next drawable task of a selector, locked against every other draw.
     *
     * <p>Drawable is effectively {@code open} with {@code not_before} absent
     * or reached (concept section 2.1): stored {@code open}, or stored
     * {@code active} with a lapsed lease short of the lapse that parks it. The
     * SQL states that set because the lock has to be taken in the statement
     * that selects; the kernel decides on the locked row again with {@code
     * TaskSituation} and {@code Decision}, so a row this predicate let through
     * wrongly is refused rather than taken.
     *
     * <p>Native because the lock is {@code SKIP LOCKED}, which JPA cannot
     * express: {@code PESSIMISTIC_WRITE} waits for the other transaction
     * instead of stepping over its row, and with {@code LIMIT 1} the waiting
     * draw then finds the row taken and answers empty while another task was
     * free.
     *
     * @param lapsesRecordedToPark the lapse count at which one more lapse parks
     */
    @Transactional
    public Optional<Task> lockNextDrawable(UUID scopeId, String selector,
                                           List<String> apparatusPatterns, Instant now,
                                           int lapsesRecordedToPark) {
        if (apparatusPatterns.isEmpty()) {
            throw new IllegalArgumentException("a draw filters on at least one apparatus pattern");
        }
        StringBuilder sql = new StringBuilder("""
            SELECT t.id FROM dispatch.task t
            JOIN dispatch.selector s ON s.id = t.selector_id
            WHERE t.scope_id = :scope
              AND s.name = :sel
              AND ((t.state = 'open' AND (t.not_before IS NULL OR t.not_before <= :now))
                   OR (t.state = 'active'
                       AND (t.lease_expires_at IS NULL OR t.lease_expires_at <= :now)
                       AND t.lapse_count < :park))
            """);
        appendApparatus(sql, apparatusPatterns.size(), "t.apparatus");
        sql.append("""
            ORDER BY t.number, t.sub
            LIMIT 1
            FOR UPDATE OF t SKIP LOCKED
            """);

        var query = em.createNativeQuery(sql.toString())
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_SELECTOR, selector)
            .setParameter("now", java.sql.Timestamp.from(now))
            .setParameter("park", lapsesRecordedToPark);
        bindApparatus(query, apparatusPatterns);

        List<?> ids = query.getResultList();
        if (ids.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(em.find(Task.class, ((Number) ids.get(0)).longValue()));
    }

    /**
     * The apparatus conjunct: the patterns as alternatives, {@code *} read as
     * any run of characters. The surface's character rule keeps {@code %} and
     * {@code _} out of a pattern, so nothing needs escaping (see {@link
     * ExchangeRepository}).
     */
    private static void appendApparatus(StringBuilder query, int patterns, String column) {
        if (patterns == 0) {
            return;
        }
        query.append("  AND (");
        for (int i = 0; i < patterns; i++) {
            query.append(i == 0 ? "" : " OR ").append(column).append(" LIKE :")
                .append(P_APPARATUS).append(i);
        }
        query.append(")\n");
    }

    private static void bindApparatus(jakarta.persistence.Query query, List<String> patterns) {
        for (int i = 0; i < patterns.size(); i++) {
            query.setParameter(P_APPARATUS + i, patterns.get(i).replace('*', '%'));
        }
    }

    /** The selector row, locked so its counter can be read and bumped in this transaction. */
    @Transactional
    public Optional<Selector> lockSelector(Long selectorId) {
        return em.createQuery("SELECT s FROM Selector s WHERE s.id = :id", Selector.class)
            .setParameter("id", selectorId)
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .getResultList()
            .stream()
            .findFirst();
    }

    /** The highest sub of a bracket, or null when it has no task. */
    @Transactional
    public Integer highestSub(UUID scopeId, Long selectorId, int number) {
        return em.createQuery("""
                SELECT MAX(t.sub) FROM Task t
                WHERE t.scopeId = :scope AND t.selector.id = :sel AND t.number = :num
                """, Integer.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_SELECTOR, selectorId)
            .setParameter(P_NUMBER, number)
            .getSingleResult();
    }

    /** Inserts and flushes, so a constraint answers at the call site. */
    @Transactional
    public Task insert(Task task) {
        em.persist(task);
        em.flush();
        return task;
    }

    /**
     * Deletes a draft: its texts and idempotency records first, then the task.
     *
     * <p>In that order because no key deletes along (V17 section 4): a
     * cascade would reach the text guard while the task row is already being
     * deleted. The guard refuses either delete once the task is sent.
     */
    @Transactional
    public void delete(Task task) {
        em.createQuery("DELETE FROM TaskText x WHERE x.taskId = :task")
            .setParameter(P_TASK, task.id).executeUpdate();
        em.createQuery("DELETE FROM SpentTaskKey k WHERE k.taskId = :task")
            .setParameter(P_TASK, task.id).executeUpdate();
        em.remove(task);
        em.flush();
    }

    /** Flushes pending changes, so the table's checks answer at the call site. */
    @Transactional
    public void flush() {
        em.flush();
    }

    // ----------------------------------------------------------------------
    // task_text
    // ----------------------------------------------------------------------

    /** Every text of a task, in the order written. */
    @Transactional
    public List<TaskText> texts(Long taskId) {
        return em.createQuery("""
                SELECT x FROM TaskText x WHERE x.taskId = :task ORDER BY x.id
                """, TaskText.class)
            .setParameter(P_TASK, taskId)
            .getResultList();
    }

    /** Every text of several tasks, in the order written. */
    @Transactional
    public List<TaskText> texts(Collection<Long> taskIds) {
        if (taskIds.isEmpty()) {
            return List.of();
        }
        return em.createQuery("""
                SELECT x FROM TaskText x WHERE x.taskId IN :tasks ORDER BY x.taskId, x.id
                """, TaskText.class)
            .setParameter("tasks", taskIds)
            .getResultList();
    }

    /** The highest addendum letter on one type of a task's texts, or null. */
    @Transactional
    public String highestSuffix(Long taskId, TextType type) {
        return em.createQuery("""
                SELECT MAX(x.addendumSuffix) FROM TaskText x
                WHERE x.taskId = :task AND x.textType = :type AND x.addendumSuffix IS NOT NULL
                """, String.class)
            .setParameter(P_TASK, taskId)
            .setParameter("type", type.wireName())
            .getSingleResult();
    }

    /** Inserts a text row and flushes. */
    @Transactional
    public TaskText insert(TaskText text) {
        em.persist(text);
        em.flush();
        return text;
    }

    // ----------------------------------------------------------------------
    // task_idempotency_key
    // ----------------------------------------------------------------------

    /** The row a caller's key holds in a scope, locked for update. */
    @Transactional
    public Optional<SpentTaskKey> lockKey(UUID scopeId, String callerSubject, String key) {
        return em.createQuery("""
                SELECT k FROM SpentTaskKey k
                WHERE k.scopeId = :scope AND k.callerSubject = :caller AND k.key = :key
                """, SpentTaskKey.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter("caller", callerSubject)
            .setParameter("key", key)
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .getResultList()
            .stream()
            .findFirst();
    }

    /** Remembers a spent key. */
    @Transactional
    public SpentTaskKey insert(SpentTaskKey key) {
        em.persist(key);
        em.flush();
        return key;
    }
}
