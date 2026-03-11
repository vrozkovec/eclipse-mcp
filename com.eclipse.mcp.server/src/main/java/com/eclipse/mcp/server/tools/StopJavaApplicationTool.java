package com.eclipse.mcp.server.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.ui.PlatformUI;

/**
 * Stops all currently running Java applications in the workspace.
 *
 * <p>Only Java application launches are terminated — external tools, remote debug
 * sessions, and other non-Java launches are left untouched. Equivalent to
 * selecting all Java processes and pressing Ctrl+F2 (Terminate) in Eclipse.</p>
 */
public class StopJavaApplicationTool implements Tool {

    private static final String JAVA_LAUNCH_PREFIX = "org.eclipse.jdt.launching.";
    private static final String JUNIT_LAUNCH_TYPE = "org.eclipse.jdt.junit.launchconfig";

    @Override
    public Object execute(Map<String, Object> arguments) throws Exception {
        return PlatformUI.getWorkbench().getDisplay().syncCall(() -> {
            try {
                return stopJavaApplications();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private Map<String, Object> stopJavaApplications() throws Exception {
        ILaunchManager manager = DebugPlugin.getDefault().getLaunchManager();
        ILaunch[] launches = manager.getLaunches();

        // Terminate only active Java launches
        List<String> terminated = new ArrayList<>();
        for (ILaunch launch : launches) {
            if (!launch.isTerminated() && isJavaLaunch(launch)) {
                String label = launch.getLaunchConfiguration() != null
                        ? launch.getLaunchConfiguration().getName()
                        : "unknown";
                launch.terminate();
                terminated.add(label);
            }
        }

        // Remove terminated Java launches from the debug view
        ILaunch[] currentLaunches = manager.getLaunches();
        List<ILaunch> toRemove = new ArrayList<>();
        for (ILaunch launch : currentLaunches) {
            if (launch.isTerminated() && isJavaLaunch(launch)) {
                toRemove.add(launch);
            }
        }
        if (!toRemove.isEmpty()) {
            manager.removeLaunches(toRemove.toArray(new ILaunch[0]));
        }

        Map<String, Object> result = new HashMap<>();
        result.put("status", terminated.isEmpty() ? "no_java_applications_running" : "terminated");
        result.put("terminatedCount", terminated.size());
        result.put("terminatedLaunches", terminated);
        return result;
    }

    /**
     * Checks whether a launch is a Java application (local Java app, JUnit, etc.).
     */
    private boolean isJavaLaunch(ILaunch launch) {
        try {
            ILaunchConfiguration config = launch.getLaunchConfiguration();
            if (config == null) {
                return false;
            }
            String typeId = config.getType().getIdentifier();
            return typeId.startsWith(JAVA_LAUNCH_PREFIX) || typeId.equals(JUNIT_LAUNCH_TYPE);
        } catch (CoreException e) {
            return false;
        }
    }
}
