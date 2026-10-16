/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.tenancy;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.UUID;

/**
 * The substrate's tenant resolver: one configured tenant for the whole
 * deployment.
 *
 * <p>The service authenticates against the tenant realm, so an authenticated
 * caller is by construction a caller of this deployment's tenant. Deriving a
 * per-request tenant from an organisation claim is the multi-tenant edition's
 * business and is deliberately absent here: the substrate establishes the
 * axis and its enforcement, not the directory that populates it. Everything
 * downstream — the ORM filter, the GUC, the policy — is indifferent to which
 * of the two produced the value, which is why the second can be added later
 * without touching any of them.
 */
@ApplicationScoped
public class ConfiguredTenantResolver implements TenantResolver {

    private final UUID tenantId;

    @Inject
    ConfiguredTenantResolver(@ConfigProperty(name = "dispatch.tenant-id") UUID tenantId) {
        this.tenantId = tenantId;
    }

    @Override
    public UUID currentTenant() {
        return tenantId;
    }
}
