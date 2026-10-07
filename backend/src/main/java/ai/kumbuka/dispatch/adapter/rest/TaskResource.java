package ai.kumbuka.dispatch.adapter.rest;

import ai.kumbuka.dispatch.adapter.payload.Answers;
import ai.kumbuka.dispatch.surface.AddressParser;
import ai.kumbuka.dispatch.surface.CallRouter;
import ai.kumbuka.dispatch.surface.CallerActor;
import ai.kumbuka.dispatch.surface.ProcessVerb;
import ai.kumbuka.dispatch.surface.Refused;
import ai.kumbuka.dispatch.surface.Surface;
import ai.kumbuka.dispatch.tenancy.TenantBound;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The REST surface: the twenty-five calls of the declaration on the routes of
 * {@link RestRoute}, and nothing else.
 *
 * <p>A request becomes a call: its name from the route, its arguments from
 * the path (the scope and the bracket kind, or the complete address of one
 * task), the {@code If-Match} header (the conflict token), the query and the
 * body — the same map the assistant surface hands over, closed against the
 * same declaration by the same {@link CallRouter}. So a call refuses an
 * invented argument by name on both surfaces alike, whichever part of the
 * request it came in.
 *
 * <p>A call names calls in the vocabulary of the surface its caller came
 * through. Without the {@code Kumbuka-Surface} header that is this one. With
 * {@code Kumbuka-Surface: assistant} it is the assistant surface: a router
 * that publishes these calls as tools reaches this service over REST and says
 * so, and the call is made under its tool name, so that {@code next}, {@code
 * waiting_for}, every message and the {@code data} of every refusal name the
 * tools that caller holds. Any other value is refused and the call is not
 * made. The header is not an argument of the call — it is in no declaration
 * and never reaches {@link CallRouter}'s argument map — and it grants nothing.
 * A {@code 405} names routes, which have one spelling, so it stays in the REST
 * form whatever the header says.
 *
 * <p>This class holds the HTTP expression of a call and nothing else: the
 * route, the status, {@code ETag} with the conflict token, {@code 201} with
 * {@code Location} for a created task, {@code 405} with {@code Allow} for a
 * call that is not at the address it was sent to.
 */
@Path("/api/{scope}")
@Authenticated
@TenantBound
@Produces(MediaType.APPLICATION_JSON)
public class TaskResource {

    private static final String IF_MATCH = "If-Match";

    /**
     * Names the surface the caller came through, where that is not this one.
     * A router that publishes these calls as assistant tools and reaches this
     * service over REST says so here, so that the calls in an answer carry the
     * names the caller knows them by. It decides nothing else: who calls, and
     * what that caller may do, come from the verified identity as always.
     */
    static final String SURFACE = "Kumbuka-Surface";

    /** The one value {@link #SURFACE} takes: the assistant surface. */
    static final String ASSISTANT = "assistant";

    @Inject CallRouter router;
    @Inject CallerActor caller;
    @Inject ObjectMapper json;

    // ======================================================================
    // The six bindings: one per method and depth
    // ======================================================================

    @POST
    @Path("{selector}")
    public Response collectionPost(@PathParam("scope") String scope,
                                   @PathParam("selector") String segment,
                                   @Context HttpHeaders http,
                                   @Context UriInfo uri, String body) {
        return route("POST", new At(RestRoute.Depth.COLLECTION, scope, segment, null),
            new Headers(null, http.getRequestHeader(SURFACE)), uri, body);
    }

    @GET
    @Path("{selector}")
    public Response collectionGet(@PathParam("scope") String scope,
                                  @PathParam("selector") String segment,
                                  @Context HttpHeaders http,
                                  @Context UriInfo uri) {
        return route("GET", new At(RestRoute.Depth.COLLECTION, scope, segment, null),
            new Headers(null, http.getRequestHeader(SURFACE)), uri, null);
    }

    @GET
    @Path("{selector}/{id}")
    public Response itemGet(@PathParam("scope") String scope,
                            @PathParam("selector") String selector,
                            @PathParam("id") String segment,
                            @HeaderParam(IF_MATCH) String ifMatch,
                            @Context HttpHeaders http,
                            @Context UriInfo uri) {
        return route("GET", new At(RestRoute.Depth.ITEM, scope, selector, segment),
            new Headers(ifMatch, http.getRequestHeader(SURFACE)), uri, null);
    }

    @PATCH
    @Path("{selector}/{id}")
    public Response itemPatch(@PathParam("scope") String scope,
                              @PathParam("selector") String selector,
                              @PathParam("id") String segment,
                              @HeaderParam(IF_MATCH) String ifMatch,
                              @Context HttpHeaders http,
                              @Context UriInfo uri, String body) {
        return route("PATCH", new At(RestRoute.Depth.ITEM, scope, selector, segment),
            new Headers(ifMatch, http.getRequestHeader(SURFACE)), uri, body);
    }

    @DELETE
    @Path("{selector}/{id}")
    public Response itemDelete(@PathParam("scope") String scope,
                               @PathParam("selector") String selector,
                               @PathParam("id") String segment,
                               @HeaderParam(IF_MATCH) String ifMatch,
                               @Context HttpHeaders http,
                               @Context UriInfo uri) {
        return route("DELETE", new At(RestRoute.Depth.ITEM, scope, selector, segment),
            new Headers(ifMatch, http.getRequestHeader(SURFACE)), uri, null);
    }

    @POST
    @Path("{selector}/{id}")
    public Response itemPost(@PathParam("scope") String scope,
                             @PathParam("selector") String selector,
                             @PathParam("id") String segment,
                             @HeaderParam(IF_MATCH) String ifMatch,
                             @Context HttpHeaders http,
                             @Context UriInfo uri, String body) {
        return route("POST", new At(RestRoute.Depth.ITEM, scope, selector, segment),
            new Headers(ifMatch, http.getRequestHeader(SURFACE)), uri, body);
    }

    // ======================================================================
    // A request, as a call
    // ======================================================================

    /** Where a request is addressed: the depth and the path segments as they arrived. */
    private record At(RestRoute.Depth depth, String scope, String selectorSegment,
                      String idSegment) {
    }

    /**
     * The headers a call reads, as they arrived. The conflict token may be
     * absent; the surface is kept as every value it arrived with, an empty
     * list when there is none, because an empty value or a second one is a
     * value it cannot take, not an absence.
     */
    private record Headers(String ifMatch, List<String> surface) {
    }

    private Response route(String method, At where, Headers headers, UriInfo uri, String body) {
        RestRoute.Depth depth = where.depth();
        String scope = where.scope();
        String selectorSegment = where.selectorSegment();
        String idSegment = where.idSegment();
        String[] at = RestRoute.split(depth == RestRoute.Depth.COLLECTION
            ? selectorSegment : idSegment);
        RestRoute route = RestRoute.find(method, depth, at[1]).orElse(null);
        if (route == null) {
            return notHere(method, depth, at[1], scope, selectorSegment, at[0]);
        }
        List<String> named = headers.surface();
        if (!named.isEmpty() && !List.of(ASSISTANT).equals(named)) {
            return refusal(Refused.argumentInvalid(route.call(), SURFACE,
                String.join(", ", named), "it takes the one value " + ASSISTANT
                    + ", once, or is left out"), null);
        }
        Surface surface = named.isEmpty() ? Surface.REST : Surface.MCP;
        String call = nameOn(surface, route);

        Map<String, Object> arguments = new LinkedHashMap<>();
        if (depth == RestRoute.Depth.COLLECTION) {
            arguments.put("scope", scope);
            arguments.put("selector", at[0]);
        } else {
            arguments.put("address", AddressParser.SCHEME + "://" + scope + "/"
                + selectorSegment + "/" + at[0]);
        }
        String ifMatch = headers.ifMatch();
        if (ifMatch != null && !ifMatch.isBlank()) {
            arguments.put("conflict_token", unquote(ifMatch.trim()));
        }
        Refused collision = merge(call, arguments, query(uri), "the query");
        if (collision == null) {
            collision = mergeBody(call, arguments, body);
        }
        if (collision != null) {
            return refusal(collision, null);
        }

        CallRouter.Outcome outcome = router.call(surface, caller::current, call, arguments);
        return respond(route, outcome);
    }

    /**
     * The name a route's call goes by on a surface. Every route is a call of
     * the declaration under its REST name; {@code VerbSurfaceConformanceTest}
     * turns red on one that is not.
     */
    private static String nameOn(Surface surface, RestRoute route) {
        return ProcessVerb.byName(Surface.REST, route.call()).on(surface);
    }

    /**
     * A request at an address no route serves with that method and verb: 405
     * with {@code Allow}. Where the verb is a call of this surface at another
     * depth, the refusal says where it applies; otherwise it names the calls
     * there are, and an earlier name is answered exactly so.
     */
    private Response notHere(String method, RestRoute.Depth depth, String verb, String scope,
                             String selectorSegment, String at) {
        String attempted = verb == null ? method : verb;
        String selector = depth == RestRoute.Depth.COLLECTION ? at : selectorSegment;
        ProcessVerb declared = verb == null ? null : ProcessVerb.byName(Surface.REST, verb);
        Refused refused;
        if (declared != null) {
            String address = depth == RestRoute.Depth.COLLECTION
                ? AddressParser.SCHEME + "://" + scope + "/" + selector
                : AddressParser.SCHEME + "://" + scope + "/" + selector + "/" + at;
            refused = Refused.callNotAtThisAddress(attempted, address,
                RestRoute.of(verb)
                    .map(route -> route.method() + " /api/" + scope + "/" + selector
                        + (route.depth() == RestRoute.Depth.ITEM ? "/<number>.<sub>" : "")
                        + (route.custom() ? ":" + route.call() : ""))
                    .orElse("no route of this surface"));
        } else {
            refused = Refused.argumentUnknown(attempted, "this service",
                verb == null ? "route for " + method + " at this address" : "call", attempted,
                ProcessVerb.names(Surface.REST));
        }
        return refusal(refused, RestRoute.allow(depth));
    }

    private static Map<String, Object> query(UriInfo uri) {
        Map<String, Object> flat = new LinkedHashMap<>();
        uri.getQueryParameters().forEach((name, values) ->
            flat.put(name, String.join(",", values)));
        return flat;
    }

    /** Adds what one part of the request carries; refuses an argument given twice. */
    private static Refused merge(String call, Map<String, Object> into,
                                 Map<String, Object> from, String part) {
        for (Map.Entry<String, Object> given : from.entrySet()) {
            if (into.containsKey(given.getKey())) {
                return Refused.argumentInvalid(call, given.getKey(),
                    String.valueOf(given.getValue()), "it arrived twice: in " + part + " and "
                        + "in the path or a header, and a call takes each argument once");
            }
            into.put(given.getKey(), given.getValue());
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Refused mergeBody(String call, Map<String, Object> into, String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        Object parsed;
        try {
            parsed = json.readValue(body, Object.class);
        } catch (JsonProcessingException e) {
            return Refused.argumentInvalid(call, "body", "the body sent",
                "a body is a JSON object of the call's arguments");
        }
        if (!(parsed instanceof Map)) {
            return Refused.argumentInvalid(call, "body", String.valueOf(parsed),
                "a body is a JSON object of the call's arguments");
        }
        return merge(call, into, (Map<String, Object>) parsed, "the body");
    }

    // ======================================================================
    // An outcome, in HTTP
    // ======================================================================

    private static Response respond(RestRoute route, CallRouter.Outcome outcome) {
        if (outcome instanceof CallRouter.Refusal refusal) {
            return refusal(refusal.refused(), null);
        }
        Object body = Answers.of(outcome);
        if (outcome instanceof CallRouter.Answered answered) {
            Response.ResponseBuilder response = route == RestRoute.CREATE
                ? Response.created(location(answered)).entity(body)
                : Response.ok(body);
            if (answered.view().conflictToken() != null) {
                response.tag(new EntityTag(answered.view().conflictToken()));
            }
            return response.build();
        }
        return Response.ok(body).build();
    }

    /** Built from the address, never echoed from the request. */
    private static java.net.URI location(CallRouter.Answered created) {
        AddressParser.Parts at = AddressParser.uri(created.address());
        return UriBuilder.fromResource(TaskResource.class)
            .path("{selector}/{id}")
            .build(at.scope(), at.selector(), at.id());
    }

    private static Response refusal(Refused refused, String allow) {
        Response.ResponseBuilder response = Response
            .status(allow != null ? 405 : RefusalMapper.statusOf(refused.code()))
            .type(MediaType.APPLICATION_JSON)
            .entity(Answers.envelope(refused));
        if (allow != null) {
            response.header(HttpHeaders.ALLOW, allow);
        }
        return response.build();
    }

    /** Tolerates the quoted form an HTTP entity tag arrives in. */
    private static String unquote(String raw) {
        String value = raw.startsWith("W/") ? raw.substring(2) : raw;
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
            ? value.substring(1, value.length() - 1)
            : value;
    }
}
