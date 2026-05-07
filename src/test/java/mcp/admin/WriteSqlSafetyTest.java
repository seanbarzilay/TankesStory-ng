package mcp.admin;

import mcp.data.SqlSafety;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WriteSqlSafetyTest {

    private final WriteSqlSafety safety = new WriteSqlSafety(
            new SqlSafety(List.of("account.password")),
            List.of("characters", "inventoryitems"));

    @Test
    void check_validUpdate_passes() throws Exception {
        WriteSqlSafety.Kind k = safety.check("UPDATE characters SET level = 50 WHERE id = 1");
        assertEquals(WriteSqlSafety.Kind.UPDATE, k);
    }

    @Test
    void check_validInsert_passes() throws Exception {
        WriteSqlSafety.Kind k = safety.check("INSERT INTO inventoryitems (id) VALUES (1)");
        assertEquals(WriteSqlSafety.Kind.INSERT, k);
    }

    @Test
    void check_validDelete_passes() throws Exception {
        WriteSqlSafety.Kind k = safety.check("DELETE FROM inventoryitems WHERE id = 1");
        assertEquals(WriteSqlSafety.Kind.DELETE, k);
    }

    @Test
    void check_select_rejected() {
        SqlSafety.UnsafeSqlException ex = assertThrows(SqlSafety.UnsafeSqlException.class,
                () -> safety.check("SELECT * FROM characters"));
        assertTrue(ex.getMessage().contains("for UPDATE/INSERT/DELETE"));
    }

    @Test
    void check_tableNotInAllowlist_rejected() {
        SqlSafety.UnsafeSqlException ex = assertThrows(SqlSafety.UnsafeSqlException.class,
                () -> safety.check("UPDATE accounts SET name = 'x'"));
        assertTrue(ex.getMessage().contains("table not writable"));
    }

    @Test
    void check_piiColumnReferenced_rejected() {
        SqlSafety.UnsafeSqlException ex = assertThrows(SqlSafety.UnsafeSqlException.class,
                () -> safety.check("UPDATE characters SET level = 50, password = 'x' WHERE id = 1"));
        assertTrue(ex.getMessage().contains("denied column"));
    }

    @Test
    void check_emptyAllowlist_rejected() {
        WriteSqlSafety empty = new WriteSqlSafety(new SqlSafety(List.of()), List.of());
        assertThrows(SqlSafety.UnsafeSqlException.class,
                () -> empty.check("UPDATE characters SET level = 50"));
    }

    @Test
    void check_multiStatement_rejected() {
        assertThrows(SqlSafety.UnsafeSqlException.class,
                () -> safety.check("UPDATE characters SET level = 1; DELETE FROM characters"));
    }
}
