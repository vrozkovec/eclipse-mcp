package com.eclipse.mcp.server.handlers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ResourcesListHandler implements MCPRequestHandler {

    @Override
    public Object handle(Object params) throws Exception {
        List<Map<String, Object>> resources = new ArrayList<>();
        
        resources.add(createResource(
            "workspace://projects",
            "List all projects in the workspace",
            "application/json"
        ));
        
        resources.add(createResource(
            "workspace://project/{projectName}/files",
            "List all files in a specific project",
            "application/json"
        ));
        
        resources.add(createResource(
            "workspace://project/{projectName}/problems",
            "Get problems for a specific project",
            "application/json"
        ));
        
        Map<String, Object> result = new HashMap<>();
        result.put("resources", resources);
        
        return result;
    }
    
    private Map<String, Object> createResource(String uri, String name, String mimeType) {
        Map<String, Object> resource = new HashMap<>();
        resource.put("uri", uri);
        resource.put("name", name);
        resource.put("mimeType", mimeType);
        return resource;
    }
}