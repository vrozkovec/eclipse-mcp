package com.eclipse.mcp.server.tools;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.ui.IDebugUIConstants;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;
import org.eclipse.ui.PlatformUI;

/**
 * Relaunches a launch configuration in debug mode: the most recently launched one, or the one
 * named by {@code configurationName}.
 *
 * <p>Before launching, the running Java launches selected by {@code terminate} are stopped: by
 * default only the running instances of the relaunched configuration, so other applications keep
 * running; {@code all} stops every Java application except attached remote debug sessions, and
 * {@code none} starts the new launch alongside. External tools and other non-Java launches are
 * never touched. Without {@code configurationName} the call is refused while applications of
 * several configurations are running, because the most recently launched one may then belong to
 * another session.</p>
 *
 * <p>Every launch runs an unsaved working copy of the configuration, so the saved configuration is
 * never modified. The working copy writes the console output to a fresh per-launch log file
 * ({@link LaunchLogs}), returned as {@code logFile}, and carries the optional one-off overrides
 * ({@code vmArguments}, {@code programArguments}, {@code environment}). Eclipse's launch history
 * ignores working copies, so these launches do not change what F11 or the Run History menu
 * launch.</p>
 *
 * <p>Equivalent to terminating the application (Ctrl+F2) and pressing F11 (Debug Last Launched)
 * in Eclipse.</p>
 */
public class DebugRelaunchTool implements Tool {

    @Override
    public Object execute(Map<String, Object> arguments) throws Exception {
        // Validated before the UI-thread call so bad input surfaces as a plain error message
        RunOverrides overrides = RunOverrides.from(arguments);
        TerminateScope terminateScope = TerminateScope.from(arguments);
        return PlatformUI.getWorkbench().getDisplay().syncCall(() -> {
            try {
                return stopAndRelaunchDebug(arguments, overrides, terminateScope);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private Map<String, Object> stopAndRelaunchDebug(Map<String, Object> arguments, RunOverrides overrides,
            TerminateScope terminateScope) throws Exception {
        ILaunchManager manager = DebugPlugin.getDefault().getLaunchManager();
        ILaunch[] launches = manager.getLaunches();
        List<ILaunch> running = JavaLaunches.running();

        // Find the most recent Java launch configuration from launch history.
        // getLaunches() includes both active and terminated launches from the session,
        // ordered by creation time — the last entry with a config is the most recent.
        ILaunchConfiguration lastConfig = null;
        for (ILaunch launch : launches) {
            if (launch.getLaunchConfiguration() != null && JavaLaunches.isJavaLaunch(launch)) {
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
        } else if (running.stream().map(JavaLaunches::configurationName).distinct().count() > 1) {
            // The most recently launched configuration may be another session's application
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("error", "Applications of several launch configurations are running; specify configurationName.");
            error.put("stillRunning", describe(running));
            error.put("availableConfigurations", getConfigurationNames(manager));
            return error;
        }

        if (lastConfig == null || !lastConfig.exists()) {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("error", "No launch configuration found. Either no program has been launched in this session, or specify configurationName.");
            error.put("availableConfigurations", getConfigurationNames(manager));
            return error;
        }

        // Terminate the selected Java launches and clear terminated ones from the debug view
        List<ILaunch> toTerminate = terminateScope.select(running, lastConfig.getName());
        List<String> terminated = JavaLaunches.terminate(toTerminate);
        JavaLaunches.removeTerminated();
        // Computed rather than queried: terminate() can return before isTerminated() flips
        List<ILaunch> stillRunning = new ArrayList<>(running);
        stillRunning.removeAll(toTerminate);

        // Relaunch in debug mode from an unsaved working copy carrying this run's log file and
        // overrides. A remote debug session starts no process, so it gets no log file.
        ILaunchConfigurationWorkingCopy workingCopy = lastConfig.getWorkingCopy();
        overrides.applyTo(workingCopy);
        Path logFile = null;
        String logFileError = null;
        if (!JavaLaunches.isRemoteDebugConfiguration(lastConfig)) {
            try {
                logFile = LaunchLogs.newLogFile(lastConfig.getName());
                workingCopy.setAttribute(IDebugUIConstants.ATTR_CAPTURE_IN_FILE, logFile.toString());
                workingCopy.setAttribute(IDebugUIConstants.ATTR_APPEND_TO_FILE, false);
            } catch (IOException e) {
                // Launch anyway; the console output then goes where the configuration says
                logFileError = e.toString();
            }
        }
        ILaunch newLaunch = workingCopy.launch(ILaunchManager.DEBUG_MODE, new NullProgressMonitor());
        if (logFile != null) {
            LaunchLogs.prune(logFilesInUse(logFile, stillRunning));
        }

        Map<String, Object> result = new HashMap<>();
        result.put("status", "launched");
        result.put("terminate", terminateScope.argumentValue());
        result.put("terminatedCount", terminated.size());
        result.put("terminatedLaunches", terminated);
        result.put("launchConfiguration", lastConfig.getName());
        result.put("launchMode", ILaunchManager.DEBUG_MODE);
        result.put("launchType", lastConfig.getType().getName());
        if (logFile != null) {
            result.put("logFile", logFile.toString());
        }
        if (logFileError != null) {
            result.put("logFileError", logFileError);
        }
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
        result.put("stillRunning", describe(stillRunning));

        return result;
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

    private static List<Map<String, Object>> describe(List<ILaunch> launches) {
        return launches.stream().map(JavaLaunches::describe).toList();
    }

    /**
     * Returns the log files that pruning must keep: the new launch's and those of the applications
     * that are still running.
     */
    private static List<Path> logFilesInUse(Path newLogFile, List<ILaunch> stillRunning) {
        List<Path> inUse = new ArrayList<>();
        inUse.add(newLogFile);
        for (ILaunch launch : stillRunning) {
            String file = JavaLaunches.logFile(launch.getLaunchConfiguration());
            if (file != null) {
                inUse.add(Path.of(file));
            }
        }
        return inUse;
    }

    /**
     * Returns the saved configuration behind a launch. A launch started by this tool runs an unsaved
     * working copy; relaunching must go back to the saved original so that the previous run's log
     * file and overrides do not stick.
     */
    private static ILaunchConfiguration savedConfiguration(ILaunchConfiguration config) {
        if (config instanceof ILaunchConfigurationWorkingCopy workingCopy && workingCopy.getOriginal() != null) {
            return workingCopy.getOriginal();
        }
        return config;
    }

    /**
     * Which running Java launches are stopped before the relaunch.
     */
    private enum TerminateScope {

        /** The running instances of the relaunched configuration — the default. */
        SAME,

        /** Every running Java launch except remote debug sessions. */
        ALL,

        /** Nothing: the new launch runs alongside the others. */
        NONE;

        /**
         * Reads the optional {@code terminate} tool argument; absent or blank means {@link #SAME}.
         *
         * @throws IllegalArgumentException if it is not one of {@code same}, {@code all}, {@code none}
         */
        static TerminateScope from(Map<String, Object> arguments) {
            Object value = arguments.get("terminate");
            if (value == null || value instanceof String string && string.isBlank()) {
                return SAME;
            }
            for (TerminateScope scope : values()) {
                if (scope.argumentValue().equals(value)) {
                    return scope;
                }
            }
            throw new IllegalArgumentException("terminate must be one of \"same\", \"all\" or \"none\"");
        }

        /**
         * Selects the running launches to terminate.
         *
         * @param running           the running Java launches
         * @param configurationName name of the configuration being relaunched
         * @return the launches to terminate
         */
        List<ILaunch> select(List<ILaunch> running, String configurationName) {
            return switch (this) {
                case SAME -> running.stream()
                        .filter(launch -> configurationName.equals(JavaLaunches.configurationName(launch)))
                        .toList();
                case ALL -> running.stream()
                        .filter(launch -> !JavaLaunches.isRemoteDebugSession(launch))
                        .toList();
                case NONE -> List.of();
            };
        }

        /**
         * Returns the tool argument value of this scope, e.g. {@code "same"}.
         */
        String argumentValue() {
            return name().toLowerCase(Locale.ROOT);
        }
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
         * Applies the overrides to a working copy of the configuration. The copy is deliberately
         * never saved, so the .launch file stays untouched.
         */
        void applyTo(ILaunchConfigurationWorkingCopy workingCopy) throws CoreException {
            append(workingCopy, IJavaLaunchConfigurationConstants.ATTR_VM_ARGUMENTS, vmArguments);
            append(workingCopy, IJavaLaunchConfigurationConstants.ATTR_PROGRAM_ARGUMENTS, programArguments);
            if (!environment.isEmpty()) {
                Map<String, String> merged = new HashMap<>(
                        workingCopy.getAttribute(ILaunchManager.ATTR_ENVIRONMENT_VARIABLES, Map.of()));
                merged.putAll(environment);
                workingCopy.setAttribute(ILaunchManager.ATTR_ENVIRONMENT_VARIABLES, merged);
            }
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
