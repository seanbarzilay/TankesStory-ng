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
    public String description() { return "Describe a Cosmic map by ID (name, portals, returnMap)."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        ObjectNode id = root.putObject("properties").putObject("id");
        id.put("type", "integer");
        id.put("description", "Map ID (e.g. 100000000).");
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
