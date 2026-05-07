package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.admin.AuditEntry;
import mcp.admin.AuditLog;
import mcp.admin.CommandCatalog;
import mcp.admin.RunCommandExecutor;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RunCommandToolTest {

    private static class FakeAuditLog extends AuditLog {
        final List<AuditEntry> seen = new ArrayList<>();
        public FakeAuditLog() { super(() -> { throw new RuntimeException("not used"); }); }
        @Override public long insert(AuditEntry e) { seen.add(e); return seen.size(); }
    }

    private static class StubExecutor extends RunCommandExecutor {
        boolean throwUnknown;
        StubExecutor() { super(new CommandCatalog(new java.util.LinkedHashMap<>()), name -> false); }
        @Override public Result run(String commandLine, int asGmLevel) throws RunException {
            if (throwUnknown) throw new RunException("unknown command: x");
            return new Result(true, "");
        }
    }

    @Test
    void name_isCorrect() {
        assertEquals("cosmic.admin.run_command", new RunCommandTool(new StubExecutor(), new FakeAuditLog()).name());
    }

    @Test
    void call_validCommand_returnsAuditId() throws Exception {
        StubExecutor exec = new StubExecutor();
        FakeAuditLog audit = new FakeAuditLog();
        RunCommandTool tool = new RunCommandTool(exec, audit);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("command", "@kick alice");
        JsonNode out = tool.call(args);
        assertEquals(true, out.get("ok").asBoolean());
        assertTrue(out.get("audit_id").asLong() > 0);
        assertEquals(1, audit.seen.size());
        assertEquals("cosmic.admin.run_command", audit.seen.get(0).tool());
    }

    @Test
    void call_unknownCommand_throwsInvalidParams() {
        StubExecutor exec = new StubExecutor();
        exec.throwUnknown = true;
        RunCommandTool tool = new RunCommandTool(exec, new FakeAuditLog());
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("command", "@nope");
        Tool.ToolException ex = assertThrows(Tool.ToolException.class, () -> tool.call(args));
        assertEquals(-32602, ex.code());
        assertTrue(ex.getMessage().contains("unknown"));
    }

    @Test
    void call_missingCommandArg_throws() {
        RunCommandTool tool = new RunCommandTool(new StubExecutor(), new FakeAuditLog());
        Tool.ToolException ex = assertThrows(Tool.ToolException.class,
                () -> tool.call(JsonRpc.MAPPER.createObjectNode()));
        assertEquals(-32602, ex.code());
    }
}
