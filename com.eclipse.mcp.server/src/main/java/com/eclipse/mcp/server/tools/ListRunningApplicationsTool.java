package com.eclipse.mcp.server.tools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.ui.PlatformUI;

/**
 * Lists the Java applications currently running from Eclipse — local Java applications, JUnit runs
 * and remote debug sessions — with configuration name, launch type and mode, process id, start time
 * and the file their console output is written to. Read-only: nothing is started or stopped.
 */
public class ListRunningApplicationsTool implements Tool {

    @Override
    public Object execute(Map<String, Object> arguments) throws Exception {
        return PlatformUI.getWorkbench().getDisplay().syncCall(this::listRunningApplications);
    }

    private Map<String, Object> listRunningApplications() {
        List<Map<String, Object>> applications = JavaLaunches.running().stream()
                .map(JavaLaunches::describe)
                .toList();

        Map<String, Object> result = new HashMap<>();
        result.put("applications", applications);
        result.put("totalCount", applications.size());
        return result;
    }
}
