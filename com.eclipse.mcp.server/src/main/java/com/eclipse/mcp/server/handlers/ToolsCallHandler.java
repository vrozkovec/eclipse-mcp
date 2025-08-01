package com.eclipse.mcp.server.handlers;

import java.util.HashMap;
import java.util.Map;

import com.eclipse.mcp.server.tools.FindResourceTool;
import com.eclipse.mcp.server.tools.FindTypeTool;
import com.eclipse.mcp.server.tools.GetProblemsTool;
import com.eclipse.mcp.server.tools.MavenGoalTool;
import com.eclipse.mcp.server.tools.MavenUpdateProjectTool;
import com.eclipse.mcp.server.tools.RefactorActionsTool;
import com.eclipse.mcp.server.tools.RunTestsTool;
import com.eclipse.mcp.server.tools.SourceActionsTool;
import com.eclipse.mcp.server.tools.Tool;
import com.fasterxml.jackson.databind.ObjectMapper;

public class ToolsCallHandler implements MCPRequestHandler {
    
    private final Map<String, Tool> tools;
    private final ObjectMapper objectMapper;
    
    public ToolsCallHandler() {
        this.objectMapper = new ObjectMapper();
        this.tools = new HashMap<>();
        
        registerTools();
    }
    
    private void registerTools() {
        tools.put("find_type", new FindTypeTool());
        tools.put("find_resource", new FindResourceTool());
        tools.put("run_tests", new RunTestsTool());
        tools.put("get_problems", new GetProblemsTool());
        tools.put("source_actions", new SourceActionsTool());
        tools.put("refactor_actions", new RefactorActionsTool());
        tools.put("maven_goal", new MavenGoalTool());
        tools.put("maven_update_project", new MavenUpdateProjectTool());
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object handle(Object params) throws Exception {
        Map<String, Object> paramsMap = (Map<String, Object>) params;
        String toolName = (String) paramsMap.get("name");
        Object arguments = paramsMap.get("arguments");
        
        Tool tool = tools.get(toolName);
        if (tool == null) {
            throw new IllegalArgumentException("Unknown tool: " + toolName);
        }
        
        Map<String, Object> argumentsMap = new HashMap<>();
        if (arguments != null) {
            argumentsMap = objectMapper.convertValue(arguments, Map.class);
        }
        
        Object result = tool.execute(argumentsMap);
        
        Map<String, Object> response = new HashMap<>();
        response.put("content", result);
        response.put("isError", false);
        
        return response;
    }
}