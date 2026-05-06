package mcp.protocol;

public record McpError(int code, String message) {

    public static final int PARSE_ERROR = -32700;
    public static final int INVALID_REQUEST = -32600;
    public static final int METHOD_NOT_FOUND = -32601;
    public static final int INVALID_PARAMS = -32602;
    public static final int INTERNAL_ERROR = -32603;
    public static final int SERVER_SHUTTING_DOWN = -32000;
    public static final int QUERY_TIMEOUT = -32001;

    public static McpError parseError(String msg)        { return new McpError(PARSE_ERROR, msg); }
    public static McpError invalidRequest(String msg)    { return new McpError(INVALID_REQUEST, msg); }
    public static McpError methodNotFound(String method) { return new McpError(METHOD_NOT_FOUND, "no such method: " + method); }
    public static McpError invalidParams(String msg)     { return new McpError(INVALID_PARAMS, msg); }
    public static McpError internal(String msg)          { return new McpError(INTERNAL_ERROR, msg); }
    public static McpError shuttingDown()                { return new McpError(SERVER_SHUTTING_DOWN, "server_shutting_down"); }
    public static McpError queryTimeout()                { return new McpError(QUERY_TIMEOUT, "query_timeout"); }
}
