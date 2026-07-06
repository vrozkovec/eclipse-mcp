package com.eclipse.mcp.server.tools;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.debug.core.DebugException;
import org.eclipse.debug.core.DebugPlugin;
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfigurationType;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.debug.core.model.IProcess;
import org.eclipse.debug.core.model.IStreamMonitor;
import org.eclipse.debug.core.model.IStreamsProxy;
import org.eclipse.debug.ui.IDebugUIConstants;
import org.eclipse.m2e.actions.MavenLaunchConstants;
import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.ui.PlatformUI;

/**
 * Launches Maven goals on a workspace project through an m2e launch configuration
 * ({@code org.eclipse.m2e.Maven2LaunchConfigurationType}) — the same mechanism as
 * "Run As &gt; Maven build" and the "Maven clean" launch shortcut. The configuration is
 * a fresh, private, unsaved working copy (exactly what m2e's {@code ExecutePomAction}
 * creates), so nothing leaks into the launch history or the Launch Configurations dialog.
 *
 * <p>Threading: launch creation runs on the UI thread via {@code syncCall} (fast);
 * waiting for the external Maven JVM polls {@link ILaunch#isTerminated()} on the
 * caller's (MCP request) thread so the Eclipse UI is never blocked.</p>
 */
final class MavenLauncher {

    /** Maximum number of trailing output characters included in the result. */
    private static final int OUTPUT_TAIL_CHARS = 8000;

    /** Poll interval while waiting for the external Maven process, in milliseconds. */
    private static final long POLL_INTERVAL_MILLIS = 250L;

    /** Grace period after termination letting the stream monitors flush, in milliseconds. */
    private static final long STREAM_FLUSH_MILLIS = 150L;

    private MavenLauncher() {
    }

    /**
     * Runs the given Maven goals on a project and blocks until the build finishes or the
     * timeout elapses (the process is then terminated best-effort).
     *
     * @param projectName    name of an open workspace project with the Maven nature
     * @param goals          Maven goals/phases for a single invocation, e.g. {@code ["clean"]}
     * @param timeoutSeconds maximum time to wait for the Maven process
     * @return result map with {@code status}, {@code success}, {@code exitCode},
     *         {@code buildResult}, {@code elapsedMillis} and the tail of the build output
     * @throws Exception if the project is invalid or the launch cannot be created
     */
    static Map<String, Object> run(String projectName, List<String> goals, long timeoutSeconds)
            throws Exception {
        String goalString = String.join(" ", goals);
        long start = System.currentTimeMillis();

        ILaunch launch = PlatformUI.getWorkbench().getDisplay().syncCall(() -> {
            try {
                return launchMaven(projectName, goalString);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        boolean timedOut = waitForTermination(launch, start + timeoutSeconds * 1000L);
        long elapsed = System.currentTimeMillis() - start;

        String output = collectOutput(launch);
        Integer exitCode = readExitCode(launch);
        boolean success = !timedOut && exitCode != null && exitCode.intValue() == 0;

        if (success) {
            // Drop the terminated launch from the Debug view; failures are kept so the
            // user can post-mortem the console.
            PlatformUI.getWorkbench().getDisplay().syncCall(() -> {
                DebugPlugin.getDefault().getLaunchManager().removeLaunch(launch);
                return null;
            });
        }

        Map<String, Object> result = new HashMap<>();
        result.put("projectName", projectName);
        result.put("goals", goalString);
        result.put("status", timedOut ? "timeout" : "completed");
        result.put("success", success);
        result.put("exitCode", exitCode);
        result.put("buildResult", detectBuildResult(output));
        result.put("elapsedMillis", elapsed);
        result.put("outputTruncated", output.length() > OUTPUT_TAIL_CHARS);
        result.put("outputTail", output.length() > OUTPUT_TAIL_CHARS
                ? output.substring(output.length() - OUTPUT_TAIL_CHARS)
                : output);
        if (timedOut) {
            result.put("timeoutSeconds", timeoutSeconds);
        }
        return result;
    }

    /**
     * Validates the project and spawns the external Maven process from a fresh, private,
     * unsaved m2e launch configuration. Must run on the UI thread.
     *
     * @param projectName name of an open workspace project with the Maven nature
     * @param goalString  space-joined Maven goals
     * @return the started launch
     * @throws Exception if the project is invalid or the launch cannot be created
     */
    private static ILaunch launchMaven(String projectName, String goalString) throws Exception {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!project.exists() || !project.isOpen()) {
            throw new IllegalArgumentException("Project not found or not open: " + projectName);
        }
        if (MavenPlugin.getMavenProjectRegistry().getProject(project) == null) {
            throw new IllegalArgumentException("Not a Maven project: " + projectName);
        }

        ILaunchManager launchManager = DebugPlugin.getDefault().getLaunchManager();
        ILaunchConfigurationType type = launchManager
                .getLaunchConfigurationType(MavenLaunchConstants.LAUNCH_CONFIGURATION_TYPE_ID);
        if (type == null) {
            throw new IllegalStateException(
                    "Maven launch support (org.eclipse.m2e.launching) is not installed");
        }

        String configName = launchManager.generateLaunchConfigurationName(
                "MCP Maven " + projectName + " " + goalString.replace(':', '-'));
        ILaunchConfigurationWorkingCopy config = type.newInstance(null, configName);
        config.setAttribute(MavenLaunchConstants.ATTR_POM_DIR, project.getLocation().toOSString());
        config.setAttribute(MavenLaunchConstants.ATTR_GOALS, goalString);
        config.setAttribute(MavenLaunchConstants.ATTR_BATCH, true);
        config.setAttribute(MavenLaunchConstants.ATTR_COLOR, MavenLaunchConstants.ATTR_COLOR_VALUE_NEVER);
        config.setAttribute(IDebugUIConstants.ATTR_PRIVATE, true);
        // Launch the unsaved working copy (as m2e's own launch shortcut does): no doSave()
        // means no persistent launch configuration is created.
        return config.launch(ILaunchManager.RUN_MODE, new NullProgressMonitor());
    }

    /**
     * Polls the launch on the calling thread until it terminates or the deadline passes;
     * on timeout the launch is terminated best-effort.
     *
     * @param launch         the running Maven launch
     * @param deadlineMillis wall-clock deadline in epoch milliseconds
     * @return {@code true} if the deadline passed before the launch terminated
     * @throws InterruptedException if the polling thread is interrupted
     */
    private static boolean waitForTermination(ILaunch launch, long deadlineMillis)
            throws InterruptedException {
        while (!launch.isTerminated()) {
            if (System.currentTimeMillis() > deadlineMillis) {
                try {
                    launch.terminate();
                } catch (DebugException e) {
                    // best effort — the result still reports the timeout
                }
                return true;
            }
            Thread.sleep(POLL_INTERVAL_MILLIS);
        }
        Thread.sleep(STREAM_FLUSH_MILLIS);
        return false;
    }

    /**
     * Collects the buffered stdout and stderr of the launch's process.
     *
     * @param launch the terminated (or timed-out) launch
     * @return the combined output, or an empty string if unavailable
     */
    private static String collectOutput(ILaunch launch) {
        IProcess[] processes = launch.getProcesses();
        if (processes.length == 0) {
            return "";
        }
        IStreamsProxy proxy = processes[0].getStreamsProxy();
        if (proxy == null) {
            return "";
        }
        return contents(proxy.getOutputStreamMonitor()) + contents(proxy.getErrorStreamMonitor());
    }

    /**
     * Returns a stream monitor's buffered contents, tolerating {@code null} monitors.
     *
     * @param monitor the stream monitor, may be {@code null}
     * @return the buffered contents, never {@code null}
     */
    private static String contents(IStreamMonitor monitor) {
        String contents = monitor != null ? monitor.getContents() : null;
        return contents != null ? contents : "";
    }

    /**
     * Reads the exit code of the launch's process, if it has terminated.
     *
     * @param launch the launch to inspect
     * @return the exit code, or {@code null} if the process is missing or still running
     */
    private static Integer readExitCode(ILaunch launch) {
        IProcess[] processes = launch.getProcesses();
        if (processes.length == 0 || !processes[0].isTerminated()) {
            return null;
        }
        try {
            return processes[0].getExitValue();
        } catch (DebugException e) {
            return null;
        }
    }

    /**
     * Classifies the build outcome by scanning the Maven output for its summary line.
     *
     * @param output the captured Maven output
     * @return {@code SUCCESS}, {@code FAILURE}, or {@code UNKNOWN}
     */
    private static String detectBuildResult(String output) {
        if (output.contains("BUILD SUCCESS")) {
            return "SUCCESS";
        }
        if (output.contains("BUILD FAILURE") || output.contains("BUILD ERROR")) {
            return "FAILURE";
        }
        return "UNKNOWN";
    }
}
