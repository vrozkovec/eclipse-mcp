package com.eclipse.mcp.server.handlers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.eclipse.mcp.server.tools.AnalyzeTypeDependenciesTool;
import com.eclipse.mcp.server.tools.CleanWorkspaceTool;
import com.eclipse.mcp.server.tools.CleanupCodeTool;
import com.eclipse.mcp.server.tools.DebugRelaunchTool;
import com.eclipse.mcp.server.tools.FindReferencesTool;
import com.eclipse.mcp.server.tools.FindResourceTool;
import com.eclipse.mcp.server.tools.FindTypeTool;
import com.eclipse.mcp.server.tools.FormatCodeTool;
import com.eclipse.mcp.server.tools.GetBuildStatusTool;
import com.eclipse.mcp.server.tools.GetProblemsTool;
import com.eclipse.mcp.server.tools.ListProjectsTool;
import com.eclipse.mcp.server.tools.MavenGoalTool;
import com.eclipse.mcp.server.tools.MavenUpdateProjectTool;
import com.eclipse.mcp.server.tools.OrganizeImportsTool;
import com.eclipse.mcp.server.tools.RefactorActionsTool;
import com.eclipse.mcp.server.tools.RenamePackageTool;
import com.eclipse.mcp.server.tools.RefreshWorkspaceTool;
import com.eclipse.mcp.server.tools.ResolveProjectTool;
import com.eclipse.mcp.server.tools.RunTestClassTool;
import com.eclipse.mcp.server.tools.RunTestsTool;
import com.eclipse.mcp.server.tools.StopJavaApplicationTool;
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
        tools.put("run_test_class", new RunTestClassTool());
        tools.put("get_problems", new GetProblemsTool());
        tools.put("source_actions", new SourceActionsTool());
        tools.put("refactor_actions", new RefactorActionsTool());
        tools.put("maven_goal", new MavenGoalTool());
        tools.put("maven_update_project", new MavenUpdateProjectTool());
        tools.put("find_references", new FindReferencesTool());
        tools.put("analyze_type_dependencies", new AnalyzeTypeDependenciesTool());
        tools.put("clean_workspace", new CleanWorkspaceTool());
        tools.put("refresh_workspace", new RefreshWorkspaceTool());
        tools.put("debug_relaunch", new DebugRelaunchTool());
        tools.put("get_build_status", new GetBuildStatusTool());
        tools.put("stop_java_application", new StopJavaApplicationTool());
        tools.put("list_projects", new ListProjectsTool());
        tools.put("resolve_project", new ResolveProjectTool());
        tools.put("format_code", new FormatCodeTool());
        tools.put("organize_imports", new OrganizeImportsTool());
        tools.put("cleanup_code", new CleanupCodeTool());
        tools.put("rename_package", new RenamePackageTool());
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

        String textContent = objectMapper.writeValueAsString(result);

        Map<String, Object> response = new HashMap<>();
        response.put("content", List.of(Map.of("type", "text", "text", textContent)));
        response.put("isError", false);

        return response;
    }
}