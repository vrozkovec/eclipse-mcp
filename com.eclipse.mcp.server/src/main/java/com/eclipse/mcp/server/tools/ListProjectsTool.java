package com.eclipse.mcp.server.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.ui.PlatformUI;

/**
 * Lists all projects in the workspace with their name, location, and open status.
 */
public class ListProjectsTool implements Tool {

    @Override
    public Object execute(Map<String, Object> arguments) throws Exception {
        return PlatformUI.getWorkbench().getDisplay().syncCall(() -> {
            try {
                return listProjects();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private Map<String, Object> listProjects() {
        IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();

        List<Map<String, Object>> projectList = new ArrayList<>();
        for (IProject project : projects) {
            Map<String, Object> info = new HashMap<>();
            info.put("name", project.getName());
            info.put("isOpen", project.isOpen());
            if (project.isOpen() && project.getLocation() != null) {
                info.put("location", project.getLocation().toString());
            }
            projectList.add(info);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("projects", projectList);
        result.put("totalCount", projectList.size());
        return result;
    }
}
