# Cosmic MCP Slice 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an in-process MCP HTTP server to Cosmic that exposes 13 read-only research tools (item / mob / map / npc / quest / skill describe, drop & name search, script & code search, db schema & SELECT, config inspect) over LAN with bearer-token auth.

**Architecture:** New `mcp/` Java package inside Cosmic, started at the end of `Server.init()`. Hand-rolled JSON-RPC 2.0 over Netty HTTP (no Spring, no MCP SDK). Tools delegate to existing Cosmic providers (`ItemInformationProvider`, `LifeFactory`, etc.) and `DatabaseConnection`. Two narrow auxiliary indices (`DropIndex`, `NameIndex`) built once at startup.

**Tech Stack:** Java 21, Maven, Netty 4.2 (already present), Jackson (new), JSqlParser (new), JUnit 5, Mockito, Testcontainers MySQL (test scope, new). No Spring.

**Spec:** `docs/superpowers/specs/2026-05-07-cosmic-mcp-design.md`.

---

## File Structure

```
src/main/java/mcp/
  McpServer.java                    Boot + shutdown, owns Netty channel
  config/McpConfig.java             Typed view of the `mcp:` block in config.yaml
  transport/HttpJsonRpcHandler.java Netty handler: reads HTTP body, dispatches, writes response
  transport/AuthFilter.java         Bearer token check, constant-time compare
  protocol/JsonRpc.java             Request/response/error record types + Jackson codec
  protocol/McpError.java            JSON-RPC error code constants + factory
  protocol/McpDispatcher.java       Routes initialize / tools/list / tools/call
  protocol/ToolRegistry.java        Holds Tool instances by name
  tools/Tool.java                   Tool interface (name, schema, call)
  tools/ItemTool.java               1 cosmic.item.describe
  tools/MobTool.java                2 cosmic.mob.describe
  tools/MapTool.java                3 cosmic.map.describe
  tools/NpcTool.java                4 cosmic.npc.describe
  tools/QuestTool.java              5 cosmic.quest.describe
  tools/SkillTool.java              6 cosmic.skill.describe
  tools/DropSearchTool.java         7 cosmic.drop.search
  tools/NameSearchTool.java         8 cosmic.name.search
  tools/ScriptFinderTool.java       9 cosmic.script.find
  tools/JavaCodeSearchTool.java     10 cosmic.code.search
  tools/SchemaTool.java             11 cosmic.db.schema
  tools/SqlSelectTool.java          12 cosmic.db.select
  tools/ConfigInspectTool.java      13 cosmic.config.get
  data/DropIndex.java               Forward + reverse drop index, built once
  data/NameIndex.java               String.wz name index, built once
  data/SqlSafety.java               JSqlParser AST checks + denylist + caps

src/test/java/mcp/
  ... mirror structure with *Test.java files

config.yaml                          + new `mcp:` section
pom.xml                              + jackson-databind, jsqlparser, netty-codec-http, testcontainers (test)
src/main/java/net/server/Server.java + start McpServer at end of init(); stop on shutdown
```

---

## Task 1: pom.xml dependencies and config skeleton

**Files:**
- Modify: `pom.xml` — add Jackson, JSqlParser, netty-codec-http, testcontainers (test)
- Modify: `config.yaml` — add `mcp:` block (disabled by default)
- Create: `src/main/java/mcp/config/McpConfig.java`
- Create: `src/test/java/mcp/config/McpConfigTest.java`

- [ ] **Step 1: Add dependencies to pom.xml**

In `pom.xml`, add a property near the existing version properties (around line 71):

```xml
<jackson.version>2.18.2</jackson.version>
<jsqlparser.version>5.0</jsqlparser.version>
<testcontainers.version>1.20.4</testcontainers.version>
```

Add the netty-codec-http dependency in the Networking block (after `netty-handler`, around line 128):

```xml
<dependency>
    <groupId>io.netty</groupId>
    <artifactId>netty-codec-http</artifactId>
    <version>${netty.version}</version>
</dependency>
```

Add a new MCP section before `<!-- Testing -->` (around line 163):

```xml
<!-- MCP -->
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>${jackson.version}</version>
</dependency>
<dependency>
    <groupId>com.github.jsqlparser</groupId>
    <artifactId>jsqlparser</artifactId>
    <version>${jsqlparser.version}</version>
</dependency>
```

Add testcontainers in the Testing block (after `mockito-junit-jupiter`):

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>mysql</artifactId>
    <version>${testcontainers.version}</version>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>${testcontainers.version}</version>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 2: Verify build still passes**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS, no errors.

- [ ] **Step 3: Add `mcp:` block to config.yaml**

Append to the end of `config.yaml`:

```yaml
mcp:
  enabled: false
  bind_addr: "127.0.0.1"
  port: 8765
  auth_token: ""
  tls_cert: ""
  tls_key: ""
  sql_enabled: true
  sql_timeout_seconds: 5
  sql_row_cap: 1000
  sql_pii_denylist:
    - "account.password"
    - "account.pin"
    - "account.pic"
    - "account.email"
    - "account.tos"
  request_log: true
```

Note: YamlBeans uses flat field names (no nested objects with non-trivial mapping), so we keep `tls_cert`/`tls_key`/`sql_*` as flat fields rather than nested.

- [ ] **Step 4: Add `mcp` field to YamlConfig**

Edit `src/main/java/config/YamlConfig.java`. After the existing fields (`server`, `worlds`), add:

```java
public McpConfigYaml mcp;
```

Create `src/main/java/config/McpConfigYaml.java`:

```java
package config;

import java.util.List;

public class McpConfigYaml {
    public boolean enabled;
    public String bind_addr;
    public int port;
    public String auth_token;
    public String tls_cert;
    public String tls_key;
    public boolean sql_enabled;
    public int sql_timeout_seconds;
    public int sql_row_cap;
    public List<String> sql_pii_denylist;
    public boolean request_log;
}
```

- [ ] **Step 5: Write the failing test for McpConfig validation**

Create `src/test/java/mcp/config/McpConfigTest.java`:

```java
package mcp.config;

import config.McpConfigYaml;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpConfigTest {

    @Test
    void from_disabled_returnsDisabled() {
        McpConfigYaml y = new McpConfigYaml();
        y.enabled = false;
        McpConfig c = McpConfig.from(y);
        assertEquals(false, c.enabled());
    }

    @Test
    void from_enabledWithoutToken_throws() {
        McpConfigYaml y = baseEnabled();
        y.auth_token = "";
        assertThrows(IllegalArgumentException.class, () -> McpConfig.from(y));
    }

    @Test
    void from_enabledWithShortToken_throws() {
        McpConfigYaml y = baseEnabled();
        y.auth_token = "tooshort";
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> McpConfig.from(y));
        assertTrue(ex.getMessage().toLowerCase().contains("token"));
    }

    @Test
    void from_enabledValid_returnsConfig() {
        McpConfigYaml y = baseEnabled();
        McpConfig c = McpConfig.from(y);
        assertEquals("192.168.1.42", c.bindAddr());
        assertEquals(8765, c.port());
        assertEquals(5, c.sqlTimeoutSeconds());
        assertEquals(1000, c.sqlRowCap());
        assertEquals(List.of("account.password"), c.sqlPiiDenylist());
    }

    private McpConfigYaml baseEnabled() {
        McpConfigYaml y = new McpConfigYaml();
        y.enabled = true;
        y.bind_addr = "192.168.1.42";
        y.port = 8765;
        y.auth_token = "01234567890123456789abcd";
        y.sql_enabled = true;
        y.sql_timeout_seconds = 5;
        y.sql_row_cap = 1000;
        y.sql_pii_denylist = List.of("account.password");
        y.request_log = true;
        return y;
    }
}
```

- [ ] **Step 6: Run test to verify it fails**

Run: `./mvnw test -Dtest=McpConfigTest -q`
Expected: COMPILATION FAILURE — `McpConfig` does not exist.

- [ ] **Step 7: Implement McpConfig**

Create `src/main/java/mcp/config/McpConfig.java`:

```java
package mcp.config;

import config.McpConfigYaml;

import java.util.List;

public record McpConfig(
        boolean enabled,
        String bindAddr,
        int port,
        String authToken,
        String tlsCert,
        String tlsKey,
        boolean sqlEnabled,
        int sqlTimeoutSeconds,
        int sqlRowCap,
        List<String> sqlPiiDenylist,
        boolean requestLog
) {

    private static final int MIN_TOKEN_LENGTH = 16;

    public static McpConfig from(McpConfigYaml y) {
        if (!y.enabled) {
            return new McpConfig(false, "", 0, "", "", "", false, 0, 0, List.of(), false);
        }
        if (y.auth_token == null || y.auth_token.length() < MIN_TOKEN_LENGTH) {
            throw new IllegalArgumentException(
                    "mcp.auth_token must be at least " + MIN_TOKEN_LENGTH + " characters when mcp.enabled=true");
        }
        return new McpConfig(
                true,
                y.bind_addr == null ? "127.0.0.1" : y.bind_addr,
                y.port == 0 ? 8765 : y.port,
                y.auth_token,
                y.tls_cert == null ? "" : y.tls_cert,
                y.tls_key == null ? "" : y.tls_key,
                y.sql_enabled,
                y.sql_timeout_seconds == 0 ? 5 : y.sql_timeout_seconds,
                y.sql_row_cap == 0 ? 1000 : y.sql_row_cap,
                y.sql_pii_denylist == null ? List.of() : List.copyOf(y.sql_pii_denylist),
                y.request_log
        );
    }

    public boolean tlsEnabled() {
        return !tlsCert.isEmpty() && !tlsKey.isEmpty();
    }
}
```

- [ ] **Step 8: Run test to verify it passes**

Run: `./mvnw test -Dtest=McpConfigTest -q`
Expected: 4 tests, all PASS.

- [ ] **Step 9: Commit**

```bash
git add pom.xml config.yaml src/main/java/config/YamlConfig.java src/main/java/config/McpConfigYaml.java src/main/java/mcp/config/McpConfig.java src/test/java/mcp/config/McpConfigTest.java
git commit -m "Add MCP config skeleton and dependencies #minor"
```

---

## Task 2: JSON-RPC types and error codes

**Files:**
- Create: `src/main/java/mcp/protocol/JsonRpc.java`
- Create: `src/main/java/mcp/protocol/McpError.java`
- Create: `src/test/java/mcp/protocol/JsonRpcTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/mcp/protocol/JsonRpcTest.java`:

```java
package mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JsonRpcTest {

    private final ObjectMapper mapper = JsonRpc.MAPPER;

    @Test
    void parseRequest_validToolsCall() throws Exception {
        String json = """
                {"jsonrpc":"2.0","id":7,"method":"tools/call","params":{"name":"x","arguments":{"a":1}}}""";
        JsonRpc.Request req = JsonRpc.parseRequest(json);
        assertEquals("2.0", req.jsonrpc());
        assertEquals(7, req.id().asInt());
        assertEquals("tools/call", req.method());
        assertEquals(1, req.params().get("arguments").get("a").asInt());
    }

    @Test
    void parseRequest_missingMethod_throws() {
        String json = """
                {"jsonrpc":"2.0","id":1}""";
        try {
            JsonRpc.parseRequest(json);
            throw new AssertionError("expected exception");
        } catch (JsonRpc.ParseException e) {
            // expected
        }
    }

    @Test
    void result_serializesCorrectly() throws Exception {
        JsonRpc.Response resp = JsonRpc.result(mapper.readTree("3"), mapper.valueToTree(42));
        String json = mapper.writeValueAsString(resp);
        JsonNode parsed = mapper.readTree(json);
        assertEquals("2.0", parsed.get("jsonrpc").asText());
        assertEquals(3, parsed.get("id").asInt());
        assertEquals(42, parsed.get("result").asInt());
        assertNull(parsed.get("error"));
    }

    @Test
    void error_serializesCorrectly() throws Exception {
        JsonRpc.Response resp = JsonRpc.error(mapper.readTree("3"), McpError.invalidParams("bad"));
        String json = mapper.writeValueAsString(resp);
        JsonNode parsed = mapper.readTree(json);
        assertEquals(-32602, parsed.get("error").get("code").asInt());
        assertEquals("bad", parsed.get("error").get("message").asText());
        assertNull(parsed.get("result"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./mvnw test -Dtest=JsonRpcTest -q`
Expected: COMPILATION FAILURE.

- [ ] **Step 3: Implement McpError**

Create `src/main/java/mcp/protocol/McpError.java`:

```java
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
```

- [ ] **Step 4: Implement JsonRpc**

Create `src/main/java/mcp/protocol/JsonRpc.java`:

```java
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
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./mvnw test -Dtest=JsonRpcTest -q`
Expected: 4 tests PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/mcp/protocol/JsonRpc.java src/main/java/mcp/protocol/McpError.java src/test/java/mcp/protocol/JsonRpcTest.java
git commit -m "Add JSON-RPC types and MCP error codes #minor"
```

---

## Task 3: Tool interface and ToolRegistry

**Files:**
- Create: `src/main/java/mcp/tools/Tool.java`
- Create: `src/main/java/mcp/protocol/ToolRegistry.java`
- Create: `src/test/java/mcp/protocol/ToolRegistryTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/mcp/protocol/ToolRegistryTest.java`:

```java
package mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.tools.Tool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRegistryTest {

    @Test
    void register_andLookup() {
        Tool fake = fakeTool("a.b");
        ToolRegistry reg = new ToolRegistry(List.of(fake));
        Optional<Tool> got = reg.find("a.b");
        assertTrue(got.isPresent());
        assertEquals("a.b", got.get().name());
    }

    @Test
    void list_returnsAllToolDescriptors() {
        ToolRegistry reg = new ToolRegistry(List.of(fakeTool("a.b"), fakeTool("c.d")));
        List<Tool.Descriptor> list = reg.list();
        assertEquals(2, list.size());
        assertEquals("a.b", list.get(0).name());
    }

    @Test
    void duplicateName_throws() {
        try {
            new ToolRegistry(List.of(fakeTool("a.b"), fakeTool("a.b")));
            throw new AssertionError("expected exception");
        } catch (IllegalArgumentException e) {
            // expected
        }
    }

    private Tool fakeTool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return "desc"; }
            @Override public ObjectNode inputSchema() { return JsonRpc.MAPPER.createObjectNode(); }
            @Override public JsonNode call(JsonNode args) { return JsonRpc.MAPPER.createObjectNode(); }
        };
    }
}
```

- [ ] **Step 2: Implement Tool interface**

Create `src/main/java/mcp/tools/Tool.java`:

```java
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
```

- [ ] **Step 3: Implement ToolRegistry**

Create `src/main/java/mcp/protocol/ToolRegistry.java`:

```java
package mcp.protocol;

import mcp.tools.Tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class ToolRegistry {

    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public ToolRegistry(List<Tool> tools) {
        for (Tool t : tools) {
            if (this.tools.put(t.name(), t) != null) {
                throw new IllegalArgumentException("duplicate tool name: " + t.name());
            }
        }
    }

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public List<Tool.Descriptor> list() {
        List<Tool.Descriptor> out = new ArrayList<>(tools.size());
        for (Tool t : tools.values()) {
            out.add(t.descriptor());
        }
        return out;
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./mvnw test -Dtest=ToolRegistryTest -q`
Expected: 3 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/mcp/tools/Tool.java src/main/java/mcp/protocol/ToolRegistry.java src/test/java/mcp/protocol/ToolRegistryTest.java
git commit -m "Add MCP Tool interface and ToolRegistry #minor"
```

---

## Task 4: Dispatcher (initialize, tools/list, tools/call routing)

**Files:**
- Create: `src/main/java/mcp/protocol/McpDispatcher.java`
- Create: `src/test/java/mcp/protocol/McpDispatcherTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/mcp/protocol/McpDispatcherTest.java`:

```java
package mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.tools.Tool;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class McpDispatcherTest {

    @Test
    void initialize_returnsServerInfo() throws Exception {
        McpDispatcher d = new McpDispatcher(new ToolRegistry(List.of()));
        JsonRpc.Request req = JsonRpc.parseRequest("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}""");
        JsonRpc.Response resp = d.dispatch(req);
        assertNull(resp.error());
        assertEquals("cosmic-mcp", resp.result().get("serverInfo").get("name").asText());
    }

    @Test
    void toolsList_returnsRegistry() throws Exception {
        Tool fake = fakeTool("a.b");
        McpDispatcher d = new McpDispatcher(new ToolRegistry(List.of(fake)));
        JsonRpc.Request req = JsonRpc.parseRequest("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list"}""");
        JsonRpc.Response resp = d.dispatch(req);
        assertEquals(1, resp.result().get("tools").size());
        assertEquals("a.b", resp.result().get("tools").get(0).get("name").asText());
    }

    @Test
    void toolsCall_unknownName_returnsMethodNotFound() throws Exception {
        McpDispatcher d = new McpDispatcher(new ToolRegistry(List.of()));
        JsonRpc.Request req = JsonRpc.parseRequest("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"missing","arguments":{}}}""");
        JsonRpc.Response resp = d.dispatch(req);
        assertNotNull(resp.error());
        assertEquals(McpError.METHOD_NOT_FOUND, resp.error().code());
    }

    @Test
    void toolsCall_knownName_returnsResult() throws Exception {
        Tool echo = new Tool() {
            @Override public String name() { return "echo"; }
            @Override public String description() { return ""; }
            @Override public ObjectNode inputSchema() { return JsonRpc.MAPPER.createObjectNode(); }
            @Override public JsonNode call(JsonNode args) { return args; }
        };
        McpDispatcher d = new McpDispatcher(new ToolRegistry(List.of(echo)));
        JsonRpc.Request req = JsonRpc.parseRequest("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"echo","arguments":{"x":42}}}""");
        JsonRpc.Response resp = d.dispatch(req);
        assertNull(resp.error());
        assertEquals(42, resp.result().get("content").get(0).get("json").get("x").asInt());
    }

    @Test
    void unknownMethod_returnsMethodNotFound() throws Exception {
        McpDispatcher d = new McpDispatcher(new ToolRegistry(List.of()));
        JsonRpc.Request req = JsonRpc.parseRequest("""
                {"jsonrpc":"2.0","id":1,"method":"frobnicate"}""");
        JsonRpc.Response resp = d.dispatch(req);
        assertEquals(McpError.METHOD_NOT_FOUND, resp.error().code());
    }

    private Tool fakeTool(String name) {
        return new Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return ""; }
            @Override public ObjectNode inputSchema() { return JsonRpc.MAPPER.createObjectNode(); }
            @Override public JsonNode call(JsonNode args) { return JsonRpc.MAPPER.createObjectNode(); }
        };
    }
}
```

- [ ] **Step 2: Implement McpDispatcher**

Create `src/main/java/mcp/protocol/McpDispatcher.java`:

```java
package mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.tools.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

public class McpDispatcher {

    private static final Logger log = LoggerFactory.getLogger(McpDispatcher.class);
    private static final String SERVER_NAME = "cosmic-mcp";
    private static final String SERVER_VERSION = "1.0.0";
    private static final String PROTOCOL_VERSION = "2024-11-05";

    private final ToolRegistry registry;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public McpDispatcher(ToolRegistry registry) {
        this.registry = registry;
    }

    public void shutdown() {
        running.set(false);
    }

    public JsonRpc.Response dispatch(JsonRpc.Request req) {
        if (!running.get()) {
            return JsonRpc.error(req.id(), McpError.shuttingDown());
        }
        try {
            return switch (req.method()) {
                case "initialize" -> JsonRpc.result(req.id(), buildInitializeResult());
                case "tools/list" -> JsonRpc.result(req.id(), buildToolsList());
                case "tools/call" -> dispatchToolCall(req);
                default -> JsonRpc.error(req.id(), McpError.methodNotFound(req.method()));
            };
        } catch (Tool.ToolException e) {
            return JsonRpc.error(req.id(), new McpError(e.code(), e.getMessage()));
        } catch (Exception e) {
            log.warn("dispatcher internal error for method={}", req.method(), e);
            return JsonRpc.error(req.id(), McpError.internal("internal error"));
        }
    }

    private ObjectNode buildInitializeResult() {
        ObjectNode result = JsonRpc.MAPPER.createObjectNode();
        result.put("protocolVersion", PROTOCOL_VERSION);
        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", SERVER_NAME);
        serverInfo.put("version", SERVER_VERSION);
        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools");
        return result;
    }

    private ObjectNode buildToolsList() {
        ObjectNode result = JsonRpc.MAPPER.createObjectNode();
        ArrayNode arr = result.putArray("tools");
        for (Tool.Descriptor d : registry.list()) {
            ObjectNode n = arr.addObject();
            n.put("name", d.name());
            n.put("description", d.description());
            n.set("inputSchema", d.inputSchema());
        }
        return result;
    }

    private JsonRpc.Response dispatchToolCall(JsonRpc.Request req) throws Tool.ToolException {
        JsonNode params = req.params();
        if (params == null || !params.has("name")) {
            return JsonRpc.error(req.id(), McpError.invalidParams("missing tools/call name"));
        }
        String name = params.get("name").asText();
        Optional<Tool> tool = registry.find(name);
        if (tool.isEmpty()) {
            return JsonRpc.error(req.id(), McpError.methodNotFound(name));
        }
        JsonNode args = params.has("arguments") ? params.get("arguments") : JsonRpc.MAPPER.createObjectNode();
        JsonNode toolResult = tool.get().call(args);

        ObjectNode result = JsonRpc.MAPPER.createObjectNode();
        ArrayNode content = result.putArray("content");
        ObjectNode item = content.addObject();
        item.put("type", "json");
        item.set("json", toolResult);
        return JsonRpc.result(req.id(), result);
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./mvnw test -Dtest=McpDispatcherTest -q`
Expected: 5 tests PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/mcp/protocol/McpDispatcher.java src/test/java/mcp/protocol/McpDispatcherTest.java
git commit -m "Add MCP dispatcher with initialize/tools routing #minor"
```

---

## Task 5: AuthFilter (bearer token, constant-time compare)

**Files:**
- Create: `src/main/java/mcp/transport/AuthFilter.java`
- Create: `src/test/java/mcp/transport/AuthFilterTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/mcp/transport/AuthFilterTest.java`:

```java
package mcp.transport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AuthFilterTest {

    private final AuthFilter filter = new AuthFilter("01234567890123456789abcd");

    @Test
    void check_missingHeader_returnsUnauthorized() {
        assertEquals(AuthFilter.Result.UNAUTHORIZED, filter.check(null));
    }

    @Test
    void check_wrongScheme_returnsUnauthorized() {
        assertEquals(AuthFilter.Result.UNAUTHORIZED, filter.check("Basic abcdef"));
    }

    @Test
    void check_wrongToken_returnsUnauthorized() {
        assertEquals(AuthFilter.Result.UNAUTHORIZED, filter.check("Bearer wrong-token-but-long-enough"));
    }

    @Test
    void check_correctToken_returnsOk() {
        assertEquals(AuthFilter.Result.OK, filter.check("Bearer 01234567890123456789abcd"));
    }

    @Test
    void check_correctTokenWithExtraWhitespace_returnsOk() {
        assertEquals(AuthFilter.Result.OK, filter.check("Bearer  01234567890123456789abcd"));
    }
}
```

- [ ] **Step 2: Implement AuthFilter**

Create `src/main/java/mcp/transport/AuthFilter.java`:

```java
package mcp.transport;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class AuthFilter {

    public enum Result { OK, UNAUTHORIZED }

    private final byte[] expectedToken;

    public AuthFilter(String expectedToken) {
        this.expectedToken = expectedToken.getBytes(StandardCharsets.UTF_8);
    }

    public Result check(String authorizationHeader) {
        if (authorizationHeader == null) return Result.UNAUTHORIZED;
        String trimmed = authorizationHeader.trim();
        if (!trimmed.regionMatches(true, 0, "Bearer ", 0, 7)) return Result.UNAUTHORIZED;
        String token = trimmed.substring(7).trim();
        byte[] got = token.getBytes(StandardCharsets.UTF_8);
        if (got.length != expectedToken.length) {
            // run isEqual anyway against a same-length buffer to keep timing flat
            MessageDigest.isEqual(expectedToken, expectedToken);
            return Result.UNAUTHORIZED;
        }
        return MessageDigest.isEqual(got, expectedToken) ? Result.OK : Result.UNAUTHORIZED;
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./mvnw test -Dtest=AuthFilterTest -q`
Expected: 5 tests PASS.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/mcp/transport/AuthFilter.java src/test/java/mcp/transport/AuthFilterTest.java
git commit -m "Add MCP AuthFilter with constant-time token compare #minor"
```

---

## Task 6: HttpJsonRpcHandler (Netty handler binding everything together)

**Files:**
- Create: `src/main/java/mcp/transport/HttpJsonRpcHandler.java`

- [ ] **Step 1: Implement the Netty handler**

Create `src/main/java/mcp/transport/HttpJsonRpcHandler.java`:

```java
package mcp.transport;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpDispatcher;
import mcp.protocol.McpError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

public class HttpJsonRpcHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(HttpJsonRpcHandler.class);
    private static final String ENDPOINT = "/mcp";

    private final AuthFilter auth;
    private final McpDispatcher dispatcher;
    private final boolean requestLog;

    public HttpJsonRpcHandler(AuthFilter auth, McpDispatcher dispatcher, boolean requestLog) {
        this.auth = auth;
        this.dispatcher = dispatcher;
        this.requestLog = requestLog;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest req) {
        Instant t0 = Instant.now();
        if (!ENDPOINT.equals(req.uri())) {
            send(ctx, HttpResponseStatus.NOT_FOUND, "{}");
            return;
        }
        if (req.method() != HttpMethod.POST) {
            send(ctx, HttpResponseStatus.METHOD_NOT_ALLOWED, "{}");
            return;
        }
        String authHeader = req.headers().get(HttpHeaderNames.AUTHORIZATION);
        if (auth.check(authHeader) != AuthFilter.Result.OK) {
            send(ctx, HttpResponseStatus.UNAUTHORIZED, "{}");
            return;
        }
        String body = req.content().toString(StandardCharsets.UTF_8);

        JsonRpc.Response resp;
        String methodName = "?";
        try {
            JsonRpc.Request rpc = JsonRpc.parseRequest(body);
            methodName = rpc.method();
            resp = dispatcher.dispatch(rpc);
        } catch (JsonRpc.ParseException e) {
            resp = JsonRpc.error(JsonRpc.MAPPER.nullNode(), McpError.parseError(e.getMessage()));
        }

        String json;
        try {
            json = JsonRpc.MAPPER.writeValueAsString(resp);
        } catch (Exception e) {
            log.warn("failed to serialize response", e);
            json = "{\"jsonrpc\":\"2.0\",\"error\":{\"code\":-32603,\"message\":\"serialization error\"}}";
        }
        send(ctx, HttpResponseStatus.OK, json);

        if (requestLog) {
            long ms = Duration.between(t0, Instant.now()).toMillis();
            String caller = ctx.channel().remoteAddress() == null ? "?" : ctx.channel().remoteAddress().toString();
            boolean ok = resp.error() == null;
            log.info("mcp method={} caller={} dur_ms={} ok={}", methodName, caller, ms, ok);
        }
    }

    private static void send(ChannelHandlerContext ctx, HttpResponseStatus status, String body) {
        FullHttpResponse resp = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, status,
                Unpooled.copiedBuffer(body, StandardCharsets.UTF_8));
        resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
        resp.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, resp.content().readableBytes());
        resp.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
        ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("mcp handler exception", cause);
        ctx.close();
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/mcp/transport/HttpJsonRpcHandler.java
git commit -m "Add MCP Netty HTTP/JSON-RPC handler #minor"
```

---

## Task 7: McpServer (Netty bootstrap + lifecycle)

**Files:**
- Create: `src/main/java/mcp/McpServer.java`

- [ ] **Step 1: Implement McpServer**

Create `src/main/java/mcp/McpServer.java`:

```java
package mcp;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import mcp.config.McpConfig;
import mcp.protocol.McpDispatcher;
import mcp.protocol.ToolRegistry;
import mcp.transport.AuthFilter;
import mcp.transport.HttpJsonRpcHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public class McpServer {

    private static final Logger log = LoggerFactory.getLogger(McpServer.class);
    private static final int MAX_BODY_BYTES = 1024 * 1024;

    private final McpConfig config;
    private final ToolRegistry registry;
    private final McpDispatcher dispatcher;

    private EventLoopGroup boss;
    private EventLoopGroup worker;
    private Channel channel;

    public McpServer(McpConfig config, ToolRegistry registry) {
        this.config = config;
        this.registry = registry;
        this.dispatcher = new McpDispatcher(registry);
    }

    public void start() throws Exception {
        if (!config.enabled()) {
            log.info("mcp disabled, skipping start");
            return;
        }
        AuthFilter auth = new AuthFilter(config.authToken());
        SslContext sslCtx = config.tlsEnabled()
                ? SslContextBuilder.forServer(new File(config.tlsCert()), new File(config.tlsKey())).build()
                : null;
        boss = new NioEventLoopGroup(1);
        worker = new NioEventLoopGroup();
        ServerBootstrap b = new ServerBootstrap();
        b.group(boss, worker)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        if (sslCtx != null) {
                            ch.pipeline().addLast(sslCtx.newHandler(ch.alloc()));
                        }
                        ch.pipeline().addLast(new HttpServerCodec());
                        ch.pipeline().addLast(new HttpObjectAggregator(MAX_BODY_BYTES));
                        ch.pipeline().addLast(new HttpJsonRpcHandler(auth, dispatcher, config.requestLog()));
                    }
                });
        channel = b.bind(config.bindAddr(), config.port()).sync().channel();
        log.info("mcp listening on {}:{} (tls={})", config.bindAddr(), config.port(), config.tlsEnabled());
    }

    public void stop() {
        if (channel == null) return;
        log.info("mcp stopping");
        dispatcher.shutdown();
        try {
            channel.close().sync();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        worker.shutdownGracefully();
        boss.shutdownGracefully();
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/mcp/McpServer.java
git commit -m "Add McpServer Netty bootstrap and lifecycle #minor"
```

---

## Task 8: Wire McpServer into Cosmic's Server lifecycle

**Files:**
- Modify: `src/main/java/net/server/Server.java`

- [ ] **Step 1: Add McpServer field and start at end of init()**

Edit `src/main/java/net/server/Server.java`. Find the existing `import` block and add:

```java
import mcp.McpServer;
import mcp.config.McpConfig;
import mcp.protocol.ToolRegistry;
```

Add a private field near other fields (search for `private LoginServer loginServer`):

```java
private McpServer mcpServer;
```

In `init()` (around line 945, right after `OpcodeConstants.generateOpcodeNames();` and `CommandsExecutor.getInstance();`), add:

```java
try {
    McpConfig mcpConfig = McpConfig.from(YamlConfig.config.mcp);
    if (mcpConfig.enabled()) {
        mcpServer = new McpServer(mcpConfig, new ToolRegistry(java.util.List.of()));
        mcpServer.start();
    }
} catch (Exception e) {
    log.warn("Failed to start MCP server (game server continuing)", e);
}
```

(The empty tool list is intentional — tools are added in later tasks. The MCP boots and answers `tools/list` with `[]`.)

- [ ] **Step 2: Add stop call in shutdown path**

Find the `shutdown` method (search for `public Runnable shutdown(`). Inside the returned `Runnable.run()`, near the top of the shutdown sequence, add:

```java
if (mcpServer != null) {
    mcpServer.stop();
}
```

- [ ] **Step 3: Verify compile**

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Manual smoke test**

In a separate terminal, set `mcp.enabled: true` and `mcp.auth_token: "01234567890123456789abcd"` in `config.yaml`, set `mcp.bind_addr: "127.0.0.1"`, and start the server. From another terminal:

```bash
curl -s -X POST http://127.0.0.1:8765/mcp \
    -H 'Authorization: Bearer 01234567890123456789abcd' \
    -H 'Content-Type: application/json' \
    -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```
Expected: `{"jsonrpc":"2.0","id":1,"result":{"tools":[]}}`.

Without auth:
```bash
curl -i -X POST http://127.0.0.1:8765/mcp -d '{}'
```
Expected: HTTP/1.1 401.

Revert `config.yaml` `mcp.enabled` back to `false` after testing.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/net/server/Server.java
git commit -m "Wire MCP server into Cosmic Server lifecycle #minor"
```

---

## Task 9: SkillTool (`cosmic.skill.describe`) — first end-to-end tool

**Why first:** Skills are the simplest WZ entity (no cross-references), so this proves the pattern before we add joins.

**Files:**
- Create: `src/main/java/mcp/tools/SkillTool.java`
- Create: `src/test/java/mcp/tools/SkillToolTest.java`
- Modify: `src/main/java/net/server/Server.java` — add `new SkillTool()` to the registry

- [ ] **Step 1: Write the failing test**

Create `src/test/java/mcp/tools/SkillToolTest.java`:

```java
package mcp.tools;

import client.SkillFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class SkillToolTest {

    @Test
    void call_unknownId_throwsInvalidParams() {
        SkillTool tool = new SkillTool();
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("id", 9999999);

        try (MockedStatic<SkillFactory> mocked = mockStatic(SkillFactory.class)) {
            when(SkillFactory.getSkill(9999999)).thenReturn(java.util.Optional.empty());
            Tool.ToolException ex = assertThrows(Tool.ToolException.class, () -> tool.call(args));
            assertEquals(-32602, ex.code());
            assertTrue(ex.getMessage().contains("9999999"));
        }
    }

    @Test
    void call_missingId_throwsInvalidParams() {
        SkillTool tool = new SkillTool();
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        Tool.ToolException ex = assertThrows(Tool.ToolException.class, () -> tool.call(args));
        assertEquals(-32602, ex.code());
    }

    @Test
    void name_isCorrect() {
        assertEquals("cosmic.skill.describe", new SkillTool().name());
    }

    @Test
    void inputSchema_includesIdProperty() {
        JsonNode schema = new SkillTool().inputSchema();
        assertEquals("object", schema.get("type").asText());
        assertTrue(schema.get("properties").has("id"));
        assertTrue(schema.get("required").toString().contains("id"));
    }
}
```

- [ ] **Step 2: Implement SkillTool**

Create `src/main/java/mcp/tools/SkillTool.java`:

```java
package mcp.tools;

import client.SkillFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;

public class SkillTool implements Tool {

    @Override
    public String name() { return "cosmic.skill.describe"; }

    @Override
    public String description() { return "Describe a Cosmic skill by ID (job, max level, summary)."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        ObjectNode id = props.putObject("id");
        id.put("type", "integer");
        id.put("description", "Skill ID (e.g. 1121006).");
        root.putArray("required").add("id");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) throws ToolException {
        if (!args.has("id") || !args.get("id").isInt()) {
            throw new ToolException(McpError.INVALID_PARAMS, "missing or non-integer 'id'");
        }
        int id = args.get("id").asInt();
        var skillOpt = SkillFactory.getSkill(id);
        if (skillOpt.isEmpty()) {
            throw new ToolException(McpError.INVALID_PARAMS, "no such skill: " + id);
        }
        var skill = skillOpt.get();

        ObjectNode out = JsonRpc.MAPPER.createObjectNode();
        out.put("id", id);
        out.put("name", skill.getName());
        out.put("maxLevel", skill.getMaxLevel());
        out.put("element", String.valueOf(skill.getElement()));
        return out;
    }
}
```

(Adjust getter names if `Skill`'s public API differs — verify by opening `src/main/java/client/Skill.java` and `src/main/java/client/SkillFactory.java`.)

- [ ] **Step 3: Run tests**

Run: `./mvnw test -Dtest=SkillToolTest -q`
Expected: 4 tests PASS.

- [ ] **Step 4: Add SkillTool to the registry in Server.java**

In `Server.init()` where the registry is built, change:

```java
mcpServer = new McpServer(mcpConfig, new ToolRegistry(java.util.List.of()));
```

To:

```java
mcpServer = new McpServer(mcpConfig, new ToolRegistry(java.util.List.of(
        new mcp.tools.SkillTool()
)));
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/mcp/tools/SkillTool.java src/test/java/mcp/tools/SkillToolTest.java src/main/java/net/server/Server.java
git commit -m "Add cosmic.skill.describe MCP tool #minor"
```

---

## Task 10: ItemTool (`cosmic.item.describe`)

**Files:**
- Create: `src/main/java/mcp/tools/ItemTool.java`
- Create: `src/test/java/mcp/tools/ItemToolTest.java`
- Modify: `Server.java` registry

- [ ] **Step 1: Write the failing test**

Create `src/test/java/mcp/tools/ItemToolTest.java`:

```java
package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.ItemInformationProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class ItemToolTest {

    @Test
    void call_known_returnsDescription() throws Exception {
        ItemInformationProvider iip = mock(ItemInformationProvider.class);
        when(iip.getName(1002357)).thenReturn("Beginner's Glasses");
        when(iip.getSlotMax(null, 1002357)).thenReturn(1);
        when(iip.getPrice(1002357)).thenReturn(100);

        try (MockedStatic<ItemInformationProvider> mocked = mockStatic(ItemInformationProvider.class)) {
            mocked.when(ItemInformationProvider::getInstance).thenReturn(iip);
            ItemTool tool = new ItemTool();
            ObjectNode args = JsonRpc.MAPPER.createObjectNode();
            args.put("id", 1002357);
            JsonNode out = tool.call(args);
            assertEquals(1002357, out.get("id").asInt());
            assertEquals("Beginner's Glasses", out.get("name").asText());
            assertEquals(100, out.get("sellPrice").asInt());
        }
    }

    @Test
    void call_unknown_throwsInvalidParams() {
        ItemInformationProvider iip = mock(ItemInformationProvider.class);
        when(iip.getName(9999999)).thenReturn(null);
        try (MockedStatic<ItemInformationProvider> mocked = mockStatic(ItemInformationProvider.class)) {
            mocked.when(ItemInformationProvider::getInstance).thenReturn(iip);
            ItemTool tool = new ItemTool();
            ObjectNode args = JsonRpc.MAPPER.createObjectNode();
            args.put("id", 9999999);
            Tool.ToolException ex = assertThrows(Tool.ToolException.class, () -> tool.call(args));
            assertEquals(-32602, ex.code());
        }
    }

    @Test
    void name_isCorrect() {
        assertEquals("cosmic.item.describe", new ItemTool().name());
    }
}
```

- [ ] **Step 2: Implement ItemTool**

Create `src/main/java/mcp/tools/ItemTool.java`:

```java
package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;
import server.ItemInformationProvider;

public class ItemTool implements Tool {

    @Override
    public String name() { return "cosmic.item.describe"; }

    @Override
    public String description() { return "Describe a Cosmic item by ID (name, category, sell price, slot max)."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        root.putObject("properties").putObject("id").put("type", "integer").put("description", "Item ID (e.g. 1002357).");
        root.putArray("required").add("id");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) throws ToolException {
        if (!args.has("id") || !args.get("id").isInt()) {
            throw new ToolException(McpError.INVALID_PARAMS, "missing or non-integer 'id'");
        }
        int id = args.get("id").asInt();
        ItemInformationProvider iip = ItemInformationProvider.getInstance();
        String name = iip.getName(id);
        if (name == null) {
            throw new ToolException(McpError.INVALID_PARAMS, "no such item: " + id);
        }
        ObjectNode out = JsonRpc.MAPPER.createObjectNode();
        out.put("id", id);
        out.put("name", name);
        out.put("category", id / 1_000_000);
        out.put("sellPrice", iip.getPrice(id));
        out.put("slotMax", iip.getSlotMax(null, id));
        return out;
    }
}
```

(Verify the `ItemInformationProvider` getter names by opening `src/main/java/server/ItemInformationProvider.java`. Adjust `getSlotMax`'s argument or replace with `getSlotMaxFromCache(id)` or similar if the signature differs.)

- [ ] **Step 3: Run tests**

Run: `./mvnw test -Dtest=ItemToolTest -q`
Expected: 3 tests PASS.

- [ ] **Step 4: Register tool in Server.java**

Update the `List.of(...)` block in `Server.init()` to include `new mcp.tools.ItemTool()`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/mcp/tools/ItemTool.java src/test/java/mcp/tools/ItemToolTest.java src/main/java/net/server/Server.java
git commit -m "Add cosmic.item.describe MCP tool #minor"
```

---

## Task 11: MobTool (`cosmic.mob.describe`)

**Files:**
- Create: `src/main/java/mcp/tools/MobTool.java`
- Create: `src/test/java/mcp/tools/MobToolTest.java`
- Modify: `Server.java` registry

- [ ] **Step 1: Write the failing test**

Create `src/test/java/mcp/tools/MobToolTest.java`:

```java
package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.life.LifeFactory;
import server.life.MonsterStats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class MobToolTest {

    @Test
    void call_known_returnsDescription() throws Exception {
        MonsterStats stats = mock(MonsterStats.class);
        when(stats.getLevel()).thenReturn(7);
        when(stats.getHp()).thenReturn(100);
        when(stats.getMp()).thenReturn(0);
        when(stats.getExp()).thenReturn(15);
        when(stats.isBoss()).thenReturn(false);

        try (MockedStatic<LifeFactory> mocked = mockStatic(LifeFactory.class)) {
            mocked.when(() -> LifeFactory.getMonsterStats(100100)).thenReturn(stats);
            MobTool tool = new MobTool();
            ObjectNode args = JsonRpc.MAPPER.createObjectNode();
            args.put("id", 100100);
            JsonNode out = tool.call(args);
            assertEquals(100100, out.get("id").asInt());
            assertEquals(7, out.get("level").asInt());
            assertEquals(100, out.get("hp").asInt());
            assertEquals(15, out.get("exp").asInt());
            assertEquals(false, out.get("boss").asBoolean());
        }
    }

    @Test
    void call_unknown_throwsInvalidParams() {
        try (MockedStatic<LifeFactory> mocked = mockStatic(LifeFactory.class)) {
            mocked.when(() -> LifeFactory.getMonsterStats(9999999)).thenReturn(null);
            MobTool tool = new MobTool();
            ObjectNode args = JsonRpc.MAPPER.createObjectNode();
            args.put("id", 9999999);
            assertThrows(Tool.ToolException.class, () -> tool.call(args));
        }
    }

    @Test
    void name_isCorrect() {
        assertEquals("cosmic.mob.describe", new MobTool().name());
    }
}
```

- [ ] **Step 2: Implement MobTool**

Create `src/main/java/mcp/tools/MobTool.java`:

```java
package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;
import server.life.LifeFactory;
import server.life.MonsterStats;

public class MobTool implements Tool {

    @Override
    public String name() { return "cosmic.mob.describe"; }

    @Override
    public String description() { return "Describe a Cosmic monster by ID (level, HP/MP, EXP, boss flag)."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        root.putObject("properties").putObject("id").put("type", "integer").put("description", "Mob ID (e.g. 100100).");
        root.putArray("required").add("id");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) throws ToolException {
        if (!args.has("id") || !args.get("id").isInt()) {
            throw new ToolException(McpError.INVALID_PARAMS, "missing or non-integer 'id'");
        }
        int id = args.get("id").asInt();
        MonsterStats stats = LifeFactory.getMonsterStats(id);
        if (stats == null) {
            throw new ToolException(McpError.INVALID_PARAMS, "no such mob: " + id);
        }
        ObjectNode out = JsonRpc.MAPPER.createObjectNode();
        out.put("id", id);
        out.put("level", stats.getLevel());
        out.put("hp", stats.getHp());
        out.put("mp", stats.getMp());
        out.put("exp", stats.getExp());
        out.put("boss", stats.isBoss());
        return out;
    }
}
```

(Verify the `LifeFactory.getMonsterStats(int)` signature in `src/main/java/server/life/LifeFactory.java`. Adjust the method/return type if different.)

- [ ] **Step 3: Run tests**

Run: `./mvnw test -Dtest=MobToolTest -q`
Expected: 3 tests PASS.

- [ ] **Step 4: Register tool in Server.java**

Add `new mcp.tools.MobTool()` to the `List.of(...)`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/mcp/tools/MobTool.java src/test/java/mcp/tools/MobToolTest.java src/main/java/net/server/Server.java
git commit -m "Add cosmic.mob.describe MCP tool #minor"
```

---

## Task 12: MapTool, NpcTool, QuestTool

These three follow the same pattern as Tasks 10–11 but read from `MapleMapFactory`, WZ Npc.wz / scripts/npc, and WZ Quest.wz respectively.

**Files:**
- Create: `src/main/java/mcp/tools/MapTool.java` and test
- Create: `src/main/java/mcp/tools/NpcTool.java` and test
- Create: `src/main/java/mcp/tools/QuestTool.java` and test
- Modify: `Server.java` registry

- [ ] **Step 1: MapTool — write the failing test**

Create `src/test/java/mcp/tools/MapToolTest.java`:

```java
package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MapToolTest {

    @Test
    void call_missingId_throwsInvalidParams() {
        MapTool tool = new MapTool();
        Tool.ToolException ex = assertThrows(Tool.ToolException.class,
                () -> tool.call(JsonRpc.MAPPER.createObjectNode()));
        assertEquals(-32602, ex.code());
    }

    @Test
    void name_isCorrect() {
        assertEquals("cosmic.map.describe", new MapTool().name());
    }

    @Test
    void inputSchema_requiresId() {
        JsonNode schema = new MapTool().inputSchema();
        assertEquals("object", schema.get("type").asText());
    }

    // Integration coverage of the happy path is part of the manual checklist —
    // MapleMapFactory is heavyweight and instantiating it in unit tests is not
    // a productive use of effort.
}
```

- [ ] **Step 2: Implement MapTool**

Create `src/main/java/mcp/tools/MapTool.java`:

```java
package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;
import net.server.Server;
import net.server.world.World;
import server.maps.MapleMap;
import server.maps.Portal;

public class MapTool implements Tool {

    @Override
    public String name() { return "cosmic.map.describe"; }

    @Override
    public String description() { return "Describe a Cosmic map by ID (name, NPCs, portals, returnMap)."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        root.putObject("properties").putObject("id").put("type", "integer").put("description", "Map ID (e.g. 100000000).");
        root.putArray("required").add("id");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) throws ToolException {
        if (!args.has("id") || !args.get("id").isInt()) {
            throw new ToolException(McpError.INVALID_PARAMS, "missing or non-integer 'id'");
        }
        int id = args.get("id").asInt();
        // Use world 0, channel 1 to retrieve a representative MapleMap; map data is shared.
        World world = Server.getInstance().getWorld(0);
        if (world == null) {
            throw new ToolException(McpError.INTERNAL_ERROR, "no worlds initialized");
        }
        MapleMap map = world.getChannel(1).getMapFactory().getMap(id);
        if (map == null) {
            throw new ToolException(McpError.INVALID_PARAMS, "no such map: " + id);
        }

        ObjectNode out = JsonRpc.MAPPER.createObjectNode();
        out.put("id", id);
        out.put("name", map.getMapName());
        out.put("returnMap", map.getReturnMapId());
        ArrayNode portals = out.putArray("portals");
        for (Portal p : map.getPortals()) {
            ObjectNode po = portals.addObject();
            po.put("name", p.getName());
            po.put("targetMap", p.getTargetMapId());
        }
        return out;
    }
}
```

(Open `src/main/java/server/maps/MapleMap.java` to confirm the available getters; adjust if `getMapName()`, `getReturnMapId()`, or `getPortals()` are named differently.)

- [ ] **Step 3: Run MapTool tests**

Run: `./mvnw test -Dtest=MapToolTest -q`
Expected: 3 tests PASS.

- [ ] **Step 4: NpcTool — write the failing test**

Create `src/test/java/mcp/tools/NpcToolTest.java`:

```java
package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NpcToolTest {

    @TempDir
    Path scriptsRoot;

    @Test
    void call_findsAttachedScript() throws Exception {
        Path npcDir = Files.createDirectories(scriptsRoot.resolve("npc"));
        Files.writeString(npcDir.resolve("9201000.js"), "// dummy");
        NpcTool tool = new NpcTool(scriptsRoot, _id -> "Mr Smith");

        var args = JsonRpc.MAPPER.createObjectNode();
        args.put("id", 9201000);
        JsonNode out = tool.call(args);
        assertEquals("Mr Smith", out.get("name").asText());
        assertTrue(out.get("scriptPath").asText().endsWith("9201000.js"));
    }

    @Test
    void call_unknownNpc_throws() {
        NpcTool tool = new NpcTool(scriptsRoot, _id -> null);
        var args = JsonRpc.MAPPER.createObjectNode();
        args.put("id", 9999999);
        Tool.ToolException ex = assertThrows(Tool.ToolException.class, () -> tool.call(args));
        assertEquals(-32602, ex.code());
    }
}
```

- [ ] **Step 5: Implement NpcTool**

Create `src/main/java/mcp/tools/NpcTool.java`:

```java
package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;
import server.life.LifeFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.IntFunction;

public class NpcTool implements Tool {

    private final Path scriptsRoot;
    private final IntFunction<String> nameLookup;

    public NpcTool() {
        this(Path.of("scripts"), id -> {
            try { return LifeFactory.getNPC(id).getName(); }
            catch (Exception e) { return null; }
        });
    }

    NpcTool(Path scriptsRoot, IntFunction<String> nameLookup) {
        this.scriptsRoot = scriptsRoot;
        this.nameLookup = nameLookup;
    }

    @Override
    public String name() { return "cosmic.npc.describe"; }

    @Override
    public String description() { return "Describe a Cosmic NPC by ID (name + attached script if any)."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        root.putObject("properties").putObject("id").put("type", "integer").put("description", "NPC ID (e.g. 9201000).");
        root.putArray("required").add("id");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) throws ToolException {
        if (!args.has("id") || !args.get("id").isInt()) {
            throw new ToolException(McpError.INVALID_PARAMS, "missing or non-integer 'id'");
        }
        int id = args.get("id").asInt();
        String name = nameLookup.apply(id);
        if (name == null) {
            throw new ToolException(McpError.INVALID_PARAMS, "no such NPC: " + id);
        }
        ObjectNode out = JsonRpc.MAPPER.createObjectNode();
        out.put("id", id);
        out.put("name", name);
        Path script = scriptsRoot.resolve("npc").resolve(id + ".js");
        if (Files.exists(script)) {
            out.put("scriptPath", script.toString());
        } else {
            out.putNull("scriptPath");
        }
        return out;
    }
}
```

(Confirm `LifeFactory.getNPC(int).getName()` exists; otherwise replace with the WZ-based equivalent.)

- [ ] **Step 6: Run NpcTool tests**

Run: `./mvnw test -Dtest=NpcToolTest -q`
Expected: 2 tests PASS.

- [ ] **Step 7: QuestTool — write the failing test**

Create `src/test/java/mcp/tools/QuestToolTest.java`:

```java
package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestToolTest {

    @TempDir
    Path scriptsRoot;

    @Test
    void call_findsScriptIfPresent() throws Exception {
        Path questDir = Files.createDirectories(scriptsRoot.resolve("quest"));
        Files.writeString(questDir.resolve("21010.js"), "// dummy");
        QuestTool tool = new QuestTool(scriptsRoot, id -> "Test Quest");
        var args = JsonRpc.MAPPER.createObjectNode();
        args.put("id", 21010);
        JsonNode out = tool.call(args);
        assertEquals("Test Quest", out.get("name").asText());
        assertTrue(out.get("scriptPath").asText().endsWith("21010.js"));
    }
}
```

- [ ] **Step 8: Implement QuestTool**

Create `src/main/java/mcp/tools/QuestTool.java`:

```java
package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;
import server.quest.Quest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.IntFunction;

public class QuestTool implements Tool {

    private final Path scriptsRoot;
    private final IntFunction<String> nameLookup;

    public QuestTool() {
        this(Path.of("scripts"), id -> {
            try { return Quest.getInstance(id).getName(); }
            catch (Exception e) { return null; }
        });
    }

    QuestTool(Path scriptsRoot, IntFunction<String> nameLookup) {
        this.scriptsRoot = scriptsRoot;
        this.nameLookup = nameLookup;
    }

    @Override
    public String name() { return "cosmic.quest.describe"; }

    @Override
    public String description() { return "Describe a Cosmic quest by ID (name + attached script)."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        root.putObject("properties").putObject("id").put("type", "integer").put("description", "Quest ID (e.g. 21010).");
        root.putArray("required").add("id");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) throws ToolException {
        if (!args.has("id") || !args.get("id").isInt()) {
            throw new ToolException(McpError.INVALID_PARAMS, "missing or non-integer 'id'");
        }
        int id = args.get("id").asInt();
        String name = nameLookup.apply(id);
        if (name == null) {
            throw new ToolException(McpError.INVALID_PARAMS, "no such quest: " + id);
        }
        ObjectNode out = JsonRpc.MAPPER.createObjectNode();
        out.put("id", id);
        out.put("name", name);
        Path script = scriptsRoot.resolve("quest").resolve(id + ".js");
        if (Files.exists(script)) {
            out.put("scriptPath", script.toString());
        } else {
            out.putNull("scriptPath");
        }
        return out;
    }
}
```

(Confirm `Quest.getInstance(int).getName()` against `src/main/java/server/quest/Quest.java`.)

- [ ] **Step 9: Run all new tool tests**

Run: `./mvnw test -Dtest='MapToolTest,NpcToolTest,QuestToolTest' -q`
Expected: all PASS.

- [ ] **Step 10: Register all three in Server.java**

Add `new mcp.tools.MapTool()`, `new mcp.tools.NpcTool()`, `new mcp.tools.QuestTool()` to the `List.of(...)`.

- [ ] **Step 11: Commit**

```bash
git add src/main/java/mcp/tools/MapTool.java src/main/java/mcp/tools/NpcTool.java src/main/java/mcp/tools/QuestTool.java src/test/java/mcp/tools/MapToolTest.java src/test/java/mcp/tools/NpcToolTest.java src/test/java/mcp/tools/QuestToolTest.java src/main/java/net/server/Server.java
git commit -m "Add cosmic.map/npc/quest.describe MCP tools #minor"
```

---

## Task 13: NameIndex + NameSearchTool (`cosmic.name.search`)

**Files:**
- Create: `src/main/java/mcp/data/NameIndex.java`
- Create: `src/main/java/mcp/tools/NameSearchTool.java`
- Create: `src/test/java/mcp/data/NameIndexTest.java`
- Create: `src/test/java/mcp/tools/NameSearchToolTest.java`

- [ ] **Step 1: Write the failing test for NameIndex**

Create `src/test/java/mcp/data/NameIndexTest.java`:

```java
package mcp.data;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NameIndexTest {

    @Test
    void search_prefixMatchesRankFirst() {
        NameIndex idx = new NameIndex();
        idx.add(NameIndex.Kind.ITEM, 1, "Maple Sword");
        idx.add(NameIndex.Kind.ITEM, 2, "Sword of Maple");
        idx.add(NameIndex.Kind.ITEM, 3, "Generic Sword");
        List<NameIndex.Hit> hits = idx.search("maple", null, 10);
        assertEquals(2, hits.size());
        assertEquals(1, hits.get(0).id()); // prefix wins
    }

    @Test
    void search_filterByKind() {
        NameIndex idx = new NameIndex();
        idx.add(NameIndex.Kind.ITEM, 1, "Foo Bar");
        idx.add(NameIndex.Kind.MOB, 2, "Foo Bar");
        List<NameIndex.Hit> hits = idx.search("foo", NameIndex.Kind.MOB, 10);
        assertEquals(1, hits.size());
        assertEquals(NameIndex.Kind.MOB, hits.get(0).kind());
    }

    @Test
    void search_respectsLimit() {
        NameIndex idx = new NameIndex();
        for (int i = 0; i < 50; i++) idx.add(NameIndex.Kind.ITEM, i, "item " + i);
        List<NameIndex.Hit> hits = idx.search("item", null, 5);
        assertEquals(5, hits.size());
    }

    @Test
    void search_isCaseInsensitive() {
        NameIndex idx = new NameIndex();
        idx.add(NameIndex.Kind.ITEM, 1, "Maple Sword");
        assertTrue(idx.search("MAPLE", null, 10).size() == 1);
    }
}
```

- [ ] **Step 2: Implement NameIndex**

Create `src/main/java/mcp/data/NameIndex.java`:

```java
package mcp.data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class NameIndex {

    public enum Kind { ITEM, MOB, MAP, NPC, SKILL }

    public record Hit(Kind kind, int id, String name) {}

    private record Entry(Kind kind, int id, String name, String lower) {}

    private final List<Entry> entries = new ArrayList<>();

    public void add(Kind kind, int id, String name) {
        if (name == null) return;
        entries.add(new Entry(kind, id, name, name.toLowerCase()));
    }

    public List<Hit> search(String query, Kind filter, int limit) {
        String q = query.toLowerCase();
        List<Entry> matched = new ArrayList<>();
        for (Entry e : entries) {
            if (filter != null && e.kind != filter) continue;
            if (e.lower.contains(q)) matched.add(e);
        }
        matched.sort(Comparator
                .<Entry>comparingInt(e -> e.lower.startsWith(q) ? 0 : 1)
                .thenComparing(e -> e.lower));
        List<Hit> out = new ArrayList<>(Math.min(limit, matched.size()));
        for (int i = 0; i < matched.size() && i < limit; i++) {
            Entry e = matched.get(i);
            out.add(new Hit(e.kind, e.id, e.name));
        }
        return out;
    }
}
```

- [ ] **Step 3: Run NameIndex tests**

Run: `./mvnw test -Dtest=NameIndexTest -q`
Expected: 4 tests PASS.

- [ ] **Step 4: Write the failing test for NameSearchTool**

Create `src/test/java/mcp/tools/NameSearchToolTest.java`:

```java
package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.data.NameIndex;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NameSearchToolTest {

    private NameIndex index() {
        NameIndex idx = new NameIndex();
        idx.add(NameIndex.Kind.ITEM, 1002357, "Beginner's Glasses");
        idx.add(NameIndex.Kind.MOB, 100100, "Snail");
        return idx;
    }

    @Test
    void call_returnsMatches() throws Exception {
        NameSearchTool tool = new NameSearchTool(index());
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("query", "begin");
        JsonNode out = tool.call(args);
        assertEquals(1, out.get("hits").size());
        assertEquals(1002357, out.get("hits").get(0).get("id").asInt());
    }

    @Test
    void call_filtersByKind() throws Exception {
        NameSearchTool tool = new NameSearchTool(index());
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("query", " ");
        args.put("kind", "mob");
        JsonNode out = tool.call(args);
        for (JsonNode hit : out.get("hits")) assertEquals("MOB", hit.get("kind").asText());
    }

    @Test
    void call_invalidKind_throws() {
        NameSearchTool tool = new NameSearchTool(index());
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("query", "x");
        args.put("kind", "bogus");
        assertThrows(Tool.ToolException.class, () -> tool.call(args));
    }

    @Test
    void call_capLimit() throws Exception {
        NameIndex idx = new NameIndex();
        for (int i = 0; i < 200; i++) idx.add(NameIndex.Kind.ITEM, i, "item " + i);
        NameSearchTool tool = new NameSearchTool(idx);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("query", "item");
        args.put("limit", 500);
        JsonNode out = tool.call(args);
        assertEquals(100, out.get("hits").size());
    }
}
```

- [ ] **Step 5: Implement NameSearchTool**

Create `src/main/java/mcp/tools/NameSearchTool.java`:

```java
package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.data.NameIndex;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;

public class NameSearchTool implements Tool {

    private static final int MAX_LIMIT = 100;
    private static final int DEFAULT_LIMIT = 50;

    private final NameIndex index;

    public NameSearchTool(NameIndex index) {
        this.index = index;
    }

    @Override
    public String name() { return "cosmic.name.search"; }

    @Override
    public String description() { return "Fuzzy-search Cosmic entity names (item/mob/map/npc/skill)."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("query").put("type", "string").put("description", "Substring (case-insensitive).");
        ObjectNode kind = props.putObject("kind");
        kind.put("type", "string");
        kind.put("description", "Optional: item|mob|map|npc|skill.");
        kind.putArray("enum").add("item").add("mob").add("map").add("npc").add("skill");
        props.putObject("limit").put("type", "integer").put("minimum", 1).put("maximum", MAX_LIMIT);
        root.putArray("required").add("query");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) throws ToolException {
        if (!args.has("query") || !args.get("query").isTextual()) {
            throw new ToolException(McpError.INVALID_PARAMS, "missing 'query'");
        }
        String query = args.get("query").asText();
        NameIndex.Kind filter = null;
        if (args.has("kind") && !args.get("kind").isNull()) {
            String k = args.get("kind").asText().toUpperCase();
            try {
                filter = NameIndex.Kind.valueOf(k);
            } catch (IllegalArgumentException e) {
                throw new ToolException(McpError.INVALID_PARAMS, "unknown kind: " + k);
            }
        }
        int limit = DEFAULT_LIMIT;
        if (args.has("limit") && args.get("limit").isInt()) {
            limit = Math.max(1, Math.min(MAX_LIMIT, args.get("limit").asInt()));
        }
        ObjectNode out = JsonRpc.MAPPER.createObjectNode();
        ArrayNode hits = out.putArray("hits");
        for (NameIndex.Hit h : index.search(query, filter, limit)) {
            ObjectNode hn = hits.addObject();
            hn.put("kind", h.kind().name());
            hn.put("id", h.id());
            hn.put("name", h.name());
        }
        return out;
    }
}
```

- [ ] **Step 6: Build NameIndex at MCP startup**

Edit `Server.init()` MCP section. Where the registry is constructed, replace it with code that builds a NameIndex first. The simplest robust approach is a helper method:

```java
private mcp.data.NameIndex buildNameIndex() {
    mcp.data.NameIndex idx = new mcp.data.NameIndex();
    // Items: iterate ItemInformationProvider's loaded names, or walk WZ via DataProviderFactory
    // Mobs: iterate via String.wz "Mob.img"
    // Maps: iterate via String.wz "Map.img"
    // NPCs: iterate via String.wz "Npc.img"
    // Skills: SkillFactory has loaded all skills already; expose a getAll() if needed
    // Implementation note: walk WZ String.wz with provider.DataProviderFactory#getDataProvider(stringWz)
    //   then for each known top-level entry add (kind, id, name) to the index.
    populateFromStringWz(idx);
    return idx;
}
```

The concrete implementation:

```java
private void populateFromStringWz(mcp.data.NameIndex idx) {
    java.io.File wzPath = new java.io.File(System.getProperty("wz-path"));
    provider.DataProvider stringProvider = provider.DataProviderFactory.getDataProvider(new java.io.File(wzPath, "String.wz"));
    populateKind(idx, stringProvider, "Item.img", mcp.data.NameIndex.Kind.ITEM);
    populateKind(idx, stringProvider, "Mob.img", mcp.data.NameIndex.Kind.MOB);
    populateKind(idx, stringProvider, "Map.img", mcp.data.NameIndex.Kind.MAP);
    populateKind(idx, stringProvider, "Npc.img", mcp.data.NameIndex.Kind.NPC);
    populateKind(idx, stringProvider, "Skill.img", mcp.data.NameIndex.Kind.SKILL);
}

private void populateKind(mcp.data.NameIndex idx, provider.DataProvider sp, String img, mcp.data.NameIndex.Kind kind) {
    provider.Data root = sp.getData(img);
    if (root == null) return;
    walkData(root, idx, kind);
}

private void walkData(provider.Data node, mcp.data.NameIndex idx, mcp.data.NameIndex.Kind kind) {
    for (provider.Data child : node.getChildren()) {
        // Try interpret child name as ID
        try {
            int id = Integer.parseInt(child.getName());
            provider.Data nameNode = child.getChildByPath("name");
            if (nameNode != null && nameNode.getData() != null) {
                idx.add(kind, id, nameNode.getData().toString());
            }
        } catch (NumberFormatException ignore) {
            walkData(child, idx, kind);
        }
    }
}
```

(Open `src/main/java/provider/DataProvider.java` and `provider/Data.java` to confirm getter names.)

Then update the registry construction:

```java
mcp.data.NameIndex nameIndex = buildNameIndex();
mcpServer = new McpServer(mcpConfig, new ToolRegistry(java.util.List.of(
        new mcp.tools.SkillTool(),
        new mcp.tools.ItemTool(),
        new mcp.tools.MobTool(),
        new mcp.tools.MapTool(),
        new mcp.tools.NpcTool(),
        new mcp.tools.QuestTool(),
        new mcp.tools.NameSearchTool(nameIndex)
)));
```

- [ ] **Step 7: Run NameSearchTool tests and full build**

Run: `./mvnw test -Dtest=NameSearchToolTest -q`
Expected: 4 tests PASS.

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/mcp/data/NameIndex.java src/main/java/mcp/tools/NameSearchTool.java src/test/java/mcp/data/NameIndexTest.java src/test/java/mcp/tools/NameSearchToolTest.java src/main/java/net/server/Server.java
git commit -m "Add NameIndex + cosmic.name.search MCP tool #minor"
```

---

## Task 14: DropIndex + DropSearchTool (`cosmic.drop.search`)

**Files:**
- Create: `src/main/java/mcp/data/DropIndex.java`
- Create: `src/main/java/mcp/tools/DropSearchTool.java`
- Create: `src/test/java/mcp/data/DropIndexTest.java`
- Create: `src/test/java/mcp/tools/DropSearchToolTest.java`

- [ ] **Step 1: Write the failing test for DropIndex**

Create `src/test/java/mcp/data/DropIndexTest.java`:

```java
package mcp.data;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DropIndexTest {

    @Test
    void byMob_returnsAllDropsForMob() {
        DropIndex idx = new DropIndex();
        idx.add(new DropIndex.Entry(100100, 1002357, 1, 1, 100000, "drop_data"));
        idx.add(new DropIndex.Entry(100100, 4000019, 1, 1, 50000, "drop_data"));
        idx.add(new DropIndex.Entry(100200, 4000019, 1, 1, 50000, "drop_data"));
        List<DropIndex.Entry> drops = idx.byMob(100100);
        assertEquals(2, drops.size());
    }

    @Test
    void byItem_returnsAllSourcesForItem() {
        DropIndex idx = new DropIndex();
        idx.add(new DropIndex.Entry(100100, 4000019, 1, 1, 50000, "drop_data"));
        idx.add(new DropIndex.Entry(100200, 4000019, 1, 1, 50000, "drop_data"));
        List<DropIndex.Entry> sources = idx.byItem(4000019);
        assertEquals(2, sources.size());
    }

    @Test
    void byMob_unknown_returnsEmpty() {
        DropIndex idx = new DropIndex();
        assertEquals(0, idx.byMob(999999).size());
    }
}
```

- [ ] **Step 2: Implement DropIndex**

Create `src/main/java/mcp/data/DropIndex.java`:

```java
package mcp.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DropIndex {

    private static final Logger log = LoggerFactory.getLogger(DropIndex.class);

    public record Entry(int mobId, int itemId, int min, int max, int chance, String source) {}

    private final Map<Integer, List<Entry>> byMob = new HashMap<>();
    private final Map<Integer, List<Entry>> byItem = new HashMap<>();

    public void add(Entry e) {
        byMob.computeIfAbsent(e.mobId, k -> new ArrayList<>()).add(e);
        byItem.computeIfAbsent(e.itemId, k -> new ArrayList<>()).add(e);
    }

    public List<Entry> byMob(int mobId) {
        return byMob.getOrDefault(mobId, List.of());
    }

    public List<Entry> byItem(int itemId) {
        return byItem.getOrDefault(itemId, List.of());
    }

    public static DropIndex loadFrom(java.util.function.Supplier<Connection> conSupplier) {
        DropIndex idx = new DropIndex();
        try (Connection con = conSupplier.get()) {
            loadTable(con, idx, "drop_data",
                    "SELECT dropperid, itemid, minimum_quantity, maximum_quantity, chance FROM drop_data");
            loadTable(con, idx, "global_drop_data",
                    "SELECT 0 AS dropperid, itemid, minimum_quantity, maximum_quantity, chance FROM global_drop_data");
            loadTable(con, idx, "reactordrops_data",
                    "SELECT reactorid AS dropperid, itemid, 1 AS minimum_quantity, 1 AS maximum_quantity, chance FROM reactordrops_data");
        } catch (SQLException e) {
            log.warn("DropIndex load failed", e);
        }
        log.info("mcp DropIndex loaded: {} mob keys, {} item keys", idx.byMob.size(), idx.byItem.size());
        return idx;
    }

    private static void loadTable(Connection con, DropIndex idx, String src, String sql) throws SQLException {
        try (PreparedStatement ps = con.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                idx.add(new Entry(
                        rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5), src));
            }
        }
    }
}
```

(Verify the column names in `src/main/resources/db/data/152-drop-data.sql`, `151-global-drop-data.sql`, `131-reactordrops-data.sql` and adjust the SELECTs if they differ.)

- [ ] **Step 3: Run DropIndex tests**

Run: `./mvnw test -Dtest=DropIndexTest -q`
Expected: 3 tests PASS.

- [ ] **Step 4: Write the failing test for DropSearchTool**

Create `src/test/java/mcp/tools/DropSearchToolTest.java`:

```java
package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.data.DropIndex;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DropSearchToolTest {

    @Test
    void call_byMob_returnsDrops() throws Exception {
        DropIndex idx = new DropIndex();
        idx.add(new DropIndex.Entry(100100, 4000019, 1, 1, 50000, "drop_data"));
        DropSearchTool tool = new DropSearchTool(idx);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("mob_id", 100100);
        JsonNode out = tool.call(args);
        assertEquals(1, out.get("drops").size());
        assertEquals(4000019, out.get("drops").get(0).get("itemId").asInt());
    }

    @Test
    void call_byItem_returnsSources() throws Exception {
        DropIndex idx = new DropIndex();
        idx.add(new DropIndex.Entry(100100, 4000019, 1, 1, 50000, "drop_data"));
        idx.add(new DropIndex.Entry(100200, 4000019, 1, 1, 50000, "drop_data"));
        DropSearchTool tool = new DropSearchTool(idx);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("item_id", 4000019);
        JsonNode out = tool.call(args);
        assertEquals(2, out.get("drops").size());
    }

    @Test
    void call_neitherIdProvided_throws() {
        DropSearchTool tool = new DropSearchTool(new DropIndex());
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        Tool.ToolException ex = assertThrows(Tool.ToolException.class, () -> tool.call(args));
        assertEquals(-32602, ex.code());
    }
}
```

- [ ] **Step 5: Implement DropSearchTool**

Create `src/main/java/mcp/tools/DropSearchTool.java`:

```java
package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.data.DropIndex;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;

import java.util.List;

public class DropSearchTool implements Tool {

    private final DropIndex index;

    public DropSearchTool(DropIndex index) {
        this.index = index;
    }

    @Override
    public String name() { return "cosmic.drop.search"; }

    @Override
    public String description() { return "Find drops by mob_id or sources of a given item_id."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("mob_id").put("type", "integer");
        props.putObject("item_id").put("type", "integer");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) throws ToolException {
        boolean hasMob = args.has("mob_id") && args.get("mob_id").isInt();
        boolean hasItem = args.has("item_id") && args.get("item_id").isInt();
        if (!hasMob && !hasItem) {
            throw new ToolException(McpError.INVALID_PARAMS, "provide mob_id or item_id");
        }
        if (hasMob && hasItem) {
            throw new ToolException(McpError.INVALID_PARAMS, "provide exactly one of mob_id or item_id");
        }
        List<DropIndex.Entry> entries = hasMob
                ? index.byMob(args.get("mob_id").asInt())
                : index.byItem(args.get("item_id").asInt());

        ObjectNode out = JsonRpc.MAPPER.createObjectNode();
        ArrayNode arr = out.putArray("drops");
        for (DropIndex.Entry e : entries) {
            ObjectNode n = arr.addObject();
            n.put("mobId", e.mobId());
            n.put("itemId", e.itemId());
            n.put("min", e.min());
            n.put("max", e.max());
            n.put("chance", e.chance());
            n.put("source", e.source());
        }
        return out;
    }
}
```

- [ ] **Step 6: Build DropIndex in Server.init()**

Update the MCP boot block in `Server.init()`. Add a load step:

```java
mcp.data.DropIndex dropIndex = mcp.data.DropIndex.loadFrom(() -> {
    try { return tools.DatabaseConnection.getConnection(); }
    catch (java.sql.SQLException ex) { throw new RuntimeException(ex); }
});
```

Add `new mcp.tools.DropSearchTool(dropIndex)` to the `List.of(...)`.

- [ ] **Step 7: Run tests and build**

Run: `./mvnw test -Dtest='DropIndexTest,DropSearchToolTest' -q`
Expected: all PASS.

Run: `./mvnw compile -q`
Expected: BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/mcp/data/DropIndex.java src/main/java/mcp/tools/DropSearchTool.java src/test/java/mcp/data/DropIndexTest.java src/test/java/mcp/tools/DropSearchToolTest.java src/main/java/net/server/Server.java
git commit -m "Add DropIndex + cosmic.drop.search MCP tool #minor"
```

---

## Task 15: ScriptFinderTool (`cosmic.script.find`)

**Files:**
- Create: `src/main/java/mcp/tools/ScriptFinderTool.java`
- Create: `src/test/java/mcp/tools/ScriptFinderToolTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/mcp/tools/ScriptFinderToolTest.java`:

```java
package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptFinderToolTest {

    @TempDir
    Path scriptsRoot;

    @Test
    void call_findsByItemIdReference() throws IOException {
        Path npc = Files.createDirectories(scriptsRoot.resolve("npc"));
        Files.writeString(npc.resolve("9201000.js"), "cm.gainItem(1002357, 1);\n");
        Files.writeString(npc.resolve("9201001.js"), "// nothing relevant\n");
        ScriptFinderTool tool = new ScriptFinderTool(scriptsRoot);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("query", "1002357");
        JsonNode out = tool.call(args);
        assertEquals(1, out.get("matches").size());
        assertTrue(out.get("matches").get(0).get("file").asText().endsWith("9201000.js"));
        assertEquals(1, out.get("matches").get(0).get("line").asInt());
    }

    @Test
    void call_capLimit() throws IOException {
        Path q = Files.createDirectories(scriptsRoot.resolve("quest"));
        for (int i = 0; i < 200; i++) {
            Files.writeString(q.resolve(i + ".js"), "var KEY = \"banana\";\n");
        }
        ScriptFinderTool tool = new ScriptFinderTool(scriptsRoot);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("query", "banana");
        args.put("limit", 500);
        JsonNode out = tool.call(args);
        assertEquals(100, out.get("matches").size());
    }
}
```

- [ ] **Step 2: Implement ScriptFinderTool**

Create `src/main/java/mcp/tools/ScriptFinderTool.java`:

```java
package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

public class ScriptFinderTool implements Tool {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;

    private final Path scriptsRoot;

    public ScriptFinderTool() {
        this(Path.of("scripts"));
    }

    public ScriptFinderTool(Path scriptsRoot) {
        this.scriptsRoot = scriptsRoot;
    }

    @Override
    public String name() { return "cosmic.script.find"; }

    @Override
    public String description() { return "Search Cosmic JS scripts (NPC, quest, reactor, etc.) for a substring."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("query").put("type", "string");
        props.putObject("limit").put("type", "integer").put("minimum", 1).put("maximum", MAX_LIMIT);
        root.putArray("required").add("query");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) throws ToolException {
        if (!args.has("query") || !args.get("query").isTextual()) {
            throw new ToolException(McpError.INVALID_PARAMS, "missing 'query'");
        }
        String query = args.get("query").asText();
        int limit = DEFAULT_LIMIT;
        if (args.has("limit") && args.get("limit").isInt()) {
            limit = Math.max(1, Math.min(MAX_LIMIT, args.get("limit").asInt()));
        }

        ObjectNode out = JsonRpc.MAPPER.createObjectNode();
        ArrayNode matches = out.putArray("matches");
        if (!Files.isDirectory(scriptsRoot)) {
            return out;
        }
        try (Stream<Path> walker = Files.walk(scriptsRoot)) {
            for (Path p : (Iterable<Path>) walker::iterator) {
                if (matches.size() >= limit) break;
                if (!Files.isRegularFile(p)) continue;
                String fname = p.getFileName().toString();
                if (!fname.endsWith(".js")) continue;
                int line = 0;
                for (String content : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    line++;
                    if (content.contains(query)) {
                        ObjectNode m = matches.addObject();
                        m.put("file", p.toString());
                        m.put("line", line);
                        m.put("snippet", content.length() > 200 ? content.substring(0, 200) : content);
                        if (matches.size() >= limit) break;
                    }
                }
            }
        } catch (IOException e) {
            throw new ToolException(McpError.INTERNAL_ERROR, "scan failed: " + e.getMessage());
        }
        return out;
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./mvnw test -Dtest=ScriptFinderToolTest -q`
Expected: 2 tests PASS.

- [ ] **Step 4: Register tool in Server.java**

Add `new mcp.tools.ScriptFinderTool()` to the `List.of(...)`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/mcp/tools/ScriptFinderTool.java src/test/java/mcp/tools/ScriptFinderToolTest.java src/main/java/net/server/Server.java
git commit -m "Add cosmic.script.find MCP tool #minor"
```

---

## Task 16: JavaCodeSearchTool (`cosmic.code.search`) — text + opcode mode

**Files:**
- Create: `src/main/java/mcp/tools/JavaCodeSearchTool.java`
- Create: `src/test/java/mcp/tools/JavaCodeSearchToolTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/mcp/tools/JavaCodeSearchToolTest.java`:

```java
package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaCodeSearchToolTest {

    @TempDir
    Path srcRoot;

    @Test
    void call_textMode_findsStringMatches() throws IOException {
        Path pkg = Files.createDirectories(srcRoot.resolve("net/opcodes"));
        Files.writeString(pkg.resolve("Demo.java"), "class Demo { String x = \"hello\"; }\n");
        JavaCodeSearchTool tool = new JavaCodeSearchTool(srcRoot);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("query", "hello");
        JsonNode out = tool.call(args);
        assertEquals(1, out.get("matches").size());
    }

    @Test
    void call_opcodeMode_resolvesHexConstant() throws IOException {
        Path pkg = Files.createDirectories(srcRoot.resolve("net/opcodes"));
        Files.writeString(pkg.resolve("RecvOpcode.java"),
                "package net.opcodes;\n" +
                "public enum RecvOpcode {\n" +
                "    PLAYER_LOGGEDIN((short) 0x0014),\n" +
                "    USE_ITEM((short) 0x6C);\n" +
                "    private final short code;\n" +
                "    RecvOpcode(short code) { this.code = code; }\n" +
                "}\n");
        Path handlers = Files.createDirectories(srcRoot.resolve("net/handlers"));
        Files.writeString(handlers.resolve("UseItemHandler.java"),
                "import net.opcodes.RecvOpcode;\n" +
                "class UseItemHandler { RecvOpcode op = RecvOpcode.USE_ITEM; }\n");

        JavaCodeSearchTool tool = new JavaCodeSearchTool(srcRoot);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("query", "0x6C");
        args.put("kind", "opcode");
        JsonNode out = tool.call(args);
        assertEquals("USE_ITEM", out.get("opcodeName").asText());
        assertTrue(out.get("matches").size() >= 1);
    }

    @Test
    void call_opcodeMode_unresolvedOpcode_throws() throws IOException {
        Path pkg = Files.createDirectories(srcRoot.resolve("net/opcodes"));
        Files.writeString(pkg.resolve("RecvOpcode.java"),
                "public enum RecvOpcode { FOO((short) 0x01); RecvOpcode(short c){} }\n");
        JavaCodeSearchTool tool = new JavaCodeSearchTool(srcRoot);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("query", "0xEE");
        args.put("kind", "opcode");
        Tool.ToolException ex = assertThrows(Tool.ToolException.class, () -> tool.call(args));
        assertEquals(-32602, ex.code());
    }
}
```

- [ ] **Step 2: Implement JavaCodeSearchTool**

Create `src/main/java/mcp/tools/JavaCodeSearchTool.java`:

```java
package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class JavaCodeSearchTool implements Tool {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 100;
    // Matches enum constants like:  USE_ITEM((short) 0x6C),  or  USE_ITEM(0x6C),  or USE_ITEM(108)
    private static final Pattern OPCODE_DECL = Pattern.compile(
            "(?m)^\\s*([A-Z_][A-Z0-9_]*)\\s*\\(\\s*(?:\\(\\s*short\\s*\\))?\\s*(0x[0-9a-fA-F]+|\\d+)\\s*\\)");

    private final Path srcRoot;

    public JavaCodeSearchTool() {
        this(Path.of("src/main/java"));
    }

    public JavaCodeSearchTool(Path srcRoot) {
        this.srcRoot = srcRoot;
    }

    @Override
    public String name() { return "cosmic.code.search"; }

    @Override
    public String description() { return "Search Cosmic Java sources. kind=opcode resolves a hex/int opcode to its enum name and finds references."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("query").put("type", "string");
        ObjectNode kind = props.putObject("kind");
        kind.put("type", "string");
        kind.putArray("enum").add("text").add("opcode");
        props.putObject("limit").put("type", "integer").put("minimum", 1).put("maximum", MAX_LIMIT);
        root.putArray("required").add("query");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) throws ToolException {
        if (!args.has("query") || !args.get("query").isTextual()) {
            throw new ToolException(McpError.INVALID_PARAMS, "missing 'query'");
        }
        String query = args.get("query").asText();
        String kind = args.has("kind") ? args.get("kind").asText() : "text";
        int limit = DEFAULT_LIMIT;
        if (args.has("limit") && args.get("limit").isInt()) {
            limit = Math.max(1, Math.min(MAX_LIMIT, args.get("limit").asInt()));
        }
        ObjectNode out = JsonRpc.MAPPER.createObjectNode();

        String searchTerm;
        if ("opcode".equals(kind)) {
            int target;
            try { target = Integer.decode(query); }
            catch (NumberFormatException e) {
                throw new ToolException(McpError.INVALID_PARAMS, "opcode must be hex (0x6C) or int");
            }
            String resolved = resolveOpcodeName(target);
            if (resolved == null) {
                throw new ToolException(McpError.INVALID_PARAMS, "no opcode constant matches " + query);
            }
            out.put("opcodeName", resolved);
            searchTerm = resolved;
        } else {
            searchTerm = query;
        }

        ArrayNode matches = out.putArray("matches");
        try (Stream<Path> walker = Files.walk(srcRoot)) {
            for (Path p : (Iterable<Path>) walker::iterator) {
                if (matches.size() >= limit) break;
                if (!Files.isRegularFile(p)) continue;
                if (!p.getFileName().toString().endsWith(".java")) continue;
                int line = 0;
                for (String content : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    line++;
                    if (content.contains(searchTerm)) {
                        ObjectNode m = matches.addObject();
                        m.put("file", p.toString());
                        m.put("line", line);
                        m.put("snippet", content.length() > 200 ? content.substring(0, 200) : content);
                        if (matches.size() >= limit) break;
                    }
                }
            }
        } catch (IOException e) {
            throw new ToolException(McpError.INTERNAL_ERROR, "scan failed: " + e.getMessage());
        }
        return out;
    }

    private String resolveOpcodeName(int target) throws ToolException {
        Path[] candidates = {
                srcRoot.resolve("net/opcodes/RecvOpcode.java"),
                srcRoot.resolve("net/opcodes/SendOpcode.java")
        };
        for (Path c : candidates) {
            if (!Files.exists(c)) continue;
            try {
                String body = Files.readString(c, StandardCharsets.UTF_8);
                Matcher m = OPCODE_DECL.matcher(body);
                while (m.find()) {
                    int v;
                    try { v = Integer.decode(m.group(2)); }
                    catch (NumberFormatException e) { continue; }
                    if (v == target) return m.group(1);
                }
            } catch (IOException e) {
                throw new ToolException(McpError.INTERNAL_ERROR, "opcode read failed: " + e.getMessage());
            }
        }
        return null;
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./mvnw test -Dtest=JavaCodeSearchToolTest -q`
Expected: 3 tests PASS.

- [ ] **Step 4: Register tool in Server.java**

Add `new mcp.tools.JavaCodeSearchTool()` to the `List.of(...)`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/mcp/tools/JavaCodeSearchTool.java src/test/java/mcp/tools/JavaCodeSearchToolTest.java src/main/java/net/server/Server.java
git commit -m "Add cosmic.code.search MCP tool with opcode resolution #minor"
```

---

## Task 17: ConfigInspectTool (`cosmic.config.get`)

**Files:**
- Create: `src/main/java/mcp/tools/ConfigInspectTool.java`
- Create: `src/test/java/mcp/tools/ConfigInspectToolTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/mcp/tools/ConfigInspectToolTest.java`:

```java
package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigInspectToolTest {

    @TempDir
    Path tmp;

    private Path writeYaml(String yaml) throws IOException {
        Path p = tmp.resolve("config.yaml");
        Files.writeString(p, yaml);
        return p;
    }

    @Test
    void call_simpleScalar() throws Exception {
        Path p = writeYaml("server:\n  port: 8484\n");
        ConfigInspectTool tool = new ConfigInspectTool(p);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("path", "server.port");
        JsonNode out = tool.call(args);
        assertEquals(8484, out.get("value").asInt());
    }

    @Test
    void call_arrayIndex() throws Exception {
        Path p = writeYaml("worlds:\n  - exp_rate: 10\n  - exp_rate: 100\n");
        ConfigInspectTool tool = new ConfigInspectTool(p);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("path", "worlds[1].exp_rate");
        JsonNode out = tool.call(args);
        assertEquals(100, out.get("value").asInt());
    }

    @Test
    void call_unknownPath_throws() throws Exception {
        Path p = writeYaml("server:\n  port: 8484\n");
        ConfigInspectTool tool = new ConfigInspectTool(p);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("path", "server.nonexistent");
        Tool.ToolException ex = assertThrows(Tool.ToolException.class, () -> tool.call(args));
        assertEquals(-32602, ex.code());
    }
}
```

- [ ] **Step 2: Implement ConfigInspectTool**

Create `src/main/java/mcp/tools/ConfigInspectTool.java`:

```java
package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ConfigInspectTool implements Tool {

    private static final Pattern SEGMENT = Pattern.compile("([^.\\[\\]]+)|\\[(\\d+)\\]");

    private final Path configPath;

    public ConfigInspectTool() {
        this(Path.of("config.yaml"));
    }

    public ConfigInspectTool(Path configPath) {
        this.configPath = configPath;
    }

    @Override
    public String name() { return "cosmic.config.get"; }

    @Override
    public String description() { return "Read a value from config.yaml by dotted path (supports [index])."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        root.putObject("properties").putObject("path").put("type", "string");
        root.putArray("required").add("path");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) throws ToolException {
        if (!args.has("path") || !args.get("path").isTextual()) {
            throw new ToolException(McpError.INVALID_PARAMS, "missing 'path'");
        }
        String path = args.get("path").asText();
        JsonNode root;
        try {
            root = new YAMLMapper().readTree(Files.readString(configPath));
        } catch (IOException e) {
            throw new ToolException(McpError.INTERNAL_ERROR, "config read failed: " + e.getMessage());
        }
        JsonNode cur = root;
        Matcher m = SEGMENT.matcher(path);
        while (m.find()) {
            if (m.group(1) != null) {
                cur = cur.path(m.group(1));
            } else {
                cur = cur.path(Integer.parseInt(m.group(2)));
            }
            if (cur.isMissingNode()) {
                throw new ToolException(McpError.INVALID_PARAMS, "no such path: " + path);
            }
        }
        ObjectNode out = JsonRpc.MAPPER.createObjectNode();
        out.set("value", cur);
        return out;
    }
}
```

This pulls in `com.fasterxml.jackson.dataformat:jackson-dataformat-yaml`. Add it to `pom.xml` in the MCP dependency block:

```xml
<dependency>
    <groupId>com.fasterxml.jackson.dataformat</groupId>
    <artifactId>jackson-dataformat-yaml</artifactId>
    <version>${jackson.version}</version>
</dependency>
```

- [ ] **Step 3: Run tests**

Run: `./mvnw test -Dtest=ConfigInspectToolTest -q`
Expected: 3 tests PASS.

- [ ] **Step 4: Register tool in Server.java**

Add `new mcp.tools.ConfigInspectTool()` to the `List.of(...)`.

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/main/java/mcp/tools/ConfigInspectTool.java src/test/java/mcp/tools/ConfigInspectToolTest.java src/main/java/net/server/Server.java
git commit -m "Add cosmic.config.get MCP tool #minor"
```

---

## Task 18: SchemaTool (`cosmic.db.schema`)

**Files:**
- Create: `src/main/java/mcp/tools/SchemaTool.java`
- Create: `src/test/java/mcp/tools/SchemaToolTest.java`

- [ ] **Step 1: Write the failing test using Testcontainers**

Create `src/test/java/mcp/tools/SchemaToolTest.java`:

```java
package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaToolTest {

    static MySQLContainer<?> mysql;
    static Supplier<Connection> conSupplier;

    @BeforeAll
    static void up() throws SQLException {
        mysql = new MySQLContainer<>("mysql:8.0").withDatabaseName("cosmic_test");
        mysql.start();
        conSupplier = () -> {
            try { return DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()); }
            catch (SQLException e) { throw new RuntimeException(e); }
        };
        try (Connection c = conSupplier.get(); var s = c.createStatement()) {
            s.execute("CREATE TABLE demo_account (id INT PRIMARY KEY, name VARCHAR(50))");
        }
    }

    @AfterAll
    static void down() {
        if (mysql != null) mysql.stop();
    }

    @Test
    void call_listsTables() throws Exception {
        SchemaTool tool = new SchemaTool(conSupplier);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        JsonNode out = tool.call(args);
        boolean found = false;
        for (JsonNode t : out.get("tables")) if ("demo_account".equals(t.asText())) found = true;
        assertTrue(found);
    }

    @Test
    void call_describesTable() throws Exception {
        SchemaTool tool = new SchemaTool(conSupplier);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("table", "demo_account");
        JsonNode out = tool.call(args);
        assertEquals("demo_account", out.get("table").asText());
        assertTrue(out.get("columns").size() >= 2);
    }

    @Test
    void call_unknownTable_throws() {
        SchemaTool tool = new SchemaTool(conSupplier);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("table", "nonexistent_table");
        assertThrows(Tool.ToolException.class, () -> tool.call(args));
    }
}
```

- [ ] **Step 2: Implement SchemaTool**

Create `src/main/java/mcp/tools/SchemaTool.java`:

```java
package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.function.Supplier;

public class SchemaTool implements Tool {

    private final Supplier<Connection> conSupplier;

    public SchemaTool(Supplier<Connection> conSupplier) {
        this.conSupplier = conSupplier;
    }

    @Override
    public String name() { return "cosmic.db.schema"; }

    @Override
    public String description() { return "List DB tables, or describe one (columns + FKs)."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        root.putObject("properties").putObject("table").put("type", "string");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) throws ToolException {
        ObjectNode out = JsonRpc.MAPPER.createObjectNode();
        try (Connection con = conSupplier.get()) {
            if (args.has("table") && args.get("table").isTextual()) {
                describe(con, args.get("table").asText(), out);
            } else {
                listTables(con, out);
            }
        } catch (SQLException e) {
            throw new ToolException(McpError.INTERNAL_ERROR, "db error: " + e.getMessage());
        }
        return out;
    }

    private void listTables(Connection con, ObjectNode out) throws SQLException {
        ArrayNode arr = out.putArray("tables");
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = DATABASE() ORDER BY TABLE_NAME");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) arr.add(rs.getString(1));
        }
    }

    private void describe(Connection con, String table, ObjectNode out) throws SQLException, ToolException {
        ArrayNode cols;
        out.put("table", table);
        cols = out.putArray("columns");
        boolean any = false;
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE, COLUMN_DEFAULT, COLUMN_KEY " +
                "FROM INFORMATION_SCHEMA.COLUMNS " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    any = true;
                    ObjectNode col = cols.addObject();
                    col.put("name", rs.getString(1));
                    col.put("type", rs.getString(2));
                    col.put("nullable", "YES".equals(rs.getString(3)));
                    col.put("default", rs.getString(4));
                    col.put("key", rs.getString(5));
                }
            }
        }
        if (!any) {
            throw new ToolException(McpError.INVALID_PARAMS, "no such table: " + table);
        }
        ArrayNode fks = out.putArray("foreignKeys");
        try (PreparedStatement ps = con.prepareStatement(
                "SELECT COLUMN_NAME, REFERENCED_TABLE_NAME, REFERENCED_COLUMN_NAME " +
                "FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE " +
                "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND REFERENCED_TABLE_NAME IS NOT NULL")) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ObjectNode fk = fks.addObject();
                    fk.put("column", rs.getString(1));
                    fk.put("refTable", rs.getString(2));
                    fk.put("refColumn", rs.getString(3));
                }
            }
        }
    }
}
```

- [ ] **Step 3: Run tests (Testcontainers requires Docker running)**

Run: `./mvnw test -Dtest=SchemaToolTest -q`
Expected: 3 tests PASS.

If Docker is unavailable, mark this task partially blocked and re-run later — do NOT skip the test code.

- [ ] **Step 4: Register tool in Server.java**

Add the following to the `List.of(...)`:

```java
new mcp.tools.SchemaTool(() -> {
    try { return tools.DatabaseConnection.getConnection(); }
    catch (java.sql.SQLException ex) { throw new RuntimeException(ex); }
})
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/mcp/tools/SchemaTool.java src/test/java/mcp/tools/SchemaToolTest.java src/main/java/net/server/Server.java
git commit -m "Add cosmic.db.schema MCP tool #minor"
```

---

## Task 19: SqlSafety + SqlSelectTool (`cosmic.db.select`)

**Files:**
- Create: `src/main/java/mcp/data/SqlSafety.java`
- Create: `src/main/java/mcp/tools/SqlSelectTool.java`
- Create: `src/test/java/mcp/data/SqlSafetyTest.java`
- Create: `src/test/java/mcp/tools/SqlSelectToolTest.java`

- [ ] **Step 1: Write the failing test for SqlSafety**

Create `src/test/java/mcp/data/SqlSafetyTest.java`:

```java
package mcp.data;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlSafetyTest {

    private final SqlSafety safety = new SqlSafety(List.of("account.password", "account.email"));

    @Test
    void check_simpleSelect_passes() throws Exception {
        safety.check("SELECT id, name FROM character WHERE accountid = ?");
    }

    @Test
    void check_insert_rejected() {
        SqlSafety.UnsafeSqlException ex = assertThrows(SqlSafety.UnsafeSqlException.class,
                () -> safety.check("INSERT INTO character VALUES (1)"));
        assertEquals("only single SELECT allowed", ex.getMessage());
    }

    @Test
    void check_multiStatement_rejected() {
        assertThrows(SqlSafety.UnsafeSqlException.class,
                () -> safety.check("SELECT 1; SELECT 2"));
    }

    @Test
    void check_piiColumn_rejected() {
        SqlSafety.UnsafeSqlException ex = assertThrows(SqlSafety.UnsafeSqlException.class,
                () -> safety.check("SELECT password FROM account"));
        assertEquals("denied column: account.password", ex.getMessage());
    }

    @Test
    void check_piiColumnViaQualifiedRef_rejected() {
        assertThrows(SqlSafety.UnsafeSqlException.class,
                () -> safety.check("SELECT a.email FROM account a"));
    }

    @Test
    void check_emptySql_rejected() {
        assertThrows(SqlSafety.UnsafeSqlException.class, () -> safety.check(""));
    }
}
```

- [ ] **Step 2: Implement SqlSafety**

Create `src/main/java/mcp/data/SqlSafety.java`:

```java
package mcp.data;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.deparser.ExpressionDeParser;
import net.sf.jsqlparser.util.deparser.SelectDeParser;
import net.sf.jsqlparser.util.deparser.StatementDeParser;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class SqlSafety {

    private final Map<String, String> denied; // column-lower -> "table.column" for messages

    public SqlSafety(List<String> piiDenylist) {
        this.denied = new HashMap<>();
        for (String entry : piiDenylist) {
            String[] parts = entry.toLowerCase(Locale.ROOT).split("\\.");
            if (parts.length == 2) {
                // key by column name; we still match against the qualified form for the error message
                denied.put(parts[1], entry.toLowerCase(Locale.ROOT));
            }
        }
    }

    public void check(String sql) throws UnsafeSqlException {
        if (sql == null || sql.isBlank()) throw new UnsafeSqlException("empty sql");
        Statements stmts;
        try {
            stmts = CCJSqlParserUtil.parseStatements(sql);
        } catch (JSQLParserException e) {
            throw new UnsafeSqlException("parse error: " + e.getMessage());
        }
        if (stmts.getStatements().size() != 1) {
            throw new UnsafeSqlException("only single SELECT allowed");
        }
        Statement s = stmts.getStatements().get(0);
        if (!(s instanceof Select)) {
            throw new UnsafeSqlException("only single SELECT allowed");
        }
        DenyVisitor v = new DenyVisitor(denied);
        StringBuilder buf = new StringBuilder();
        new StatementDeParser(new ExpressionDeParser(new SelectDeParser(v, buf), buf), new SelectDeParser(v, buf), buf).deParse(s);
        if (v.violation != null) {
            throw new UnsafeSqlException("denied column: " + v.violation);
        }
    }

    public static class UnsafeSqlException extends Exception {
        public UnsafeSqlException(String msg) { super(msg); }
    }

    private static class DenyVisitor extends ExpressionDeParser {
        final Map<String, String> denied;
        String violation;

        DenyVisitor(Map<String, String> denied) { this.denied = denied; }

        @Override
        public void visit(Column col) {
            if (violation != null) return;
            String name = col.getColumnName().toLowerCase(Locale.ROOT);
            if (denied.containsKey(name)) {
                violation = denied.get(name);
            }
            super.visit(col);
        }
    }
}
```

(JSqlParser API can vary by version; if `ExpressionDeParser` integration is awkward, fall back to a string-walk: lowercase the SQL, look for any of the denied column names with a word boundary. The behavior tests in Step 1 still apply.)

- [ ] **Step 3: Write the failing test for SqlSelectTool**

Create `src/test/java/mcp/tools/SqlSelectToolTest.java`:

```java
package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.data.SqlSafety;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlSelectToolTest {

    static MySQLContainer<?> mysql;
    static Supplier<Connection> conSupplier;

    @BeforeAll
    static void up() throws SQLException {
        mysql = new MySQLContainer<>("mysql:8.0").withDatabaseName("cosmic_test");
        mysql.start();
        conSupplier = () -> {
            try { return DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()); }
            catch (SQLException e) { throw new RuntimeException(e); }
        };
        try (Connection c = conSupplier.get(); var s = c.createStatement()) {
            s.execute("CREATE TABLE account (id INT PRIMARY KEY, name VARCHAR(50), password VARCHAR(50))");
            s.execute("INSERT INTO account VALUES (1, 'admin', 'secret')");
        }
    }

    @AfterAll
    static void down() {
        if (mysql != null) mysql.stop();
    }

    @Test
    void call_select_returnsRows() throws Exception {
        SqlSelectTool tool = new SqlSelectTool(conSupplier,
                new SqlSafety(List.of("account.password")), 5, 1000);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("sql", "SELECT id, name FROM account");
        JsonNode out = tool.call(args);
        assertEquals(1, out.get("rows").size());
        assertEquals("admin", out.get("rows").get(0).get("name").asText());
        assertEquals(false, out.get("truncated").asBoolean());
    }

    @Test
    void call_piiColumn_rejected() {
        SqlSelectTool tool = new SqlSelectTool(conSupplier,
                new SqlSafety(List.of("account.password")), 5, 1000);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("sql", "SELECT password FROM account");
        Tool.ToolException ex = assertThrows(Tool.ToolException.class, () -> tool.call(args));
        assertEquals(-32602, ex.code());
        assertTrue(ex.getMessage().contains("password"));
    }

    @Test
    void call_insert_rejected() {
        SqlSelectTool tool = new SqlSelectTool(conSupplier,
                new SqlSafety(List.of()), 5, 1000);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("sql", "INSERT INTO account VALUES (2, 'x', 'y')");
        assertThrows(Tool.ToolException.class, () -> tool.call(args));
    }

    @Test
    void call_truncatedAtCap() throws Exception {
        try (Connection c = conSupplier.get(); var s = c.createStatement()) {
            for (int i = 2; i < 12; i++) s.execute("INSERT INTO account VALUES (" + i + ", 'u" + i + "', 'p')");
        }
        SqlSelectTool tool = new SqlSelectTool(conSupplier,
                new SqlSafety(List.of("account.password")), 5, 3);
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("sql", "SELECT id, name FROM account ORDER BY id");
        JsonNode out = tool.call(args);
        assertEquals(3, out.get("rows").size());
        assertEquals(true, out.get("truncated").asBoolean());
    }
}
```

- [ ] **Step 4: Implement SqlSelectTool**

Create `src/main/java/mcp/tools/SqlSelectTool.java`:

```java
package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.data.SqlSafety;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.util.function.Supplier;

public class SqlSelectTool implements Tool {

    private final Supplier<Connection> conSupplier;
    private final SqlSafety safety;
    private final int timeoutSeconds;
    private final int rowCap;

    public SqlSelectTool(Supplier<Connection> conSupplier, SqlSafety safety,
                         int timeoutSeconds, int rowCap) {
        this.conSupplier = conSupplier;
        this.safety = safety;
        this.timeoutSeconds = timeoutSeconds;
        this.rowCap = rowCap;
    }

    @Override
    public String name() { return "cosmic.db.select"; }

    @Override
    public String description() { return "Run a read-only SELECT (capped, denylisted PII columns blocked)."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode props = root.putObject("properties");
        props.putObject("sql").put("type", "string");
        props.putObject("params").put("type", "array");
        root.putArray("required").add("sql");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) throws ToolException {
        if (!args.has("sql") || !args.get("sql").isTextual()) {
            throw new ToolException(McpError.INVALID_PARAMS, "missing 'sql'");
        }
        String sql = args.get("sql").asText();
        try {
            safety.check(sql);
        } catch (SqlSafety.UnsafeSqlException e) {
            throw new ToolException(McpError.INVALID_PARAMS, e.getMessage());
        }
        ObjectNode out = JsonRpc.MAPPER.createObjectNode();
        ArrayNode rows = out.putArray("rows");
        ArrayNode columns = out.putArray("columns");
        boolean truncated = false;
        try (Connection con = conSupplier.get()) {
            con.setReadOnly(true);
            try (PreparedStatement ps = con.prepareStatement(sql)) {
                ps.setQueryTimeout(timeoutSeconds);
                if (args.has("params") && args.get("params").isArray()) {
                    int i = 1;
                    for (JsonNode p : args.get("params")) {
                        if (p.isInt()) ps.setInt(i, p.asInt());
                        else if (p.isLong()) ps.setLong(i, p.asLong());
                        else if (p.isBoolean()) ps.setBoolean(i, p.asBoolean());
                        else ps.setString(i, p.asText());
                        i++;
                    }
                }
                try (ResultSet rs = ps.executeQuery()) {
                    ResultSetMetaData md = rs.getMetaData();
                    for (int c = 1; c <= md.getColumnCount(); c++) columns.add(md.getColumnLabel(c));
                    int count = 0;
                    while (rs.next()) {
                        if (count >= rowCap) { truncated = true; break; }
                        ObjectNode row = rows.addObject();
                        for (int c = 1; c <= md.getColumnCount(); c++) {
                            Object v = rs.getObject(c);
                            String key = md.getColumnLabel(c);
                            if (v == null) row.putNull(key);
                            else if (v instanceof Number n) row.put(key, n.toString());
                            else row.put(key, v.toString());
                        }
                        count++;
                    }
                }
            }
        } catch (SQLTimeoutException e) {
            throw new ToolException(McpError.QUERY_TIMEOUT, "query_timeout");
        } catch (SQLException e) {
            throw new ToolException(McpError.INTERNAL_ERROR, "db error: " + e.getMessage());
        }
        out.put("truncated", truncated);
        return out;
    }
}
```

- [ ] **Step 5: Run tests**

Run: `./mvnw test -Dtest='SqlSafetyTest,SqlSelectToolTest' -q`
Expected: all PASS.

- [ ] **Step 6: Register tool in Server.java**

Add to the `List.of(...)`:

```java
new mcp.tools.SqlSelectTool(
        () -> {
            try { return tools.DatabaseConnection.getConnection(); }
            catch (java.sql.SQLException ex) { throw new RuntimeException(ex); }
        },
        new mcp.data.SqlSafety(mcpConfig.sqlPiiDenylist()),
        mcpConfig.sqlTimeoutSeconds(),
        mcpConfig.sqlRowCap()
)
```

- [ ] **Step 7: Commit**

```bash
git add src/main/java/mcp/data/SqlSafety.java src/main/java/mcp/tools/SqlSelectTool.java src/test/java/mcp/data/SqlSafetyTest.java src/test/java/mcp/tools/SqlSelectToolTest.java src/main/java/net/server/Server.java
git commit -m "Add SqlSafety + cosmic.db.select MCP tool #minor"
```

---

## Task 20: End-to-end manual verification + docs

**Files:**
- Modify: `README.md` — add a short "MCP server" section under "Advanced concepts"

- [ ] **Step 1: Run full test suite**

Run: `./mvnw test -q`
Expected: BUILD SUCCESS, all tests pass.

- [ ] **Step 2: Run full build**

Run: `./mvnw clean package -DskipTests=false -q`
Expected: BUILD SUCCESS; `target/Cosmic-1.0-SNAPSHOT-jar-with-dependencies.jar` (or whatever the assembly produces) is generated.

- [ ] **Step 3: Edit config.yaml for live test**

Change `mcp:` block:
```yaml
mcp:
  enabled: true
  bind_addr: "127.0.0.1"
  port: 8765
  auth_token: "01234567890123456789abcd"
  ...
```

- [ ] **Step 4: Start the server**

Run: start Cosmic via your usual path (IDE, `./mvnw exec:java`, or the launch script).
Expected: log line `mcp listening on 127.0.0.1:8765 (tls=false)`.

- [ ] **Step 5: Manual tool exercise**

For each tool, run a curl from another terminal and verify the response shape. Replace IDs with real ones from your DB / WZ as needed.

```bash
TOKEN=01234567890123456789abcd
URL=http://127.0.0.1:8765/mcp

# tools/list
curl -s -X POST $URL -H "Authorization: Bearer $TOKEN" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | jq

# item describe
curl -s -X POST $URL -H "Authorization: Bearer $TOKEN" \
  -d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"cosmic.item.describe","arguments":{"id":1002357}}}' | jq

# pii rejection
curl -s -X POST $URL -H "Authorization: Bearer $TOKEN" \
  -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"cosmic.db.select","arguments":{"sql":"SELECT password FROM account"}}}' | jq
```

Expected:
- tools/list returns 13 tools.
- item describe returns the item record.
- pii rejection returns `error.code = -32602` with message containing `account.password`.

- [ ] **Step 6: Auth failure check**

```bash
curl -i -X POST http://127.0.0.1:8765/mcp -d '{}'
```
Expected: `HTTP/1.1 401 Unauthorized`.

- [ ] **Step 7: Add a section to README.md**

Edit `README.md`. After the existing "Advanced concepts" section, append:

```markdown
### MCP server

Cosmic ships with an optional in-process Model Context Protocol (MCP) server that exposes read-only research tools (item / mob / map / npc / quest / skill describe, drop & name search, script & code search, db schema & SELECT, config inspect) over HTTP for use by an MCP-aware client (e.g. Claude Code).

To enable, set `mcp.enabled: true` in `config.yaml` and provide a 16+ character `mcp.auth_token`. Bind to a LAN IP (do **not** bind to `0.0.0.0` on a public host without TLS). The server listens on the configured port (default 8765) and accepts JSON-RPC 2.0 over `POST /mcp`.

Client-side, register an HTTP MCP server with `Authorization: Bearer <token>`. See `docs/superpowers/specs/2026-05-07-cosmic-mcp-design.md` for the full spec.
```

- [ ] **Step 8: Final commit**

```bash
git add README.md
git commit -m "Document MCP server in README #minor"
```

- [ ] **Step 9: Revert config.yaml mcp.enabled to false**

Set `mcp.enabled: false` and `mcp.auth_token: ""` (or your team's preferred default) before any push to upstream.

```bash
git add config.yaml
git commit -m "Restore mcp default to disabled"
```

---

## Summary of tools after Task 20

| # | Tool | Task |
|---|---|---|
| 1 | cosmic.item.describe | 10 |
| 2 | cosmic.mob.describe | 11 |
| 3 | cosmic.map.describe | 12 |
| 4 | cosmic.npc.describe | 12 |
| 5 | cosmic.quest.describe | 12 |
| 6 | cosmic.skill.describe | 9 |
| 7 | cosmic.drop.search | 14 |
| 8 | cosmic.name.search | 13 |
| 9 | cosmic.script.find | 15 |
| 10 | cosmic.code.search | 16 |
| 11 | cosmic.db.schema | 18 |
| 12 | cosmic.db.select | 19 |
| 13 | cosmic.config.get | 17 |

All 13 spec'd tools accounted for. Tasks 1–8 build the foundation (config, JSON-RPC, dispatcher, registry, auth, transport, server, lifecycle wiring).
