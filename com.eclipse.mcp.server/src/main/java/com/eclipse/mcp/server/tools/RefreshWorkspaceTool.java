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
 * Refreshes projects in the workspace, equivalent to pressing F5.
 * Synchronizes the workspace with the filesystem.
 *
 * <p>If {@code projectName} is provided, only that project is refreshed.
 * Otherwise all open projects are refreshed.</p>
 */
public class RefreshWorkspaceTool implements Tool {

    @Override
    public Object execute(Map<String, Object> arguments) throws Exception {
        String projectName = (String) arguments.get("projectName");
        return PlatformUI.getWorkbench().getDisplay().syncCall(() -> {
            try {
                if (projectName != null && !projectName.isBlank()) {
                    return refreshProject(projectName);
                }
                return refreshAllProjects();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private Map<String, Object> refreshProject(String projectName) throws Exception {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!project.exists() || !project.isOpen()) {
            throw new IllegalArgumentException("Project not found or not open: " + projectName);
        }

        project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());

        Map<String, Object> result = new HashMap<>();
        result.put("status", "refreshed");
        result.put("projectCount", 1);
        result.put("projects", List.of(projectName));
        return result;
    }

    private Map<String, Object> refreshAllProjects() throws Exception {
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
