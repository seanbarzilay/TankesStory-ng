package mcp.admin;

import client.command.Command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public class CommandCatalog {

    public record Entry(String name, int gmLevel, String description) {}

    private final Map<String, Command> registry;

    public CommandCatalog(Map<String, Command> registry) {
        this.registry = registry;
    }

    public Optional<Command> find(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(registry.get(name.toLowerCase()));
    }

    public List<Entry> list(String filterSubstring, Integer gmLevel) {
        List<Entry> out = new ArrayList<>();
        String filter = filterSubstring == null ? null : filterSubstring.toLowerCase();
        for (Map.Entry<String, Command> e : registry.entrySet()) {
            String name = e.getKey();
            Command cmd = e.getValue();
            if (filter != null && !name.contains(filter)) continue;
            if (gmLevel != null && cmd.getRank() != gmLevel) continue;
            String desc = cmd.getDescription() == null ? "" : cmd.getDescription();
            out.add(new Entry(name, cmd.getRank(), desc));
        }
        out.sort(Comparator.comparing(Entry::name));
        return out;
    }
}
