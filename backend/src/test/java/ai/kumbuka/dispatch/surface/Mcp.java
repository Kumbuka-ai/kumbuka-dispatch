package ai.kumbuka.dispatch.surface;

import io.restassured.http.ContentType;
import io.restassured.response.Response;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.assertj.core.api.Assertions.assertThat;

/** Calls of the assistant surface, as an MCP client makes them. */
public final class Mcp {

    private Mcp() {
    }

    /** One {@code tools/call}, as the current test identity. */
    public static Response call(String tool, Map<String, Object> arguments) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", 1);
        envelope.put("method", "tools/call");
        envelope.put("params", Map.of("name", tool, "arguments", arguments));
        return given().contentType(ContentType.JSON).body(envelope).post("/mcp");
    }

    /** Any other JSON-RPC method. */
    public static Response rpc(String method, Map<String, Object> params) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", 1);
        envelope.put("method", method);
        envelope.put("params", params);
        return given().contentType(ContentType.JSON).body(envelope).post("/mcp");
    }

    /** The answer of a call that must have succeeded. */
    public static Map<String, Object> answer(Response response) {
        assertThat(response.jsonPath().getBoolean("result.isError"))
            .as("the call succeeds: %s", response.asString())
            .isFalse();
        return response.jsonPath().getMap("result.structuredContent");
    }

    /** The refusal envelope of a call that must have been refused. */
    public static Map<String, Object> refusal(Response response) {
        assertThat(response.jsonPath().getBoolean("result.isError"))
            .as("the call is refused: %s", response.asString())
            .isTrue();
        return response.jsonPath().getMap("result.structuredContent");
    }

    public static String reason(Response response) {
        return String.valueOf(refusal(response).get("reason"));
    }

    @SuppressWarnings("unchecked")
    public static String field(Map<String, Object> answer, String name) {
        Object value = ((Map<String, Object>) answer.get("fields")).get(name);
        return value == null ? null : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    public static List<String> nextCalls(Map<String, Object> answer) {
        return ((List<Map<String, Object>>) answer.get("next")).stream()
            .map(step -> String.valueOf(step.get("call")))
            .toList();
    }
}
