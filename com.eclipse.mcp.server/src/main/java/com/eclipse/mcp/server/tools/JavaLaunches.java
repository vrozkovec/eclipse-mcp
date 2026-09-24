package com.eclipse.mcp.server.tools;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.variables.VariablesPlugin;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.ui.IDebugUIConstants;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;

/**
 * Finds, describes and terminates the Java launches of the workspace — local Java applications,
 * JUnit runs and remote debug sessions — for the application lifecycle tools.
 *
 * <p>A remote debug session ("Remote Java Application") attaches to a VM that Eclipse did not start.
 * The tools leave such sessions alone when they stop "everything", and stop one only when it is
 * named explicitly.</p>
 *
 * <p>All methods must be called on the UI thread.</p>
 */
final class JavaLaunches {

    private static final String JAVA_LAUNCH_PREFIX = "org.eclipse.jdt.launching.";
    private static final String JUNIT_LAUNCH_TYPE = "org.eclipse.jdt.junit.launchconfig";

    private static final DateTimeFormatter LAUNCHED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private JavaLaunches() {
    }

    /**
     * Checks whether a launch is a Java launch: a local Java application, a JUnit run or a remote
     * debug session.
     *
     * @param launch the launch to check
     * @return {@code true} for a Java launch
     */
    static boolean isJavaLaunch(ILaunch launch) {
        String typeId = typeId(launch.getLaunchConfiguration());
        return typeId != null && (typeId.startsWith(JAVA_LAUNCH_PREFIX) || typeId.equals(JUNIT_LAUNCH_TYPE));
    }

    /**
     * Checks whether a launch is a remote debug session, which attaches to a VM Eclipse did not start.
     *
     * @param launch the launch to check
     * @return {@code true} for a "Remote Java Application" launch
     */
    static boolean isRemoteDebugSession(ILaunch launch) {
        return isRemoteDebugConfiguration(launch.getLaunchConfiguration());
    }

    /**
     * Checks whether a launch configuration is a "Remote Java Application", which starts no process
     * of its own.
     *
     * @param config the launch configuration, may be {@code null}
     * @return {@code true} for a remote debug configuration
     */
    static boolean isRemoteDebugConfiguration(ILaunchConfiguration config) {
        return IJavaLaunchConfigurationConstants.ID_REMOTE_JAVA_APPLICATION.equals(typeId(config));
    }

    /**
     * Returns the running Java launches in launch order, remote debug sessions included.
     *
     * @return the Java launches that have not terminated yet
     */
    static List<ILaunch> running() {
        List<ILaunch> running = new ArrayList<>();
        for (ILaunch launch : launchManager().getLaunches()) {
            if (!launch.isTerminated() && isJavaLaunch(launch)) {
                running.add(launch);
            }
        }
        return running;
    }

    /**
     * Returns the name of the launch configuration behind a launch. A launch started with one-off
     * overrides runs an unsaved working copy, which keeps the saved configuration's name.
     *
     * @param launch the launch
     * @return the configuration name, or {@code "unknown"} for a launch without configuration
     */
    static String configurationName(ILaunch launch) {
        ILaunchConfiguration config = launch.getLaunchConfiguration();
        return config != null ? config.getName() : "unknown";
    }

    /**
     * Terminates the given launches.
     *
     * @param launches the launches to terminate
     * @return the configuration names of the terminated launches
     * @throws DebugException if a launch cannot be terminated
     */
    static List<String> terminate(List<ILaunch> launches) throws DebugException {
        List<String> names = new ArrayList<>();
        for (ILaunch launch : launches) {
            launch.terminate();
            names.add(configurationName(launch));
        }
        return names;
    }

    /**
     * Removes all terminated Java launches from the launch manager, which clears them from the
     * Debug view.
     */
    static void removeTerminated() {
        ILaunchManager manager = launchManager();
        List<ILaunch> terminated = new ArrayList<>();
        for (ILaunch launch : manager.getLaunches()) {
            if (launch.isTerminated() && isJavaLaunch(launch)) {
                terminated.add(launch);
            }
        }
        if (!terminated.isEmpty()) {
            manager.removeLaunches(terminated.toArray(new ILaunch[0]));
        }
    }

    /**
     * Describes a launch for tool results: {@code configurationName}, {@code launchType},
     * {@code launchMode}, {@code pid}, {@code launchedAt} and {@code logFile}. Values that are not
     * known, such as the process id of a remote debug session, are left out.
     *
     * @param launch the launch to describe
     * @return the description, in a stable key order
     */
    static Map<String, Object> describe(ILaunch launch) {
        ILaunchConfiguration config = launch.getLaunchConfiguration();
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("configurationName", configurationName(launch));
        putIfKnown(description, "launchType", typeName(config));
        putIfKnown(description, "launchMode", launch.getLaunchMode());
        putIfKnown(description, "pid", processId(launch));
        putIfKnown(description, "launchedAt", launchedAt(launch));
        putIfKnown(description, "logFile", logFile(config));
        return description;
    }

    /**
     * Returns the file a launch configuration writes its console output to (Common tab &gt; Output
     * File), with Eclipse variables such as {@code ${workspace_loc}} expanded.
     *
     * @param config the launch configuration, may be {@code null}
     * @return the file path, or {@code null} if the output is not written to a file
     */
    static String logFile(ILaunchConfiguration config) {
        if (config == null) {
            return null;
        }
        String file;
        try {
            file = config.getAttribute(IDebugUIConstants.ATTR_CAPTURE_IN_FILE, (String) null);
        } catch (CoreException e) {
            return null;
        }
        if (file == null || file.isBlank()) {
            return null;
        }
        try {
            return VariablesPlugin.getDefault().getStringVariableManager().performStringSubstitution(file);
        } catch (CoreException e) {
            // an undefined variable — report the path as configured
            return file;
        }
    }

    private static String typeId(ILaunchConfiguration config) {
        if (config == null) {
            return null;
        }
        try {
            return config.getType().getIdentifier();
        } catch (CoreException e) {
            return null;
        }
    }

    private static String typeName(ILaunchConfiguration config) {
        if (config == null) {
            return null;
        }
        try {
            return config.getType().getName();
        } catch (CoreException e) {
            return null;
        }
    }

    /**
     * Returns the operating system process id of the launch's first process that reports one.
     */
    private static Long processId(ILaunch launch) {
        for (IProcess process : launch.getProcesses()) {
            String pid = process.getAttribute(IProcess.ATTR_PROCESS_ID);
            if (pid != null) {
                try {
                    return Long.valueOf(pid);
                } catch (NumberFormatException e) {
                    return null;
                }
            }
        }
        return null;
    }

    /**
     * Returns when the launch was started, as a local ISO date-time to the second.
     */
    private static String launchedAt(ILaunch launch) {
        String timestamp = launch.getAttribute(DebugPlugin.ATTR_LAUNCH_TIMESTAMP);
        if (timestamp == null) {
            return null;
        }
        try {
            Instant instant = Instant.ofEpochMilli(Long.parseLong(timestamp));
            return LocalDateTime.ofInstant(instant, ZoneId.systemDefault()).format(LAUNCHED_AT);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void putIfKnown(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private static ILaunchManager launchManager() {
        return DebugPlugin.getDefault().getLaunchManager();
    }
}
