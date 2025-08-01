package com.eclipse.mcp.server.handlers;

import java.util.HashMap;
import java.util.Map;

public class InitializeHandler implements MCPRequestHandler {

    @Override
    public Object handle(Object params) throws Exception {
        Map<String, Object> result = new HashMap<>();
        result.put("protocolVersion", "2024-11-05");
        
        Map<String, Object> capabilities = new HashMap<>();
        
        Map<String, Object> tools = new HashMap<>();
        tools.put("listChanged", true);
        capabilities.put("tools", tools);
        
        Map<String, Object> resources = new HashMap<>();
        resources.put("subscribe", true);
        resources.put("listChanged", true);
        capabilities.put("resources", resources);
        
        result.put("capabilities", capabilities);
        result.put("serverInfo", Map.of(
            "name", "Eclipse MCP Server",
            "version", "1.0.0"
        ));
        
        return result;
    }
}