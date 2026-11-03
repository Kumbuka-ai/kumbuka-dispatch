package ai.kumbuka.dispatch.surface;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Refuses to bring the service up on a declaration it cannot serve (C5.6).
 *
 * <p>This is the mechanism behind section 4.4's last sentence — "the service
 * refuses to start with an undeclared reason in its catalogue". A guard at
 * start-up rather than a test, because a test asserts the declaration that was
 * committed and this asserts the declaration that was deployed. The two differ
 * exactly when it matters: a build that packaged an older class, a
 * hand-patched jar, a merge that dropped an entry.
 *
 * <p><strong>The document, not the records.</strong> The guard builds the
 * document {@code GET /api/declaration} serves, by the method that route
 * calls ({@link SurfaceDeclaration#asMap()}), and checks that document:
 * distinct call names, a description for every call and every argument, the
 * description budget, and every refusal a call names declared under {@code
 * reasons}. The check over the verb records stands before it as a first
 * stage. The refusals and the catalogue are one enum here ({@link
 * RefusalCode}), so the last condition holds by the type; the check makes it
 * on the document all the same, because the document is what a router binds.
 *
 * <p>Failing here is loud and total. A service that came up with a reason it
 * cannot word would serve every call correctly until the first refusal of that
 * kind, and would then answer a caller with an internal error for a rule the
 * caller broke — which is the one failure this surface's whole contract is
 * about.
 *
 * <p>In every build: the declaration is served on the REST surface whether or
 * not the build carries the assistant surface (C5.1).
 */
@ApplicationScoped
public class DeclarationGuard {

    private static final Logger LOG = Logger.getLogger(DeclarationGuard.class);

    void onStart(@Observes StartupEvent event) {
        SurfaceDeclaration.requireServable();
        requireServable(SurfaceDeclaration.asMap());
        LOG.infof("surface declaration served: %d calls, %d reasons",
            SurfaceDeclaration.calls().size(), SurfaceDeclaration.reasons().size());
    }

    /**
     * Refuses a served document that cannot be served, naming the call.
     *
     * <p>Takes the document rather than building it, so that each refusal can
     * be observed against a document that breaks it; at start-up it is handed
     * the document the route serves.
     */
    static void requireServable(Map<String, Object> document) {
        Set<String> declared = new HashSet<>();
        for (Map<?, ?> reason : maps(document.get("reasons"))) {
            declared.add(String.valueOf(reason.get("reason")));
        }
        List<String> names = new ArrayList<>();
        for (Map<?, ?> call : maps(document.get("calls"))) {
            String name = String.valueOf(call.get("call"));
            names.add(name);
            String description = text(call.get("description"));
            SurfaceDeclaration.requireDescribed(name, description);
            SurfaceDeclaration.requireWithinBudget(name, description);
            requireDescribedArguments(name, maps(call.get("arguments")));
            for (Object refusal : list(call.get("refusals"))) {
                if (!declared.contains(String.valueOf(refusal))) {
                    throw new IllegalStateException(name + " names the refusal " + refusal
                        + ", which the declaration's reasons do not declare. A caller would "
                        + "meet a code nobody can word.");
                }
            }
        }
        SurfaceDeclaration.requireDistinctNames(names);
    }

    private static void requireDescribedArguments(String call, List<Map<?, ?>> arguments) {
        if (arguments.isEmpty()) {
            throw new IllegalStateException(call + " is served with no arguments. Its input "
                + "schema would be an empty closed object.");
        }
        for (Map<?, ?> argument : arguments) {
            if (text(argument.get("description")).isBlank()) {
                throw new IllegalStateException(call + " serves '" + argument.get("name")
                    + "' without a description. The description is what an "
                    + "ARGUMENT_MISSING refusal says the value IS.");
            }
        }
    }

    /** A member as text; an absent one is the empty text. */
    private static String text(@Nullable Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** A member as a list; an absent one, or one of another type, is the empty list. */
    private static List<?> list(@Nullable Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    /** The objects of a list member; an absent one has none. */
    private static List<Map<?, ?>> maps(@Nullable Object value) {
        List<Map<?, ?>> maps = new ArrayList<>();
        for (Object entry : list(value)) {
            if (entry instanceof Map<?, ?> map) {
                maps.add(map);
            }
        }
        return maps;
    }
}
