package mcp.edit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class GitRunner {

    private final Path workingDir;

    public GitRunner(Path workingDir) {
        this.workingDir = workingDir;
    }

    public Result run(String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>(args.length + 1);
        cmd.add("git");
        for (String a : args) cmd.add(a);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(workingDir.toFile()).redirectErrorStream(false);
        Process p = pb.start();
        boolean done = p.waitFor(60, TimeUnit.SECONDS);
        if (!done) {
            p.destroyForcibly();
            return new Result(-1, "", "git timed out");
        }
        String stdout = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(p.exitValue(), stdout, stderr);
    }

    public record Result(int exitCode, String stdout, String stderr) {}
}
