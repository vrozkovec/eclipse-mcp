package com.eclipse.mcp.server.handlers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.ui.PlatformUI;

public class ResourcesReadHandler implements MCPRequestHandler {

    @Override
    @SuppressWarnings("unchecked")
    public Object handle(Object params) throws Exception {
        Map<String, Object> paramsMap = (Map<String, Object>) params;
        String uri = (String) paramsMap.get("uri");
        
        if (uri == null) {
            throw new IllegalArgumentException("URI is required");
        }
        
        return PlatformUI.getWorkbench().getDisplay().syncCall(() -> {
            try {
                return readResource(uri);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
    
    private Map<String, Object> readResource(String uri) throws Exception {
        Map<String, Object> result = new HashMap<>();
        
        if (uri.equals("workspace://projects")) {
            List<Map<String, Object>> projects = new ArrayList<>();
            
            IProject[] workspaceProjects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
            for (IProject project : workspaceProjects) {
                if (project.isOpen()) {
                    Map<String, Object> projectInfo = new HashMap<>();
                    projectInfo.put("name", project.getName());
                    projectInfo.put("location", project.getLocation().toString());
                    projectInfo.put("description", project.getDescription().getComment());
                    projects.add(projectInfo);
                }
            }
            
            result.put("contents", projects);
            result.put("mimeType", "application/json");
            
        } else if (uri.startsWith("workspace://project/") && uri.endsWith("/problems")) {
            String projectName = uri.substring("workspace://project/".length(), uri.length() - "/problems".length());
            
            com.eclipse.mcp.server.tools.GetProblemsTool problemsTool = new com.eclipse.mcp.server.tools.GetProblemsTool();
            Map<String, Object> arguments = new HashMap<>();
            arguments.put("projectName", projectName);
            
            Object problems = problemsTool.execute(arguments);
            result.put("contents", problems);
            result.put("mimeType", "application/json");
            
        } else if (uri.startsWith("workspace://project/") && uri.endsWith("/files")) {
            String projectName = uri.substring("workspace://project/".length(), uri.length() - "/files".length());
            
            com.eclipse.mcp.server.tools.FindResourceTool resourceTool = new com.eclipse.mcp.server.tools.FindResourceTool();
            Map<String, Object> arguments = new HashMap<>();
            arguments.put("resourceName", "*");
            
            Object files = resourceTool.execute(arguments);
            result.put("contents", files);
            result.put("mimeType", "application/json");
            
        } else {
            throw new IllegalArgumentException("Unknown resource URI: " + uri);
        }
        
        return result;
    }
}