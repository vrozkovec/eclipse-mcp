package com.eclipse.mcp.server.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.ui.PlatformUI;

/**
 * Stops any currently running program and relaunches the most recently used
 * launch configuration in debug mode.
 *
 * <p>Only Java application launches are terminated — external tools, remote debug
 * sessions, and other non-Java launches are left untouched.</p>
 *
 * <p>Equivalent to manually terminating Java processes (Ctrl+F2)
 * and then pressing F11 (Debug Last Launched) in Eclipse.</p>
 */
public class DebugRelaunchTool implements Tool {

    @Override
    public Object execute(Map<String, Object> arguments) throws Exception {
        return PlatformUI.getWorkbench().getDisplay().syncCall(() -> {
            try {
                return stopAndRelaunchDebug(arguments);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private Map<String, Object> stopAndRelaunchDebug(Map<String, Object> arguments) throws Exception {
        ILaunchManager manager = DebugPlugin.getDefault().getLaunchManager();
        ILaunch[] launches = manager.getLaunches();

        // Find the most recent Java launch configuration from launch history.
        // getLaunches() includes both active and terminated launches from the session,
        // ordered by creation time — the last entry with a config is the most recent.
        ILaunchConfiguration lastConfig = null;
        for (ILaunch launch : launches) {
            if (launch.getLaunchConfiguration() != null && isJavaLaunch(launch)) {
                lastConfig = launch.getLaunchConfiguration();
            }
        }

        // Allow explicit override via optional configurationName argument
        String configName = (String) arguments.get("configurationName");
        if (configName != null && !configName.isBlank()) {
            ILaunchConfiguration[] allConfigs = manager.getLaunchConfigurations();
            ILaunchConfiguration found = null;
            for (ILaunchConfiguration config : allConfigs) {
                if (config.getName().equals(configName)) {
                    found = config;
                    break;
                }
            }
            if (found == null) {
                Map<String, Object> error = new HashMap<>();
                error.put("status", "error");
                error.put("error", "Launch configuration not found: " + configName);
                error.put("availableConfigurations", getConfigurationNames(manager));
                return error;
            }
            lastConfig = found;
        }

        if (lastConfig == null || !lastConfig.exists()) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("error", "No launch configuration found. Either no program has been launched in this session, or specify configurationName.");
            error.put("availableConfigurations", getConfigurationNames(manager));
            return error;
        }

        // Terminate only active Java launches — leave external tools and other launches alone
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

        // Relaunch in debug mode
        var monitor = new NullProgressMonitor();
        ILaunch newLaunch = lastConfig.launch(ILaunchManager.DEBUG_MODE, monitor);

        Map<String, Object> result = new HashMap<>();
        result.put("status", "launched");
        result.put("terminatedCount", terminated.size());
        result.put("terminatedLaunches", terminated);
        result.put("launchConfiguration", lastConfig.getName());
        result.put("launchMode", ILaunchManager.DEBUG_MODE);
        result.put("launchType", lastConfig.getType().getName());

        List<String> processes = new ArrayList<>();
        if (newLaunch.getProcesses() != null) {
            for (var process : newLaunch.getProcesses()) {
                processes.add(process.getLabel());
            }
        }
        result.put("processes", processes);

        return result;
    }

    private static final String JAVA_LAUNCH_PREFIX = "org.eclipse.jdt.launching.";
    private static final String JUNIT_LAUNCH_TYPE = "org.eclipse.jdt.junit.launchconfig";

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

    /**
     * Returns names of all available launch configurations for error messages.
     */
    private List<String> getConfigurationNames(ILaunchManager manager) throws Exception {
        List<String> names = new ArrayList<>();
        for (ILaunchConfiguration config : manager.getLaunchConfigurations()) {
            names.add(config.getName());
        }
        return names;
    }
}
