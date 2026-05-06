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
