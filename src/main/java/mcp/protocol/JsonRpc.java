package mcp.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

public final class JsonRpc {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonRpc() {}

    public record Request(
            @JsonProperty("jsonrpc") String jsonrpc,
            @JsonProperty("id") JsonNode id,
            @JsonProperty("method") String method,
            @JsonProperty("params") JsonNode params
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Response(
            @JsonProperty("jsonrpc") String jsonrpc,
            @JsonProperty("id") JsonNode id,
            @JsonProperty("result") JsonNode result,
            @JsonProperty("error") McpError error
    ) {}

    public static Request parseRequest(String body) throws ParseException {
        try {
            Request r = MAPPER.readValue(body, Request.class);
            if (r.method == null || r.method.isBlank()) {
                throw new ParseException("missing method");
            }
            return r;
        } catch (JsonProcessingException e) {
            throw new ParseException("invalid JSON: " + e.getOriginalMessage());
        }
    }

    public static Response result(JsonNode id, JsonNode result) {
        return new Response("2.0", id, result, null);
    }

    public static Response error(JsonNode id, McpError error) {
        return new Response("2.0", id, null, error);
    }

    public static class ParseException extends Exception {
        public ParseException(String msg) { super(msg); }
    }
}
