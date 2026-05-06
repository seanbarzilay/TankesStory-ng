package mcp.tools;

import client.Skill;
import client.SkillFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.protocol.JsonRpc;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import server.life.Element;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class SkillToolTest {

    @Test
    void call_unknownId_throwsInvalidParams() {
        SkillTool tool = new SkillTool();
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("id", 9999999);

        try (MockedStatic<SkillFactory> mocked = mockStatic(SkillFactory.class)) {
            mocked.when(() -> SkillFactory.getSkill(9999999)).thenReturn(null);
            Tool.ToolException ex = assertThrows(Tool.ToolException.class, () -> tool.call(args));
            assertEquals(-32602, ex.code());
            assertTrue(ex.getMessage().contains("9999999"));
        }
    }

    @Test
    void call_known_returnsDescription() throws Exception {
        Skill skill = mock(Skill.class);
        when(skill.getId()).thenReturn(1121006);
        when(skill.getMaxLevel()).thenReturn(30);
        when(skill.getElement()).thenReturn(Element.NEUTRAL);

        SkillTool tool = new SkillTool();
        ObjectNode args = JsonRpc.MAPPER.createObjectNode();
        args.put("id", 1121006);

        try (MockedStatic<SkillFactory> mocked = mockStatic(SkillFactory.class)) {
            mocked.when(() -> SkillFactory.getSkill(1121006)).thenReturn(skill);
            JsonNode out = tool.call(args);
            assertEquals(1121006, out.get("id").asInt());
            assertEquals(112, out.get("job").asInt()); // 1121006 / 10000
            assertEquals(30, out.get("maxLevel").asInt());
            assertEquals("NEUTRAL", out.get("element").asText());
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
