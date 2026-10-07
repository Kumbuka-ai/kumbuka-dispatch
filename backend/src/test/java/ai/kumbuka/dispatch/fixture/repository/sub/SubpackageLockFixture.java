package ai.kumbuka.dispatch.fixture.repository.sub;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import java.util.List;

/**
 * A locking read without a refresh in a subpackage of a repository package,
 * for {@code LockedReadRefreshTest} to find.
 *
 * <p>The guard selects the repository package with its subpackages; a
 * selection that took the package alone would walk past this class and report
 * nothing. Lives in the test sources and is never wired into anything.
 */
public class SubpackageLockFixture {

    private final EntityManager em;

    public SubpackageLockFixture(EntityManager em) {
        this.em = em;
    }

    /** Locks and answers the context's object. */
    public List<Object> lockedInSubpackage(Long id) {
        return em.createQuery("SELECT s FROM Selector s WHERE s.id = :id", Object.class)
            .setParameter("id", id)
            .setLockMode(LockModeType.PESSIMISTIC_WRITE)
            .getResultList();
    }
}
