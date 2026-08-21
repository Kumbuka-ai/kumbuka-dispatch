package ai.kumbuka.dispatch.substrate;

import ai.kumbuka.dispatch.tenancy.TenantBound;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Read and write access to the substrate's one table, through both
 * enforcement layers.
 *
 * <p>Every method is {@link TenantBound} and transactional, which is what
 * puts the binding of {@code app.tenant_id} inside the transaction the
 * statement runs in. That combination — and not either half of it — is what
 * the probes observe: the ORM filter scopes the query, the GUC scopes the
 * policy, and removing either one is visible.
 *
 * <p>This is not a domain surface. It holds no verb of the exchange; it
 * exists so the substrate's guarantees can be stated about a real read and a
 * real write rather than about an empty schema.
 */
@ApplicationScoped
@TenantBound
public class ScopeRepository {

    @Inject EntityManager em;

    @Transactional
    public List<Scope> findAll() {
        return em.createQuery("SELECT s FROM Scope s ORDER BY s.slug", Scope.class)
            .getResultList();
    }

    @Transactional
    public Scope register(UUID platformScopeId, String slug) {
        Scope scope = new Scope();
        scope.platformScopeId = platformScopeId;
        scope.slug = slug;
        em.persist(scope);
        em.flush();
        return scope;
    }

    /** The count as the ORM sees it, for probes that assert on emptiness. */
    @Transactional
    public long count() {
        return em.createQuery("SELECT COUNT(s) FROM Scope s", Long.class).getSingleResult();
    }
}
