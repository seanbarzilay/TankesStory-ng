package mcp.admin;

import mcp.data.SqlSafety;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.update.Update;

import java.util.List;
import java.util.Locale;
import java.util.Set;

public class WriteSqlSafety {

    public enum Kind { UPDATE, INSERT, DELETE }

    private final SqlSafety piiSafety;
    private final Set<String> writableTables;

    public WriteSqlSafety(SqlSafety piiSafety, List<String> writableTables) {
        this.piiSafety = piiSafety;
        this.writableTables = Set.copyOf(writableTables.stream()
                .map(t -> t.toLowerCase(Locale.ROOT)).toList());
    }

    public Kind check(String sql) throws SqlSafety.UnsafeSqlException {
        if (sql == null || sql.isBlank()) throw new SqlSafety.UnsafeSqlException("empty sql");
        Statements stmts;
        try {
            stmts = CCJSqlParserUtil.parseStatements(sql);
        } catch (JSQLParserException e) {
            throw new SqlSafety.UnsafeSqlException("parse error: " + e.getMessage());
        }
        if (stmts.getStatements().size() != 1) {
            throw new SqlSafety.UnsafeSqlException("only single statement allowed");
        }
        Statement s = stmts.getStatements().get(0);
        Kind kind;
        String table;
        if (s instanceof Update u) {
            kind = Kind.UPDATE;
            table = u.getTable().getName();
        } else if (s instanceof Insert ins) {
            kind = Kind.INSERT;
            table = ins.getTable().getName();
        } else if (s instanceof Delete d) {
            kind = Kind.DELETE;
            table = d.getTable().getName();
        } else {
            throw new SqlSafety.UnsafeSqlException("db.execute is for UPDATE/INSERT/DELETE; use db.select for reads");
        }
        if (!writableTables.contains(table.toLowerCase(Locale.ROOT))) {
            throw new SqlSafety.UnsafeSqlException("table not writable: " + table);
        }
        String normalized = s.toString().toLowerCase(Locale.ROOT);
        for (String denied : piiDeniedColumnNames()) {
            if (denied.isEmpty()) continue;
            if (containsWordOrQualified(normalized, denied)) {
                throw new SqlSafety.UnsafeSqlException("denied column: " + denied);
            }
        }
        return kind;
    }

    private List<String> piiDeniedColumnNames() {
        return SqlSafetyAccess.getDeniedColumnList(piiSafety);
    }

    private static boolean containsWordOrQualified(String haystack, String column) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "(?:\\b|\\.)" + java.util.regex.Pattern.quote(column) + "\\b");
        return p.matcher(haystack).find();
    }

    /**
     * Reflection accessor for SqlSafety.denied — keeps Slice 3 self-contained.
     * If the field name changes, this throws at first use, which is loud enough.
     */
    static class SqlSafetyAccess {
        static List<String> getDeniedColumnList(SqlSafety safety) {
            try {
                java.lang.reflect.Field f = SqlSafety.class.getDeclaredField("denied");
                f.setAccessible(true);
                @SuppressWarnings("unchecked")
                List<Object> denied = (List<Object>) f.get(safety);
                List<String> out = new java.util.ArrayList<>();
                for (Object dc : denied) {
                    java.lang.reflect.Method m = dc.getClass().getDeclaredMethod("qualified");
                    m.setAccessible(true);
                    String q = (String) m.invoke(dc);
                    String[] parts = q.split("\\.");
                    if (parts.length == 2) out.add(parts[1]);
                }
                return out;
            } catch (Exception e) {
                throw new RuntimeException("WriteSqlSafety could not read SqlSafety.denied — has the field changed?", e);
            }
        }
    }
}
