package ai.kumbuka.dispatch.adapter.payload;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * The wire shapes this service puts on the wire as records.
 *
 * <p>One is left. The requests of both surfaces arrive as the arguments of a
 * call — MCP's tool arguments, REST's path, header, query and body — and are
 * closed against the declaration as one map, so neither surface has a request
 * record of its own. The answers are built by {@link Answers}.
 */
public final class Payloads {

    private Payloads() {
    }

    /**
     * A refusal on either surface: the reason a caller matches on, the message
     * that explains it, and the data it acts on. {@code data} is absent, not
     * empty, on the one refusal that must say nothing.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RefusalEnvelope(String reason, String message, Map<String, Object> data) {
    }
}
