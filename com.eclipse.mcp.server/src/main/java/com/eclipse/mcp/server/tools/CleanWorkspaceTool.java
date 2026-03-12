package com.eclipse.mcp.server.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.ui.PlatformUI;

/**
 * Cleans projects in the workspace, equivalent to Project &gt; Clean in Eclipse.
 *
 * <p>If {@code projectName} is provided, only that project is cleaned.
 * Otherwise all open projects are cleaned.</p>
 */
public class CleanWorkspaceTool implements Tool {

    @Override
    public Object execute(Map<String, Object> arguments) throws Exception {
        String projectName = (String) arguments.get("projectName");
        return PlatformUI.getWorkbench().getDisplay().syncCall(() -> {
            try {
                if (projectName != null && !projectName.isBlank()) {
                    return cleanProject(projectName);
                }
                return cleanAllProjects();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private Map<String, Object> cleanProject(String projectName) throws Exception {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!project.exists() || !project.isOpen()) {
            throw new IllegalArgumentException("Project not found or not open: " + projectName);
        }

        project.build(IncrementalProjectBuilder.CLEAN_BUILD, new NullProgressMonitor());

        Map<String, Object> result = new HashMap<>();
        result.put("status", "cleaned");
        result.put("projectCount", 1);
        result.put("projects", List.of(projectName));
        return result;
    }

    private Map<String, Object> cleanAllProjects() throws Exception {
        var workspace = ResourcesPlugin.getWorkspace();
        IProject[] projects = workspace.getRoot().getProjects();

        List<String> cleanedProjects = new ArrayList<>();
        for (IProject project : projects) {
            if (project.isOpen()) {
                cleanedProjects.add(project.getName());
            }
        }

        workspace.build(IncrementalProjectBuilder.CLEAN_BUILD, new NullProgressMonitor());

        Map<String, Object> result = new HashMap<>();
        result.put("status", "cleaned");
        result.put("projectCount", cleanedProjects.size());
        result.put("projects", cleanedProjects);
        return result;
    }
}
