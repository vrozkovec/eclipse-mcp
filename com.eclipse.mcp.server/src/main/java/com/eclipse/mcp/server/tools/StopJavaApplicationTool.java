package com.eclipse.mcp.server.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.debug.core.ILaunch;
import org.eclipse.ui.PlatformUI;

/**
 * Stops running Java applications in the workspace: all of them, or the running instances of one
 * launch configuration ({@code configurationName}).
 *
 * <p>Only Java launches (local Java applications, JUnit runs) are terminated — external tools, Maven
 * builds and other non-Java launches are left untouched. A remote debug session attaches to a VM that
 * Eclipse did not start: stopping everything leaves it attached, and naming its configuration
 * disconnects it (or terminates the remote VM, if the configuration allows that).</p>
 *
 * <p>Equivalent to selecting the Java processes and pressing Ctrl+F2 (Terminate) in Eclipse. As
 * there, stopped launches stay in the Debug view: their consoles remain readable, and
 * {@code debug_relaunch} still finds the most recently launched configuration.</p>
 */
public class StopJavaApplicationTool implements Tool {

    @Override
    public Object execute(Map<String, Object> arguments) throws Exception {
        // Validated before the UI-thread call so bad input surfaces as a plain error message
        String configurationName = optionalConfigurationName(arguments);
        return PlatformUI.getWorkbench().getDisplay().syncCall(() -> {
            try {
                return stopJavaApplications(configurationName);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private Map<String, Object> stopJavaApplications(String configurationName) throws Exception {
        List<ILaunch> running = JavaLaunches.running();
        List<ILaunch> toStop = running.stream()
                .filter(launch -> configurationName == null
                        ? !JavaLaunches.isRemoteDebugSession(launch)
                        : configurationName.equals(JavaLaunches.configurationName(launch)))
                .toList();
        List<String> terminated = JavaLaunches.terminate(toStop);

        // Computed rather than queried: terminate() can return before isTerminated() flips
        List<ILaunch> stillRunning = new ArrayList<>(running);
        stillRunning.removeAll(toStop);

        Map<String, Object> result = new HashMap<>();
        result.put("status", status(configurationName, terminated));
        if (configurationName != null) {
            result.put("configurationName", configurationName);
        }
        result.put("terminatedCount", terminated.size());
        result.put("terminatedLaunches", terminated);
        result.put("stillRunning", stillRunning.stream().map(JavaLaunches::describe).toList());
        return result;
    }

    private static String status(String configurationName, List<String> terminated) {
        if (!terminated.isEmpty()) {
            return "terminated";
        }
        return configurationName != null ? "not_running" : "no_java_applications_running";
    }

    /**
     * Reads the optional {@code configurationName} argument; a blank name counts as absent.
     *
     * @throws IllegalArgumentException if it is not a string
     */
    private static String optionalConfigurationName(Map<String, Object> arguments) {
        Object value = arguments.get("configurationName");
        if (value == null) {
            return null;
        }
        if (!(value instanceof String name)) {
            throw new IllegalArgumentException("configurationName must be a string");
        }
        return name.isBlank() ? null : name;
    }
}
