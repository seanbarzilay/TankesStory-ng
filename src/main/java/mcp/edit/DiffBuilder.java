package mcp.edit;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;

import java.util.Arrays;
import java.util.List;

public final class DiffBuilder {

    private static final int CONTEXT_LINES = 3;

    private DiffBuilder() {}

    public static String unified(String path, String oldContent, String newContent) {
        if (oldContent != null && oldContent.equals(newContent)) return "";
        List<String> oldLines = oldContent == null ? List.of() : Arrays.asList(oldContent.split("\n", -1));
        List<String> newLines = newContent == null ? List.of() : Arrays.asList(newContent.split("\n", -1));
        Patch<String> patch = DiffUtils.diff(oldLines, newLines);
        if (patch.getDeltas().isEmpty()) return "";
        String fromPath = oldContent == null ? "/dev/null" : path;
        String toPath = newContent == null ? "/dev/null" : path;
        List<String> unified = UnifiedDiffUtils.generateUnifiedDiff(fromPath, toPath, oldLines, patch, CONTEXT_LINES);
        return String.join("\n", unified) + "\n";
    }
}
