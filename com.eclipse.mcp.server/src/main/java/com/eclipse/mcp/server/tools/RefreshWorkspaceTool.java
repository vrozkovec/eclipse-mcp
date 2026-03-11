package com.eclipse.mcp.server.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.ui.PlatformUI;

/**
 * Refreshes all open projects in the workspace, equivalent to selecting all projects and pressing F5.
 * Synchronizes the workspace with the filesystem.
 */
public class RefreshWorkspaceTool implements Tool {

    @Override
    public Object execute(Map<String, Object> arguments) throws Exception {
        return PlatformUI.getWorkbench().getDisplay().syncCall(() -> {
            try {
                return refreshWorkspace();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private Map<String, Object> refreshWorkspace() throws Exception {
        IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
        var monitor = new NullProgressMonitor();

        List<String> refreshedProjects = new ArrayList<>();
        for (IProject project : projects) {
            if (project.isOpen()) {
                project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
                refreshedProjects.add(project.getName());
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("status", "refreshed");
        result.put("projectCount", refreshedProjects.size());
        result.put("projects", refreshedProjects);
        return result;
    }
}
