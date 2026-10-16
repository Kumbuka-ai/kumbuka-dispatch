/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.surface;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.interceptor.Interceptor;
import org.eclipse.microprofile.config.Config;
import org.jboss.logging.Logger;
import org.eclipse.microprofile.config.ConfigProvider;

import java.util.Optional;

/**
 * Refuses to start when the acting identity would not be the token's subject,
 * or when the audience is not checked at all.
 *
 * <h2>The defect this blocks, measured elsewhere first</h2>
 *
 * Quarkus resolves a service application's principal through
 * {@code upn -> preferred_username -> sub} unless it is pinned. In this
 * service the principal is the subject of every actor: it is recorded as who
 * claimed, delivered and accepted a task, it is what a receipt and an
 * idempotency key are bound to, and it is what tells the holder of a task from
 * every other executor. An unpinned principal makes those an email address on
 * one deployment and a subject on the next, and a holder who stops being
 * recognised as the holder. Both fail quietly: the write succeeds, and the
 * next call of the same caller is refused as somebody else's.
 *
 * <p>The memory service and the core carry the same guard for the same
 * reason, under the same name (C2.4, C15.2).
 *
 * <h2>Why the audience is checked here too</h2>
 *
 * An unset {@code quarkus.oidc.token.audience} does not fail; it means the
 * audience is not validated. A deployment that lost the value would accept a
 * token minted for anything at all, with a clean start and no error — which is
 * the same failure shape as the principal claim and belongs in the same guard.
 * ADR-0034 gives the platform one audience and makes every recipient validate
 * it; what the value IS is a deployment's to configure, and what this asserts
 * is that there is one.
 *
 * <h2>The escape hatch, and why it is the tenant flag</h2>
 *
 * A disabled bearer tenant validates no token, so there is no principal to pin
 * and no audience to check; asserting about one would be asserting about a
 * surface that is not there. The default is {@code true}, so silence never
 * disarms the guard — only an explicit statement that authentication is off
 * does, which is what a test that stands up no realm says.
 */
@ApplicationScoped
public class IdentityConfigurationGuard {

    private static final Logger LOG = Logger.getLogger(IdentityConfigurationGuard.class);

    /** The claim the acting identity must come from. */
    public static final String EXPECTED_PRINCIPAL_CLAIM = "sub";

    static final String PRINCIPAL_CLAIM_KEY = "quarkus.oidc.token.principal-claim";
    static final String AUDIENCE_KEY = "quarkus.oidc.token.audience";
    static final String TENANT_ENABLED_KEY = "quarkus.oidc.tenant-enabled";

    /**
     * Runs as early as an observer of the startup event can, so that the
     * refusal arrives before anything else the boot does can look like the
     * cause.
     */
    void refuseToStartOnAWrongIdentity(
            @Observes @Priority(Interceptor.Priority.PLATFORM_BEFORE) StartupEvent event) {
        verify(ConfigProvider.getConfig());
    }

    /**
     * The two settings, or a refusal naming the one that is wrong.
     *
     * <p>Takes the {@link Config} so that a test can run it against an
     * assembled configuration rather than only against the one it booted with
     * — which is what lets the red state be observed without a second
     * application.
     */
    public static void verify(Config config) {
        if (!config.getOptionalValue(TENANT_ENABLED_KEY, Boolean.class).orElse(true)) {
            return;
        }

        String claim = config.getOptionalValue(PRINCIPAL_CLAIM_KEY, String.class)
            .orElse("");
        if (!EXPECTED_PRINCIPAL_CLAIM.equals(claim)) {
            throw refused(
                "'" + PRINCIPAL_CLAIM_KEY + "' is '" + claim + "' and must be '"
                    + EXPECTED_PRINCIPAL_CLAIM + "'. The principal is the subject of "
                    + "every actor of this service, and what a holder is recognised by; "
                    + "resolved from any other claim it becomes an identifier that can "
                    + "change, and a holder that changes is refused its own task.");
        }

        Optional<String> audience = config.getOptionalValue(AUDIENCE_KEY, String.class);
        if (audience.isEmpty() || audience.get().isBlank()) {
            throw refused(
                "'" + AUDIENCE_KEY + "' is not set, so no audience would be validated and "
                    + "a token minted for any other resource would be accepted here. "
                    + "ADR-0034 gives the platform one audience and has every recipient "
                    + "validate it against the identifier its own installation "
                    + "configures. Set DISPATCH_OIDC_AUDIENCE.");
        }
    }

    /**
     * Logs a refusal as one line, then answers the exception that stops the
     * start. The log format carries no exception (C13.1, C13.2), so the reason
     * reaches the log only through this line; WARN, as a configuration anomaly
     * is (C13.3).
     */
    private static IllegalStateException refused(String why) {
        LOG.warnf("start refused: %s", why);
        return new IllegalStateException(why);
    }
}
