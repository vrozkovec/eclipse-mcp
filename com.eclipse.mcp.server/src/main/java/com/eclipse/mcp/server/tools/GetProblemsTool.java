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
        List<Map<String, Object>> warnings = new ArrayList<>();
        List<Map<String, Object>> infos = new ArrayList<>();
        
        IMarker[] markers = project.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);
        
        for (IMarker marker : markers) {
            Map<String, Object> problem = new HashMap<>();
            
            problem.put("message", marker.getAttribute(IMarker.MESSAGE, ""));
            problem.put("severity", marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO));
            problem.put("lineNumber", marker.getAttribute(IMarker.LINE_NUMBER, -1));
            problem.put("charStart", marker.getAttribute(IMarker.CHAR_START, -1));
            problem.put("charEnd", marker.getAttribute(IMarker.CHAR_END, -1));
            
            IResource resource = marker.getResource();
            if (resource != null) {
                problem.put("resourcePath", resource.getFullPath().toString());
                problem.put("resourceName", resource.getName());
                if (resource.getLocation() != null) {
                    problem.put("location", resource.getLocation().toString());
                }
            }
            
            problem.put("sourceId", marker.getAttribute(IMarker.SOURCE_ID, ""));
            problem.put("priority", marker.getAttribute(IMarker.PRIORITY, IMarker.PRIORITY_NORMAL));
            
            Object userEditable = marker.getAttribute(IMarker.USER_EDITABLE);
            if (userEditable != null) {
                problem.put("userEditable", userEditable);
            }
            
            try {
                problem.put("attributes", marker.getAttributes());
            } catch (Exception e) {
                // Ignore if we can't get all attributes
            }
            
            int severity = marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
            switch (severity) {
                case IMarker.SEVERITY_ERROR:
                    errors.add(problem);
                    break;
                case IMarker.SEVERITY_WARNING:
                    warnings.add(problem);
                    break;
                case IMarker.SEVERITY_INFO:
                default:
                    infos.add(problem);
                    break;
            }
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("projectName", projectName);
        result.put("errors", errors);
        result.put("warnings", warnings);
        result.put("infos", infos);
        result.put("totalProblems", markers.length);
        result.put("errorCount", errors.size());
        result.put("warningCount", warnings.size());
        result.put("infoCount", infos.size());
        
        return result;
    }
}