package mcp.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

public class DropIndex {

    private static final Logger log = LoggerFactory.getLogger(DropIndex.class);

    public record Entry(int mobId, int itemId, int min, int max, int chance, String source) {}

    private final Map<Integer, List<Entry>> byMob = new HashMap<>();
    private final Map<Integer, List<Entry>> byItem = new HashMap<>();

    public void add(Entry e) {
        byMob.computeIfAbsent(e.mobId(), k -> new ArrayList<>()).add(e);
        byItem.computeIfAbsent(e.itemId(), k -> new ArrayList<>()).add(e);
    }

    public List<Entry> byMob(int mobId) {
        return byMob.getOrDefault(mobId, List.of());
    }

    public List<Entry> byItem(int itemId) {
        return byItem.getOrDefault(itemId, List.of());
    }

    public static DropIndex loadFrom(Supplier<Connection> conSupplier) {
        DropIndex idx = new DropIndex();
        try (Connection con = conSupplier.get()) {
            loadTable(con, idx, "drop_data",
                    "SELECT dropperid, itemid, minimum_quantity, maximum_quantity, chance FROM drop_data");
            loadTable(con, idx, "drop_data_global",
                    "SELECT 0 AS dropperid, itemid, minimum_quantity, maximum_quantity, chance FROM drop_data_global");
            loadTable(con, idx, "reactordrops",
                    "SELECT reactorid AS dropperid, itemid, 1 AS minimum_quantity, 1 AS maximum_quantity, chance FROM reactordrops");
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
