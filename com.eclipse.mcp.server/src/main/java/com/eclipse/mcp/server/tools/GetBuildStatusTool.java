package com.eclipse.mcp.server.tools;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.ui.PlatformUI;

/**
 * Reports the current build status of the workspace.
 *
 * <p>Returns whether auto-build or manual build jobs are currently running,
 * whether auto-build is enabled, and error/warning counts. Useful for polling
 * after a {@code clean_workspace} to know when Eclipse has finished rebuilding.</p>
 */
public class GetBuildStatusTool implements Tool {

    @Override
    public Object execute(Map<String, Object> arguments) throws Exception {
        return PlatformUI.getWorkbench().getDisplay().syncCall(() -> {
            try {
                return getBuildStatus();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private Map<String, Object> getBuildStatus() throws Exception {
        var workspace = ResourcesPlugin.getWorkspace();
        var jobManager = Job.getJobManager();

        Job[] autoBuilds = jobManager.find(ResourcesPlugin.FAMILY_AUTO_BUILD);
        Job[] manualBuilds = jobManager.find(ResourcesPlugin.FAMILY_MANUAL_BUILD);

        boolean autoBuildRunning = autoBuilds.length > 0;
        boolean manualBuildRunning = manualBuilds.length > 0;
        boolean building = autoBuildRunning || manualBuildRunning;

        // Count errors and warnings across all open projects
        int errorCount = 0;
        int warningCount = 0;
        for (var project : workspace.getRoot().getProjects()) {
            if (project.isOpen()) {
                var markers = project.findMarkers(
                        org.eclipse.core.resources.IMarker.PROBLEM, true,
                        org.eclipse.core.resources.IResource.DEPTH_INFINITE);
                for (var marker : markers) {
                    int severity = marker.getAttribute(
                            org.eclipse.core.resources.IMarker.SEVERITY, -1);
                    if (severity == org.eclipse.core.resources.IMarker.SEVERITY_ERROR) {
                        errorCount++;
                    } else if (severity == org.eclipse.core.resources.IMarker.SEVERITY_WARNING) {
                        warningCount++;
                    }
                }
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("building", building);
        result.put("autoBuildRunning", autoBuildRunning);
        result.put("manualBuildRunning", manualBuildRunning);
        result.put("autoBuildEnabled", workspace.isAutoBuilding());
        result.put("errors", errorCount);
        result.put("warnings", warningCount);
        return result;
    }
}
