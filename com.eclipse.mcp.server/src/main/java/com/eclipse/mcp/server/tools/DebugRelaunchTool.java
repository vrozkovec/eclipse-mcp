package com.eclipse.mcp.server.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;
import org.eclipse.ui.PlatformUI;

/**
 * Stops any currently running program and relaunches the most recently used
 * launch configuration in debug mode.
 *
 * <p>Only Java application launches are terminated — external tools, remote debug
 * sessions, and other non-Java launches are left untouched.</p>
 *
 * <p>Optional per-run overrides ({@code vmArguments}, {@code programArguments},
 * {@code environment}) are applied to an unsaved working copy of the configuration, so
 * they affect this launch only: the saved configuration is never modified, and a later
 * relaunch without overrides uses the saved settings again.</p>
 *
 * <p>Equivalent to manually terminating Java processes (Ctrl+F2)
 * and then pressing F11 (Debug Last Launched) in Eclipse.</p>
 */
public class DebugRelaunchTool implements Tool {

    @Override
    public Object execute(Map<String, Object> arguments) throws Exception {
        // Validated before the UI-thread call so bad input surfaces as a plain error message
        RunOverrides overrides = RunOverrides.from(arguments);
        return PlatformUI.getWorkbench().getDisplay().syncCall(() -> {
            try {
                return stopAndRelaunchDebug(arguments, overrides);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private Map<String, Object> stopAndRelaunchDebug(Map<String, Object> arguments, RunOverrides overrides)
            throws Exception {
        ILaunchManager manager = DebugPlugin.getDefault().getLaunchManager();
        ILaunch[] launches = manager.getLaunches();

        // Find the most recent Java launch configuration from launch history.
        // getLaunches() includes both active and terminated launches from the session,
        // ordered by creation time — the last entry with a config is the most recent.
        ILaunchConfiguration lastConfig = null;
        for (ILaunch launch : launches) {
            if (launch.getLaunchConfiguration() != null && isJavaLaunch(launch)) {
                lastConfig = savedConfiguration(launch.getLaunchConfiguration());
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
        ILaunchConfiguration toLaunch = overrides.isEmpty() ? lastConfig : overrides.applyTo(lastConfig);
        ILaunch newLaunch = toLaunch.launch(ILaunchManager.DEBUG_MODE, monitor);

        Map<String, Object> result = new HashMap<>();
        result.put("status", "launched");
        result.put("terminatedCount", terminated.size());
        result.put("terminatedLaunches", terminated);
        result.put("launchConfiguration", lastConfig.getName());
        result.put("launchMode", ILaunchManager.DEBUG_MODE);
        result.put("launchType", lastConfig.getType().getName());
        if (!overrides.isEmpty()) {
            result.put("overrides", overrides.describe());
        }

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

    /**
     * Returns the saved configuration behind a launch. A launch started with per-run overrides
     * runs an unsaved working copy; relaunching must go back to the saved original so that
     * the overrides do not stick.
     */
    private static ILaunchConfiguration savedConfiguration(ILaunchConfiguration config) {
        if (config instanceof ILaunchConfigurationWorkingCopy workingCopy && workingCopy.getOriginal() != null) {
            return workingCopy.getOriginal();
        }
        return config;
    }

    /**
     * One-off launch attributes for a single run.
     *
     * @param vmArguments      JVM arguments appended after the configuration's own, or {@code null}
     * @param programArguments program arguments appended after the configuration's own, or {@code null}
     * @param environment      environment variables merged over the configuration's own, never {@code null}
     */
    private record RunOverrides(String vmArguments, String programArguments, Map<String, String> environment) {

        /**
         * Reads the optional {@code vmArguments}, {@code programArguments} and {@code environment}
         * tool arguments.
         *
         * @throws IllegalArgumentException if one of them has the wrong type
         */
        static RunOverrides from(Map<String, Object> arguments) {
            return new RunOverrides(
                    optionalString(arguments, "vmArguments"),
                    optionalString(arguments, "programArguments"),
                    optionalEnvironment(arguments));
        }

        boolean isEmpty() {
            return vmArguments == null && programArguments == null && environment.isEmpty();
        }

        /**
         * Creates a working copy of the configuration with the overrides applied. The copy is
         * deliberately never saved: the .launch file stays untouched, and Eclipse's launch
         * history ignores working copies, so F11 keeps launching the saved configuration.
         */
        ILaunchConfiguration applyTo(ILaunchConfiguration config) throws CoreException {
            ILaunchConfigurationWorkingCopy workingCopy = config.getWorkingCopy();
            append(workingCopy, IJavaLaunchConfigurationConstants.ATTR_VM_ARGUMENTS, vmArguments);
            append(workingCopy, IJavaLaunchConfigurationConstants.ATTR_PROGRAM_ARGUMENTS, programArguments);
            if (!environment.isEmpty()) {
                Map<String, String> merged = new HashMap<>(
                        workingCopy.getAttribute(ILaunchManager.ATTR_ENVIRONMENT_VARIABLES, Map.of()));
                merged.putAll(environment);
                workingCopy.setAttribute(ILaunchManager.ATTR_ENVIRONMENT_VARIABLES, merged);
            }
            return workingCopy;
        }

        /**
         * Describes the applied overrides for the tool result.
         */
        Map<String, Object> describe() {
            Map<String, Object> description = new LinkedHashMap<>();
            if (vmArguments != null) {
                description.put("vmArguments", vmArguments);
            }
            if (programArguments != null) {
                description.put("programArguments", programArguments);
            }
            if (!environment.isEmpty()) {
                description.put("environment", environment);
            }
            return description;
        }

        /**
         * Appends extra arguments after a configuration's own. The JVM honours the last
         * occurrence of a {@code -D} or {@code -X} option, so appending also overrides saved values.
         */
        private static void append(ILaunchConfigurationWorkingCopy workingCopy, String attribute, String extra)
                throws CoreException {
            if (extra == null) {
                return;
            }
            String saved = workingCopy.getAttribute(attribute, "").strip();
            workingCopy.setAttribute(attribute, saved.isEmpty() ? extra : saved + " " + extra);
        }

        private static String optionalString(Map<String, Object> arguments, String key) {
            Object value = arguments.get(key);
            if (value == null) {
                return null;
            }
            if (!(value instanceof String string)) {
                throw new IllegalArgumentException(key + " must be a string");
            }
            return string.isBlank() ? null : string.strip();
        }

        private static Map<String, String> optionalEnvironment(Map<String, Object> arguments) {
            Object value = arguments.get("environment");
            if (value == null) {
                return Map.of();
            }
            if (!(value instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("environment must be an object of NAME: value pairs");
            }
            Map<String, String> environment = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getValue() == null) {
                    throw new IllegalArgumentException("environment value of " + entry.getKey() + " must not be null");
                }
                environment.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
            return environment;
        }
    }
}
