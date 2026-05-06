package mcp.data;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SqlSafetyTest {

    private final SqlSafety safety = new SqlSafety(List.of("account.password", "account.email"));

    @Test
    void check_simpleSelect_passes() throws Exception {
        safety.check("SELECT id, name FROM character_data WHERE accountid = ?");
    }

    @Test
    void check_insert_rejected() {
        SqlSafety.UnsafeSqlException ex = assertThrows(SqlSafety.UnsafeSqlException.class,
                () -> safety.check("INSERT INTO account VALUES (1)"));
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
