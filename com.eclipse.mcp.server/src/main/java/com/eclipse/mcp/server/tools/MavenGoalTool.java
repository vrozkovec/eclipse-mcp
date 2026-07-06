package com.eclipse.mcp.server.tools;

import java.util.List;
import java.util.Map;

/**
 * Runs Maven goals on a workspace project through an m2e launch — the equivalent of
 * "Run As &gt; Maven build" (goals {@code ["clean"]} matches the "Maven clean" launch
 * shortcut). Blocks until the external Maven process finishes or the timeout elapses,
 * then reports the exit code, BUILD SUCCESS/FAILURE detection and the output tail.
 * The heavy lifting is delegated to {@link MavenLauncher}.
 */
public class MavenGoalTool implements Tool {

    /** Default maximum time to wait for the Maven process, in seconds. */
    private static final long DEFAULT_TIMEOUT_SECONDS = 300L;

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(Map<String, Object> arguments) throws Exception {
        String projectName = (String) arguments.get("projectName");
        List<String> goals = (List<String>) arguments.get("goals");
        long timeoutSeconds = arguments.get("timeoutSeconds") instanceof Number number
                ? number.longValue()
                : DEFAULT_TIMEOUT_SECONDS;

        if (projectName == null || projectName.trim().isEmpty()) {
            throw new IllegalArgumentException("projectName is required");
        }
        if (goals == null || goals.isEmpty()) {
            throw new IllegalArgumentException("goals are required");
        }

        return MavenLauncher.run(projectName, goals, timeoutSeconds);
    }
}
