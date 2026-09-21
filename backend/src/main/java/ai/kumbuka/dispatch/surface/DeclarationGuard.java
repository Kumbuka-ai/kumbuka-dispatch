package ai.kumbuka.dispatch.surface;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

/**
 * Refuses to bring the service up on a declaration it cannot serve.
 *
 * <p>This is the mechanism behind section 4.4's last sentence — "the service
 * refuses to start with an undeclared reason in its catalogue". A guard at
 * start-up rather than a test, because a test asserts the declaration that was
 * committed and this asserts the declaration that was deployed. The two differ
 * exactly when it matters: a build that packaged an older class, a
 * hand-patched jar, a merge that dropped an entry.
 *
 * <p>Failing here is loud and total. A service that came up with a reason it
 * cannot word would serve every call correctly until the first refusal of that
 * kind, and would then answer a caller with an internal error for a rule the
 * caller broke — which is the one failure this surface's whole contract is
 * about.
 */
@ApplicationScoped
public class DeclarationGuard {

    private static final Logger LOG = Logger.getLogger(DeclarationGuard.class);

    void onStart(@Observes StartupEvent event) {
        SurfaceDeclaration.requireServable();
        LOG.infof("surface declaration served: %d calls, %d reasons",
            SurfaceDeclaration.calls().size(), SurfaceDeclaration.reasons().size());
    }
}
