package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public interface Tool {

    String name();

    String description();

    ObjectNode inputSchema();

    JsonNode call(JsonNode args) throws ToolException;

    record Descriptor(String name, String description, ObjectNode inputSchema) {}

    default Descriptor descriptor() {
        return new Descriptor(name(), description(), inputSchema());
    }

    class ToolException extends Exception {
        private final int code;
        public ToolException(int code, String message) {
            super(message);
            this.code = code;
        }
        public int code() { return code; }
    }
}
