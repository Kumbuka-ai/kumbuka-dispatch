package ai.kumbuka.dispatch.repository;

import ai.kumbuka.dispatch.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The three statements the platform read contract is reached through.
 *
 * <p>The relation is not this service's. {@code platform.scope_access} is a
 * view published by the platform, on which this service holds {@code SELECT}
 * and nothing else, and the directory above translates its answer into the
 * typed refusals a caller sees. What lives here is only the fact that the
 * access happens through JPA, which is what the persistence boundary is about:
 * the layer is defined by the mechanism, not by who owns the table.
 *
 * <h2>Native, and why every statement here is</h2>
 *
 * There is no entity for the view and there should not be one — an entity
 * would make it look like a table this service maps and could write. The two
 * session settings are {@code set_config} and {@code current_setting}, which
 * have no JPQL expression at all. This is the enumerated native case the rule
 * set provides for, and the reason is written here rather than assumed.
 */
@ApplicationScoped
@TenantBound
public class ScopeAccessRepository {

    @Inject EntityManager em;

    /**
     * The access row for a slug, as the bound subject sees it, or empty.
     *
     * <p>Empty is returned rather than refused: whether "the subject may not
     * see it" is a refusal or an ordinary absence is the directory's
     * statement, and it needs the session check that precedes this call to
     * know which.
     */
    @Transactional
    public Optional<ScopeAccessRow> findBySlug(String slug) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT scope_id, tenant_id, slug, archived, kind, locked, can_write
                FROM platform.scope_access
                WHERE slug = :slug
                """)
            .setParameter("slug", slug)
            .getResultList();

        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Object[] row = rows.get(0);
        return Optional.of(new ScopeAccessRow(
            (UUID) row[0],
            (UUID) row[1],
            (String) row[2],
            (Boolean) row[3],
            (String) row[4],
            (Boolean) row[5],
            (Boolean) row[6]));
    }

    /**
     * The slug of a scope the bound subject may see, or empty.
     *
     * <p>The reverse of {@link #findBySlug}, and it reads the same
     * subject-filtered view — which is what makes it safe to call for a scope
     * the caller did not name. A curated answer's target may live in another
     * scope entirely (section 5.1), and its address can only be rendered
     * complete if the slug is known; a caller that may not see that scope gets
     * no slug and therefore no address, which is the same answer it would get
     * for a target that is not there.
     */
    @Transactional
    public Optional<String> slugOf(UUID scopeId) {
        List<Object[]> rows = em.createNativeQuery("""
                SELECT slug
                FROM platform.scope_access
                WHERE scope_id = :id
                """)
            .setParameter("id", scopeId)
            .getResultList();

        return rows.isEmpty()
            ? Optional.empty()
            : Optional.of(String.valueOf(rows.get(0)));
    }

    /**
     * Binds the calling subject for this transaction.
     *
     * <p>{@code is_local = true} is the whole safety property: the value resets
     * at commit or rollback and cannot ride a pooled connection into the next
     * caller's transaction.
     */
    @Transactional
    public void bindSubject(String subject) {
        em.createNativeQuery("SELECT set_config('app.subject', :v, true)")
            .setParameter("v", subject)
            .getSingleResult();
    }

    /** The bound tenant of this transaction, or null when nothing is bound. */
    @Transactional
    public Object boundTenant() {
        return em.createNativeQuery(
            "SELECT NULLIF(current_setting('app.tenant_id', true), '')").getSingleResult();
    }

    /** The bound subject of this transaction, or null when nothing is bound. */
    @Transactional
    public Object boundSubject() {
        return em.createNativeQuery(
            "SELECT NULLIF(current_setting('app.subject', true), '')").getSingleResult();
    }

    /**
     * One row of the read contract, as it comes off the view.
     *
     * <p>Distinct from the directory's own {@code ScopeAccess} on purpose. The
     * two carry the same seven values today; keeping them apart is what lets
     * the published shape of the view change without the type the domain reads
     * changing with it. That is not hypothetical: the view grew from four
     * columns to seven between two releases of the platform, and the split is
     * why the change arrived here as one edit to a query and a record rather
     * than as an edit to everything that reads a scope.
     *
     * <p>{@code kind}, {@code locked} and {@code canWrite} are the three the
     * platform added. {@code kind} is {@code project}, {@code private} or
     * {@code global} — the view no longer filters to the first, so a service
     * has to decide for itself which kinds it serves. {@code locked} is the
     * content lock, published beside {@code archived} because the two are
     * different refusals: archived is retired, locked is frozen.
     * {@code canWrite} is the calling subject's write right OVER A SERVICE
     * CHANNEL, which is the only channel this service is; a console admin's
     * override keys on the channel and is deliberately not in this column.
     */
    public record ScopeAccessRow(UUID scopeId, UUID tenantId, String slug, boolean archived,
                                 String kind, boolean locked, boolean canWrite) {
    }
}
