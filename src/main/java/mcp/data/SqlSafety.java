package mcp.data;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.Select;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

public class SqlSafety {

    private final List<DeniedColumn> denied;

    public SqlSafety(List<String> piiDenylist) {
        this.denied = new ArrayList<>();
        for (String entry : piiDenylist) {
            String lower = entry.toLowerCase(Locale.ROOT);
            String[] parts = lower.split("\\.");
            if (parts.length == 2) {
                String column = parts[1];
                // Match either "column" alone or "tab.column" with word boundaries
                Pattern p = Pattern.compile(
                        "(?:\\b|\\.)" + Pattern.quote(column) + "\\b",
                        Pattern.CASE_INSENSITIVE);
                denied.add(new DeniedColumn(lower, p));
            }
        }
    }

    public void check(String sql) throws UnsafeSqlException {
        if (sql == null || sql.isBlank()) throw new UnsafeSqlException("empty sql");
        Statements stmts;
        try {
            stmts = CCJSqlParserUtil.parseStatements(sql);
        } catch (JSQLParserException e) {
            throw new UnsafeSqlException("parse error: " + e.getMessage());
        }
        if (stmts.getStatements().size() != 1) {
            throw new UnsafeSqlException("only single SELECT allowed");
        }
        Statement s = stmts.getStatements().get(0);
        if (!(s instanceof Select)) {
            throw new UnsafeSqlException("only single SELECT allowed");
        }
        // Re-render the parsed statement to a normalized form for PII matching.
        String normalized = s.toString();
        for (DeniedColumn dc : denied) {
            if (dc.pattern.matcher(normalized).find()) {
                throw new UnsafeSqlException("denied column: " + dc.qualified);
            }
        }
    }

    public static class UnsafeSqlException extends Exception {
        public UnsafeSqlException(String msg) { super(msg); }
    }

    private record DeniedColumn(String qualified, Pattern pattern) {}
}
