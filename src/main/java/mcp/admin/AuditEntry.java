package mcp.admin;

import com.fasterxml.jackson.databind.JsonNode;

public record AuditEntry(
        String callerIp,
        String callerNote,
        String tool,
        JsonNode argsJson,
        String resultSummary,
        JsonNode beforeJson,
        String afterSummary,
        boolean ok
) {}
