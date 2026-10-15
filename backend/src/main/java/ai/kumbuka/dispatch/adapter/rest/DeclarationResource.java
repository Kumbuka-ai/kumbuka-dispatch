/*
 * Copyright (c) 2026 JBAConsult - Architekturberatung Johannes Bayer-Albert
 * SPDX-License-Identifier: AGPL-3.0-only
 * This file is part of Kumbuka and is licensed under the GNU Affero
 * General Public License v3.0 only. See the LICENSE file in the
 * repository root for the full licence text.
 */
package ai.kumbuka.dispatch.adapter.rest;

import ai.kumbuka.dispatch.surface.SurfaceDeclaration;
import io.quarkus.security.Authenticated;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/**
 * The declaration of this service, on its REST surface (ADR-0039).
 *
 * <p>The router builds its surface for this scheme from what it reads here:
 * the identity, effect class, arguments, answer shape, refusal reasons and
 * texts of every call, under a version. It holds no copy of its own.
 *
 * <p>Beside the calls rather than among them: {@code /api/declaration} is a
 * literal path, as {@code /api/whoami} is, and reads no scope. It is a
 * statement about the scheme, the same for every caller who may reach the
 * service, so it binds no tenant; a caller still authenticates, as for every
 * route of this surface. The version travels as the {@code ETag} as well, so
 * a reader can ask whether it changed without reading it.
 */
@Path("/api/declaration")
@Authenticated
public class DeclarationResource {

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response declaration() {
        Map<String, Object> declaration = SurfaceDeclaration.asMap();
        return Response.ok(declaration)
            .tag(new EntityTag(String.valueOf(declaration.get("version"))))
            .build();
    }
}
