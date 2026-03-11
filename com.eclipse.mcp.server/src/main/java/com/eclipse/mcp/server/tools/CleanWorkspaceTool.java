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
 * Cleans the entire workspace, equivalent to Project > Clean > Clean all projects in Eclipse.
 * Triggers a full clean build on every open project.
 */
public class CleanWorkspaceTool implements Tool {

    @Override
    public Object execute(Map<String, Object> arguments) throws Exception {
        return PlatformUI.getWorkbench().getDisplay().syncCall(() -> {
            try {
                return cleanWorkspace();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private Map<String, Object> cleanWorkspace() throws Exception {
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
