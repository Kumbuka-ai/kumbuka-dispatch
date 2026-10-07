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
 * <p>JPA lives in this package, refusals stay above it, and an absent row is
 * an empty {@link Optional}.
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
     * on what the first wrote. The task answered is the row as it stands under
     * the lock, also where this transaction read it earlier: see {@link
     * #underLock}.
     */
    @Transactional
    public Optional<Task> lock(UUID scopeId, ExchangeAddress address) {
        return at(scopeId, address, LockModeType.PESSIMISTIC_WRITE);
    }

    private Optional<Task> at(UUID scopeId, ExchangeAddress address, LockModeType mode) {
        Optional<Task> found = em.createQuery("""
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
        return mode == LockModeType.NONE ? found : found.map(this::underLock);
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
     * The task with this surrogate in one scope, locked for the caller's
     * transaction, as the row stands under the lock: see {@link #underLock}.
     */
    @Transactional
    public Optional<Task> lockById(UUID scopeId, Long id) {
        return em.createQuery("""
                SELECT t FROM Task t WHERE t.id = :id AND t.scopeId = :scope
                """, Task.class)
            .setParameter("id", id)
            .setParameter(P_SCOPE, scopeId)
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .getResultList()
            .stream()
            .findFirst()
            .map(this::underLock);
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

    /**
     * The children of a bracket, each locked for the caller's transaction, in
     * sub order.
     *
     * <p>Locked in the order of the address, so two transactions that lock the
     * children of one root take them in the same order. The caller holds the
     * root's lock first: the order is root before child wherever a call touches
     * both. A child that another transaction finished while this one waited,
     * or after this one read it without a lock, is read as that transaction
     * left it: see {@link #underLock}.
     */
    @Transactional
    public List<Task> lockChildren(UUID scopeId, Long selectorId, int number) {
        return em.createQuery("""
                SELECT t FROM Task t
                WHERE t.scopeId = :scope AND t.selector.id = :sel AND t.number = :num
                  AND t.sub > 0
                ORDER BY t.sub
                """, Task.class)
            .setParameter(P_SCOPE, scopeId)
            .setParameter(P_SELECTOR, selectorId)
            .setParameter(P_NUMBER, number)
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .getResultList()
            .stream()
            .map(this::underLock)
            .toList();
    }

    /**
     * The entity as its row stands now that this transaction holds the lock.
     * Every locking read of this repository answers through here, which
     * {@code LockedReadRefreshTest} holds.
     *
     * <p>A locking query takes the lock on the current row, but where the
     * transaction already holds the entity -- an earlier read without a lock
     * put it there -- the persistence context answers that earlier object and
     * not what the query found. Measured 2026-10-06 ({@code TaskLockedRowIT},
     * {@code TaskBracketLockIT}): without this a claim after such a read took
     * over a task another executor had claimed meanwhile, the closing of a
     * root overwrote a child accepted meanwhile as withdrawn, a draw counted a
     * lapse on the count read before and lost the one counted meanwhile, and a
     * second bracket root created at once took the number of the first. The
     * refresh reads the locked row again, at the cost of one more read per
     * locked row; it would drop a change not yet flushed, and every write of
     * the kernel flushes in the call that makes it.
     */
    private <T> T underLock(T entity) {
        em.refresh(entity);
        return entity;
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
     * wrongly is refused rather than taken. The task answered is the row as it
     * stands under the lock, also where this transaction read it earlier: see
     * {@link #underLock}.
     *
     * <p>Native because the lock is {@code SKIP LOCKED}, which JPA cannot
     * express: {@code PESSIMISTIC_WRITE} waits for the transaction holding the
     * row. Measured 2026-10-06: without {@code SKIP LOCKED} two concurrent
     * draws still take different tasks -- the waiting draw re-checks the row
     * once the lock is released, finds it no longer drawable and reads on --
     * but every draw queues behind every other. What {@code SKIP LOCKED} buys
     * is that a draw steps over a row another transaction holds instead of
     * waiting for it, which {@code TaskDrawIT} measures with a held lock.
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
        return Optional.ofNullable(em.find(Task.class, ((Number) ids.get(0)).longValue()))
            .map(this::underLock);
    }

    /**
     * The apparatus conjunct: the patterns as alternatives, {@code *} read as
     * any run of characters. The surface's character rule keeps {@code %} and
     * {@code _} out of a pattern, so nothing needs escaping.
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

    /**
     * The selector row, locked so its counter can be read and bumped in this
     * transaction, as the row stands under the lock: the creation read the
     * selector before it took the lock, see {@link #underLock}.
     */
    @Transactional
    public Optional<Selector> lockSelector(Long selectorId) {
        return em.createQuery("SELECT s FROM Selector s WHERE s.id = :id", Selector.class)
            .setParameter("id", selectorId)
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .getResultList()
            .stream()
            .findFirst()
            .map(this::underLock);
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

    /**
     * Takes the lock of one caller's key in a scope, held until the caller's
     * transaction ends, and waits while another transaction holds it.
     *
     * <p>A transaction-bound advisory lock of Postgres over the three values
     * the unique key of {@code task_idempotency_key} carries. The row lock of
     * {@link #lockKey} cannot serialise the first two calls under a new key:
     * neither finds a row to lock, and each would do its work. Under this lock
     * the second reads the key only once the first has committed, and so reads
     * the first's entry as a repeat does.
     *
     * <p>Not a locking read: it answers no row, so {@link #underLock} has
     * nothing to refresh. Two different keys that hash alike wait on each
     * other and decide nothing for each other.
     */
    @Transactional
    public void lockKeyUse(UUID scopeId, String callerSubject, String key) {
        em.createNativeQuery("""
                SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(
                    CAST(:scope AS text) || chr(31) || CAST(:caller AS text) || chr(31)
                        || CAST(:key AS text), 0))) AS held
                """)
            .setParameter(P_SCOPE, scopeId.toString())
            .setParameter("caller", callerSubject)
            .setParameter("key", key)
            .getSingleResult();
    }

    /**
     * The row a caller's key holds in a scope, locked for update, as the row
     * stands under the lock: see {@link #underLock}.
     */
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
            .findFirst()
            .map(this::underLock);
    }

    /** Remembers a spent key. */
    @Transactional
    public SpentTaskKey insert(SpentTaskKey key) {
        em.persist(key);
        em.flush();
        return key;
    }
}
