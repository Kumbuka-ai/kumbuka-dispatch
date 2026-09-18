package ai.kumbuka.dispatch.adapter.rest;

import jakarta.enterprise.context.RequestScoped;

/**
 * What the current REST request is calling, so a refusal can name it.
 *
 * <p>Section 4.2 requires that a refusal "names the call the caller made, under
 * that name". On MCP the name is the tool name and travels in the body, where
 * the code raising the refusal can see it. On REST the name is in the path's
 * colon segment, or it is implied by the method — and a JAX-RS exception mapper
 * sees neither: it is handed an exception and nothing about the request that
 * produced it.
 *
 * <p>So the resource records it here on the way in. A request-scoped bean
 * rather than a thread-local, because the container owns the lifecycle and a
 * thread-local on a reactive stack is a value that survives into the next
 * request or is missing from this one.
 *
 * <p><strong>The fallback is deliberate.</strong> An unset scope answers the
 * HTTP method rather than nothing: a refusal raised before the resource method
 * was entered — a malformed body, a framework-level 405 — has no verb yet, and
 * "POST is not possible on ..." is a true sentence a caller can act on where
 * "null is not possible" is not.
 */
@RequestScoped
public class CallScope {

    private String call;
    private String address;

    /** Records the verb this request is making, and the address it acts on. */
    public void calling(String call, String address) {
        this.call = call;
        this.address = address;
    }

    /** The verb, or the fallback the caller can still act on. */
    public String call(String fallback) {
        return call == null || call.isBlank() ? fallback : call;
    }

    /** The complete address this request acts on, or null at collection depth. */
    public String address() {
        return address;
    }
}
