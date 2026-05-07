package mcp.edit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import mcp.protocol.JsonRpc;
import mcp.protocol.McpError;
import mcp.tools.Tool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public class FileEditEngine {

    @FunctionalInterface
    public interface ContentValidator {
        void validate(String content) throws Tool.ToolException;
    }

    public ObjectNode apply(Path resolvedPath, String displayPath, JsonNode args, ContentValidator validator) throws Tool.ToolException {
        boolean hasFindReplace = args.has("old_string") || args.has("new_string");
        boolean hasContent = args.has("content");
        if (hasFindReplace && hasContent) {
            throw new Tool.ToolException(McpError.INVALID_PARAMS, "provide either find-replace fields or content, not both");
        }
        if (!hasFindReplace && !hasContent) {
            throw new Tool.ToolException(McpError.INVALID_PARAMS, "provide either {old_string,new_string} or content");
        }
        boolean dryRun = args.has("dry_run") && args.get("dry_run").asBoolean();

        if (!EditLock.INSTANCE.tryAcquire()) {
            throw new Tool.ToolException(McpError.SERVER_SHUTTING_DOWN, "edit_busy");
        }
        try {
            String oldContent = null;
            boolean exists = Files.exists(resolvedPath);
            if (exists) {
                try {
                    oldContent = Files.readString(resolvedPath, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new Tool.ToolException(McpError.INTERNAL_ERROR, "read failed: " + e.getMessage());
                }
            }

            String newContent;
            if (hasContent) {
                newContent = args.get("content").asText();
            } else {
                if (!exists) {
                    throw new Tool.ToolException(McpError.INVALID_PARAMS, "no such file: " + displayPath);
                }
                String oldStr = args.get("old_string").asText();
                String newStr = args.get("new_string").asText();
                boolean replaceAll = args.has("replace_all") && args.get("replace_all").asBoolean();
                int idx = oldContent.indexOf(oldStr);
                if (idx < 0) {
                    throw new Tool.ToolException(McpError.INVALID_PARAMS, "old_string not found in " + displayPath);
                }
                if (!replaceAll) {
                    int second = oldContent.indexOf(oldStr, idx + 1);
                    if (second >= 0) {
                        int count = countOccurrences(oldContent, oldStr);
                        throw new Tool.ToolException(McpError.INVALID_PARAMS,
                                "old_string matches " + count + " times in " + displayPath + "; pass replace_all=true or expand context");
                    }
                    newContent = oldContent.substring(0, idx) + newStr + oldContent.substring(idx + oldStr.length());
                } else {
                    newContent = oldContent.replace(oldStr, newStr);
                }
            }

            if (validator != null) {
                validator.validate(newContent);
            }

            String diff = DiffBuilder.unified(displayPath, oldContent, newContent);

            ObjectNode out = JsonRpc.MAPPER.createObjectNode();
            out.put("path", displayPath);
            out.put("mode", dryRun ? "preview" : "applied");
            out.put("diff", diff);
            out.put("created", !exists && !dryRun);

            if (!dryRun) {
                try {
                    Files.createDirectories(resolvedPath.getParent());
                    Path tmp = Files.createTempFile(resolvedPath.getParent(), ".mcp-edit-", ".tmp");
                    Files.writeString(tmp, newContent, StandardCharsets.UTF_8);
                    Files.move(tmp, resolvedPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException e) {
                    throw new Tool.ToolException(McpError.INTERNAL_ERROR, "write failed: " + e.getMessage());
                }
            }
            return out;
        } finally {
            EditLock.INSTANCE.release();
        }
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int i = haystack.indexOf(needle, from);
            if (i < 0) return count;
            count++;
            from = i + needle.length();
        }
    }
}
