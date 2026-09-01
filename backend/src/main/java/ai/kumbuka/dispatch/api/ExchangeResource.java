package ai.kumbuka.dispatch.api;

import ai.kumbuka.dispatch.api.payload.Payloads;
import ai.kumbuka.dispatch.tenancy.TenantBound;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.core.UriBuilder;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The REST exposition of the verb surface.
 *
 * <p>Thirteen verbs, each on exactly one outward form; the four the scheme
 * does not carry, each answering a typed category error; and a writing verb on
 * a truncated address answering 405 with {@code Allow}. Nothing else. A
 * conformance probe checks both halves of that — coverage and closure —
 * against a specification this class cannot edit.
 *
 * <h2>This is a front door, not an inner leg</h2>
 *
 * The community edition has no facade and addresses the services directly, so
 * this surface is the published contract of a copyleft-licensed product from
 * its first day. That is the reason for the strictness about form and closure:
 * a route added here casually is a route somebody depends on.
 *
 * <h2>The path is the address space, and the colon is forced</h2>
 *
 * The scheme is not part of the path — it is the routing decision one layer
 * out — so what this service sees is {@code {scope}/{selector}/{id}}. A
 * transition is a custom method in colon notation because the id part may be
 * multi-segment and a trailing verb segment could not be told apart from a
 * further id segment.
 *
 * <p>The colon is split off in {@link CustomMethod} rather than routed by the
 * framework, because the framework cannot: a path template takes the whole
 * segment it appears in, so eleven {@code @Path("{id}:verb")} routes would
 * collapse into one. That measurement is recorded on {@code CustomMethod}. The
 * outward form is unchanged and it is the outward form that is probed.
 *
 * <h2>Expression, not offering</h2>
 *
 * REST conventions are followed in how a verb is expressed and not in what is
 * offered: what the verb set lacks is not offered even where the convention
 * expects it. There is no DELETE on an exchange, and a transition addressed at
 * a collection answers 405 rather than being routed somewhere plausible.
 *
 * <p>The acts themselves are in {@link VerbSurface}, which the MCP exposition
 * calls too. This class holds their HTTP expression and nothing else, so an
 * omission there is an omission for both, and an addition here would be an
 * addition with no act behind it.
 */
@Path("/api/{scope}")
@Authenticated
@TenantBound
@Produces(MediaType.APPLICATION_JSON)
public class ExchangeResource {

    /** What a collection URI offers, for the {@code Allow} of a 405. */
    private static final String COLLECTION_ALLOW = "GET, POST";

    /** What an item URI offers when the segment names no verb. */
    private static final String ITEM_ALLOW = "GET, PATCH, POST";

    @Inject VerbSurface verbs;
    @Inject CallerActor caller;
    @Inject ObjectMapper json;

    // ======================================================================
    // The collection binding: create, and the refusal of a truncated write
    // ======================================================================

    /**
     * POST at collection depth.
     *
     * <p>The body arrives as text and is deserialised only once the verb is
     * known. That order is deliberate and it is what makes the 405 reachable:
     * a typed body parameter is deserialised before the method is entered, so
     * a transition wrongly addressed at a collection would answer 415 about
     * its content type instead of 405 about its address — a refusal about the
     * wrong thing entirely.
     */
    @POST
    @Path("{selector}")
    public Response collectionPost(@PathParam("scope") String scope,
                                   @PathParam("selector") String segment,
                                   String body) {
        var split = CustomMethod.split(segment, CustomMethod.Depth.COLLECTION);
        if (split.isEmpty()) {
            // No colon: a plain collection address, and create is the one
            // writing verb whose set semantics is declared as exactly one.
            return created(scope, verbs.create(caller.current(), scope, segment,
                read(body, Payloads.CreateRequest.class)));
        }

        CustomMethod.Split at = split.get();
        if (at.method() == CustomMethod.CLAIM_NEXT) {
            // The one transition admissible on a truncated address: its verb
            // contract declares set semantics, and the only declarable one is
            // exactly one.
            return claimed(verbs.claimNext(caller.current(), scope, at.address(),
                read(body, Payloads.ClaimRequest.class)));
        }

        // Every other verb acts at item depth. Depth is declared per verb and
        // undeclared means complete address only, so a range capability never
        // comes into existence by omission.
        throw new SurfaceException(SurfaceException.Reason.WRITE_ON_TRUNCATED_ADDRESS,
            "'" + at.verb() + "' acts on a complete address and this one is truncated at "
                + "the selector. The only declarable set semantics is exactly one, which "
                + "create has and no transition does.",
            COLLECTION_ALLOW);
    }

    /**
     * GET at collection depth: {@code query}.
     *
     * <p>Reading on a collection, so GET on the collection URI — the form
     * follows from the target and the effect class rather than from the verb's
     * name.
     *
     * <p>The query parameters are passed through raw and are NOT declared as
     * {@code @QueryParam}s. Declaring them here would put the list of
     * filterable fields in the adapter, which is a second place for it to be
     * decided; worse, an undeclared parameter would then be silently dropped
     * by the framework, and a silently dropped filter answers with the full
     * set and looks exactly like a correct narrow one. The domain refuses what
     * it does not carry, and refusing is only possible if it gets to see it.
     */
    @GET
    @Path("{selector}")
    public Response collectionGet(@PathParam("scope") String scope,
                                  @PathParam("selector") String selector,
                                  @Context UriInfo uri) {
        return listing(verbs.query(caller.current(), scope, selector, filtersOf(uri)));
    }

    /**
     * The query parameters, flattened to one value per name.
     *
     * <p>A repeated parameter is the first of two spellings of a disjunction,
     * and this surface carries the other one — comma-separated values. Reading
     * only the first occurrence would silently drop the rest, so a repeat is
     * joined with a comma and means what the comma form means. Two ways to
     * write one thing is a small cost; a dropped value is a wrong answer.
     */
    private static Map<String, String> filtersOf(UriInfo uri) {
        Map<String, String> flat = new LinkedHashMap<>();
        uri.getQueryParameters().forEach((name, values) ->
            flat.put(name, String.join(",", values)));
        return flat;
    }

    // ======================================================================
    // The item binding: read, update, and every custom method
    // ======================================================================

    @GET
    @Path("{selector}/{id}")
    public Response read(@PathParam("scope") String scope,
                         @PathParam("selector") String selector,
                         @PathParam("id") String id) {
        return ok(verbs.read(caller.current(), scope, selector, id));
    }

    /**
     * Replaces the handover draft. PATCH with {@code If-Match}; a stale token
     * is 412.
     */
    @PATCH
    @Path("{selector}/{id}")
    public Response update(@PathParam("scope") String scope,
                           @PathParam("selector") String selector,
                           @PathParam("id") String id,
                           @HeaderParam("If-Match") String ifMatch,
                           Payloads.UpdateRequest request) {
        return ok(verbs.update(caller.current(), scope, selector, id, ifMatch, request));
    }

    /**
     * POST at item depth: every custom method, in colon notation.
     *
     * <p>A segment with no colon is a plain item address, and POST is not
     * something an item offers — 405 with {@code Allow}, for the same reason
     * the collection refuses a transition: what the verb set lacks is not
     * offered, even where the convention expects it.
     *
     * <p>A colon naming no verb of this depth is 405 as well and not 404. The
     * address resolved; what did not exist is the verb, and a 404 would send
     * the caller looking for the object.
     */
    @POST
    @Path("{selector}/{id}")
    public Response itemPost(@PathParam("scope") String scope,
                             @PathParam("selector") String selector,
                             @PathParam("id") String segment,
                             String body) {
        CustomMethod.Split at = CustomMethod.split(segment, CustomMethod.Depth.ITEM)
            .filter(CustomMethod.Split::isKnown)
            .orElseThrow(() -> new SurfaceException(
                SurfaceException.Reason.WRITE_ON_TRUNCATED_ADDRESS,
                "'" + segment + "' names no verb of this scheme at item depth. A transition "
                    + "is written in colon notation on the item URI — '<id>:send' — because "
                    + "a verb as a trailing path segment could not be told apart from a "
                    + "further id segment.",
                ITEM_ALLOW));

        return dispatch(at.method(), scope, selector, at.address(), body);
    }

    /**
     * One custom method onto one act.
     *
     * <p>A {@code switch} over the enum with no default: a verb added to the
     * table without a case here is a compile error, which is the only way the
     * table and the routing cannot drift apart.
     */
    private Response dispatch(CustomMethod method, String scope, String selector,
                              String id, String body) {
        var actor = caller.current();

        return switch (method) {
            case SEND -> ok(verbs.send(actor, scope, selector, id,
                read(body, Payloads.SendRequest.class)));
            case ACCEPT -> ok(verbs.accept(actor, scope, selector, id));
            case CLAIM -> claimed(verbs.claim(actor, scope, selector, id,
                read(body, Payloads.ClaimRequest.class)));
            case RELEASE -> ok(verbs.release(actor, scope, selector, id));
            case ABANDON -> ok(verbs.abandon(actor, scope, selector, id));
            case BLOCK -> ok(verbs.block(actor, scope, selector, id));
            case RESUME -> ok(verbs.resume(actor, scope, selector, id));
            case CLOSE -> ok(verbs.close(actor, scope, selector, id));
            case CONSUME -> ok(verbs.consume(actor, scope, selector, id));

            case WITHDRAW -> {
                verbs.withdraw(actor, scope, selector, id);
                throw unreachable(method.verb());
            }
            case VALIDATE -> {
                verbs.validate(actor, scope, selector, id);
                throw unreachable(method.verb());
            }
            case CLAIM_NEXT -> throw new IllegalStateException(
                "claim_next acts at collection depth and cannot arrive here");
        };
    }

    // ======================================================================
    // The sub-collections
    // ======================================================================

    /** Adds a child to an open bracket. POST to the bracket's sub-collection. */
    @POST
    @Path("{selector}/{id}/children")
    public Response createChild(@PathParam("scope") String scope,
                                @PathParam("selector") String selector,
                                @PathParam("id") String id,
                                Payloads.CreateRequest request) {
        return created(scope, verbs.createChild(caller.current(), scope, selector, id, request));
    }

    /**
     * Attaches an addendum to a frozen exchange. Additive and not removable
     * afterwards, which is why it is not an update.
     */
    @POST
    @Path("{selector}/{id}/addenda")
    public Response append(@PathParam("scope") String scope,
                           @PathParam("selector") String selector,
                           @PathParam("id") String id,
                           Payloads.AppendRequest request) {
        return created(scope, verbs.append(caller.current(), scope, selector, id, request));
    }

    // ======================================================================
    // Dressing a result in HTTP
    // ======================================================================

    private static Response ok(VerbSurface.Result result) {
        return tagged(Response.ok(result.exchange()), result);
    }

    /**
     * A listing, with no entity tag.
     *
     * <p>Deliberately untagged: the conflict token is a per-exchange value and
     * a listing has no single one. A tag over the set would be a token callers
     * could send back on a field write, which is a token about the wrong
     * thing.
     */
    private static Response listing(VerbSurface.Listing found) {
        return Response.ok(found).build();
    }

    private static Response claimed(VerbSurface.ClaimOutcome outcome) {
        return tagged(Response.ok(new Payloads.ClaimResponse(
            outcome.result().exchange(), outcome.receipt())), outcome.result());
    }

    /**
     * 201 with {@code Location}, built from the address rather than echoed
     * from the request.
     *
     * <p>The canonical form is generated here and what arrived is never passed
     * through, so a tolerated trailing slash does not survive into a header
     * other clients will treat as an identity.
     */
    private static Response created(String scope, VerbSurface.Result result) {
        return tagged(Response
            .created(UriBuilder.fromResource(ExchangeResource.class)
                .path("{selector}/{id}")
                .build(scope, result.address().selector(),
                    AddressParser.render(result.address())))
            .entity(result.exchange()), result);
    }

    private static Response tagged(Response.ResponseBuilder response, VerbSurface.Result result) {
        if (result.conflictToken() != null) {
            response.tag(new EntityTag(result.conflictToken()));
        }
        return response.build();
    }

    // ======================================================================
    // The body
    // ======================================================================

    /**
     * Deserialises the request body, after the verb has been decided.
     *
     * <p>An absent body is null rather than an error: some verbs take one and
     * some do not, and which is which is the verb's business rather than the
     * transport's. The verb refuses a missing body where it needs one, with a
     * message that says what was missing.
     */
    private <T> T read(String body, Class<T> shape) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return json.readValue(body, shape);
        } catch (JsonProcessingException e) {
            throw new SurfaceException(SurfaceException.Reason.PAYLOAD_MALFORMED,
                "the request body is not the JSON this verb takes: " + e.getOriginalMessage());
        }
    }

    /**
     * The uncarried verbs always throw. This exists so the compiler is not
     * told a lie about a value that cannot be produced — a {@code return null}
     * on that line would be a value sitting where a later edit could make it
     * real.
     */
    private static IllegalStateException unreachable(String verb) {
        return new IllegalStateException(
            "'" + verb + "' is not carried and its refusal did not throw");
    }
}
