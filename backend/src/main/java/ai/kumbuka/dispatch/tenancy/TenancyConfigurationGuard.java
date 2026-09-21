package ai.kumbuka.dispatch.tenancy;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;

import java.util.Optional;
import java.util.UUID;

/**
 * Refuses to start without a tenant and a scope.
 *
 * <h2>Why these two have no default</h2>
 *
 * A guessed tenant is the one fault row-level security cannot catch. Every
 * layer below this one — the ORM filter, the session setting, the policy —
 * enforces the axis it is GIVEN; none of them can know the value it was given
 * is the wrong estate's. A default therefore does not make the service
 * tolerant of a missing setting, it makes it write somebody else's tenant
 * confidently, and the deployment reports a clean start.
 *
 * <p>The scope is the same argument one step along. Its value reaches the
 * migration set as a Flyway placeholder, and V5 declares this deployment's
 * selectors against it: a default there writes a declaration for a scope
 * nobody chose, in a database that will then carry it forever, because an
 * applied migration is not edited.
 *
 * <h2>Why the check sits in two places and is written once</h2>
 *
 * The application's start and the migration run are two entries, and the
 * migration run is the earlier and the more damaging of the two — by the time
 * a startup observer fires, V5 has already been applied. So
 * {@link TenantMigrationCallback} calls this before every migration, and the
 * observer below calls it for a boot that migrates nothing (an already-current
 * database, or a deployment with migrate-at-start switched off). One check,
 * two callers: a second copy is how the two would come to disagree about what
 * counts as set.
 *
 * <p><strong>Empty is not set.</strong> An exported-but-empty environment
 * variable is the ordinary shape of a misconfigured deployment — a compose
 * file with the value left off, a secret that resolved to nothing — and it
 * arrives as a present key with a blank value rather than as an absent one.
 * Treating it as set is how an empty string reaches a uuid cast in V5 and
 * fails there instead, with a message about SQL syntax and no mention of the
 * setting that caused it.
 */
@ApplicationScoped
public class TenancyConfigurationGuard {

    /** The tenancy axis for this deployment. Environment: {@code DISPATCH_TENANT_ID}. */
    public static final String TENANT_KEY = "dispatch.tenant-id";

    /** This deployment's own scope. Environment: {@code DISPATCH_SCOPE_ID}. */
    public static final String SCOPE_KEY = "dispatch.scope-id";

    /**
     * The boot path that migrates nothing.
     *
     * <p>Observing the startup event rather than eagerly constructing this
     * bean: the value is read here and nowhere else in this class, so an
     * injection point would be a second place the same question is asked, and
     * a lazily constructed bean would be asked at the first request rather
     * than at the start — which is a running deployment answering calls with
     * no tenant, exactly what this refuses.
     */
    void refuseToStartWithoutTheAxis(@Observes StartupEvent event) {
        requireConfigured(ConfigProvider.getConfig());
    }

    /**
     * Both settings, or a refusal naming the one that is missing.
     *
     * <p>Takes the {@link Config} rather than reaching for the ambient one, so
     * that the check can be run against a configuration a test assembles — in
     * particular against the shipped {@code application.properties} with no
     * environment behind it, which is the only way to observe that the
     * defaults are gone rather than that a test happened to set the values.
     */
    public static void requireConfigured(Config config) {
        requireUuid(config, TENANT_KEY, "DISPATCH_TENANT_ID",
            "the tenancy axis this deployment runs for. Row-level security enforces the "
                + "tenant it is given and cannot know it was given the wrong one, so a "
                + "default here would write another estate's tenant rather than fail");
        requireUuid(config, SCOPE_KEY, "DISPATCH_SCOPE_ID",
            "this deployment's own scope. It reaches V5 as a Flyway placeholder and is "
                + "written into the selector declaration, and an applied migration is "
                + "not edited afterwards");
    }

    /**
     * One setting: present, non-blank, and a uuid.
     *
     * <p>The three are one check because they have one remedy — set the
     * variable to the right value — and because the failure a deployment
     * actually produces is rarely the clean absent case.
     */
    private static void requireUuid(Config config, String key, String environmentVariable,
                                    String what) {
        Optional<String> raw = read(config, key);

        if (raw.isEmpty() || raw.get().isBlank()) {
            throw new IllegalStateException(
                ("%s is not set, and it has no default. It is %s. Set the environment "
                    + "variable %s to the uuid of %s and start again.")
                    .formatted(key, what, environmentVariable, key.equals(TENANT_KEY)
                        ? "this deployment's tenant" : "this deployment's scope"));
        }

        try {
            UUID.fromString(raw.get().trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                ("%s is set to a value that is not a uuid. It is %s, and it is read as a "
                    + "uuid by this service and written as one by V5. Set %s to a uuid.")
                    .formatted(key, what, environmentVariable));
        }
    }

    /**
     * The value, or empty — including when the value cannot be resolved at all.
     *
     * <p>A property written as an unexpanded reference to an absent variable
     * raises rather than answering empty, and the raise carries the expansion
     * machinery's wording rather than this service's. Catching it here is what
     * lets the four ways of leaving the setting out — absent, empty, blank,
     * unexpandable — arrive at the caller as one message naming the key.
     */
    private static Optional<String> read(Config config, String key) {
        try {
            return config.getOptionalValue(key, String.class);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}
