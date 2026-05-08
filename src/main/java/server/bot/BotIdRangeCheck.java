package server.bot;

import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Boot-time sanity check that the synthetic-id range used for player-bots
 * (everything {@code <= BotIdAllocator.START}) does not collide with any
 * existing row in the {@code characters} table.
 */
public class BotIdRangeCheck {
    public static void run() {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT MIN(id) FROM characters");
             ResultSet rs = ps.executeQuery()) {
            if (rs.next()) {
                int min = rs.getInt(1);
                if (!rs.wasNull() && min <= BotIdAllocator.START) {
                    throw new IllegalStateException(
                            "characters.id range collides with bot synthetic id range (min="
                                    + min + ")");
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("BotIdRangeCheck failed", e);
        }
    }
}
