package com.eclipse.mcp.server.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.ui.PlatformUI;

public class GetProblemsTool implements Tool {

    @Override
    public Object execute(Map<String, Object> arguments) throws Exception {
        String projectName = (String) arguments.get("projectName");
        
        if (projectName == null || projectName.trim().isEmpty()) {
            throw new IllegalArgumentException("projectName is required");
        }
        
        return PlatformUI.getWorkbench().getDisplay().syncCall(() -> {
            try {
                return getProblems(projectName);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
    
    private Map<String, Object> getProblems(String projectName) throws CoreException {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!project.exists() || !project.isOpen()) {
            throw new IllegalArgumentException("Project not found or not open: " + projectName);
        }

        List<Map<String, Object>> errors = new ArrayList<>();

        IMarker[] markers = project.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);

        for (IMarker marker : markers) {
            int severity = marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
            if (severity != IMarker.SEVERITY_ERROR) {
                continue;
            }

            Map<String, Object> error = new HashMap<>();

            error.put("message", marker.getAttribute(IMarker.MESSAGE, ""));
            error.put("lineNumber", marker.getAttribute(IMarker.LINE_NUMBER, -1));
            error.put("charStart", marker.getAttribute(IMarker.CHAR_START, -1));
            error.put("charEnd", marker.getAttribute(IMarker.CHAR_END, -1));

            IResource resource = marker.getResource();
            if (resource != null) {
                error.put("resourcePath", resource.getFullPath().toString());
                error.put("resourceName", resource.getName());
                if (resource.getLocation() != null) {
                    error.put("location", resource.getLocation().toString());
                }
            }

            error.put("sourceId", marker.getAttribute(IMarker.SOURCE_ID, ""));

            errors.add(error);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("projectName", projectName);
        result.put("errors", errors);
        result.put("errorCount", errors.size());

        return result;
    }
}