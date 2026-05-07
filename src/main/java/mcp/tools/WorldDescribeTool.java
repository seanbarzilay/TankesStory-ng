package mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.protocol.JsonRpc;

import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

public class WorldDescribeTool implements Tool {

    public record WorldStats(int id, String name, int channels, int onlineCount, int expRate, int mesoRate, int dropRate) {}

    private final LongSupplier uptimeSeconds;
    private final Supplier<List<WorldStats>> worldsSupplier;

    public WorldDescribeTool(LongSupplier uptimeSeconds, Supplier<List<WorldStats>> worldsSupplier) {
        this.uptimeSeconds = uptimeSeconds;
        this.worldsSupplier = worldsSupplier;
    }

    @Override
    public String name() { return "cosmic.admin.world.describe"; }

    @Override
    public String description() { return "Describe Cosmic worlds: uptime, channel count, online count, rates."; }

    @Override
    public ObjectNode inputSchema() {
        ObjectNode root = JsonRpc.MAPPER.createObjectNode();
        root.put("type", "object");
        root.putObject("properties");
        root.put("additionalProperties", false);
        return root;
    }

    @Override
    public JsonNode call(JsonNode args) {
        ObjectNode out = JsonRpc.MAPPER.createObjectNode();
        out.put("uptime_seconds", uptimeSeconds.getAsLong());
        ArrayNode arr = out.putArray("worlds");
        for (WorldStats w : worldsSupplier.get()) {
            ObjectNode wn = arr.addObject();
            wn.put("id", w.id());
            wn.put("name", w.name());
            wn.put("channels", w.channels());
            wn.put("online_count", w.onlineCount());
            wn.put("exp_rate", w.expRate());
            wn.put("meso_rate", w.mesoRate());
            wn.put("drop_rate", w.dropRate());
        }
        return out;
    }
}
