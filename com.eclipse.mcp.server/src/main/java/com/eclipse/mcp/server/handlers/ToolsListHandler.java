package com.eclipse.mcp.server.handlers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ToolsListHandler implements MCPRequestHandler {

    @Override
    public Object handle(Object params) throws Exception {
        List<Map<String, Object>> tools = new ArrayList<>();
        
        tools.add(createTool(
            "find_type",
            "Find Java types by name",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "typeName", Map.of(
                        "type", "string",
                        "description", "Name or pattern of the type to find"
                    ),
                    "caseSensitive", Map.of(
                        "type", "boolean",
                        "description", "Whether the search should be case sensitive",
                        "default", false
                    )
                ),
                "required", List.of("typeName")
            )
        ));
        
        tools.add(createTool(
            "find_resource",
            "Find resources by name or path",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "resourceName", Map.of(
                        "type", "string",
                        "description", "Name or pattern of the resource to find"
                    ),
                    "fileExtension", Map.of(
                        "type", "string",
                        "description", "Filter by file extension (optional)"
                    )
                ),
                "required", List.of("resourceName")
            )
        ));
        
        tools.add(createTool(
            "run_tests",
            "Run JUnit tests",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "projectName", Map.of(
                        "type", "string",
                        "description", "Name of the project containing the tests"
                    ),
                    "testClass", Map.of(
                        "type", "string",
                        "description", "Specific test class to run (optional)"
                    ),
                    "testMethod", Map.of(
                        "type", "string",
                        "description", "Specific test method to run (optional)"
                    )
                ),
                "required", List.of("projectName")
            )
        ));
        
        tools.add(createTool(
            "get_problems",
            "Get compilation errors for a project",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "projectName", Map.of(
                        "type", "string",
                        "description", "Name of the project to get problems for"
                    )
                ),
                "required", List.of("projectName")
            )
        ));
        
        tools.add(createTool(
            "source_actions",
            "Execute Eclipse source actions (Alt+Shift+S equivalent)",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "filePath", Map.of(
                        "type", "string",
                        "description", "Path to the Java file"
                    ),
                    "action", Map.of(
                        "type", "string",
                        "description", "Source action to perform",
                        "enum", List.of("generate_getters_setters", "generate_constructor", "generate_toString", "generate_hashcode_equals")
                    )
                ),
                "required", List.of("filePath", "action")
            )
        ));
        
        tools.add(createTool(
            "refactor_actions",
            "Execute Eclipse refactoring actions (Alt+Shift+T equivalent)",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "filePath", Map.of(
                        "type", "string",
                        "description", "Path to the Java file"
                    ),
                    "action", Map.of(
                        "type", "string",
                        "description", "Refactoring action to perform",
                        "enum", List.of("rename", "extract_method", "extract_variable", "inline", "move")
                    ),
                    "parameters", Map.of(
                        "type", "object",
                        "description", "Parameters for the refactoring action"
                    )
                ),
                "required", List.of("filePath", "action")
            )
        ));
        
        tools.add(createTool(
            "maven_goal",
            "Execute Maven goals on a project",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "projectName", Map.of(
                        "type", "string",
                        "description", "Name of the Maven project"
                    ),
                    "goals", Map.of(
                        "type", "array",
                        "items", Map.of("type", "string"),
                        "description", "Maven goals to execute"
                    )
                ),
                "required", List.of("projectName", "goals")
            )
        ));
        
        tools.add(createTool(
            "maven_update_project",
            "Update Maven project configuration",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "projectName", Map.of(
                        "type", "string",
                        "description", "Name of the Maven project to update"
                    ),
                    "forceUpdate", Map.of(
                        "type", "boolean",
                        "description", "Force update of snapshots/releases",
                        "default", false
                    )
                ),
                "required", List.of("projectName")
            )
        ));
        
        tools.add(createTool(
            "find_references",
            "Find all references to a Java element (type, method, or field) across the workspace. Equivalent to Ctrl+Shift+G in Eclipse.",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "elementName", Map.of(
                        "type", "string",
                        "description", "Name of the element to find references for (simple name or fully qualified)"
                    ),
                    "elementType", Map.of(
                        "type", "string",
                        "description", "Type of the element: 'type', 'method', or 'field'",
                        "enum", List.of("type", "method", "field"),
                        "default", "type"
                    ),
                    "projectScope", Map.of(
                        "type", "string",
                        "description", "Limit search to a specific project (optional)"
                    ),
                    "caseSensitive", Map.of(
                        "type", "boolean",
                        "description", "Whether the search should be case sensitive",
                        "default", true
                    )
                ),
                "required", List.of("elementName")
            )
        ));

        tools.add(createTool(
            "analyze_type_dependencies",
            "Analyze all type dependencies of a Java type. Returns all types referenced in the source, grouped by package and source (project/JAR). Useful for understanding what a type depends on before extracting it to another project.",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "typeName", Map.of(
                        "type", "string",
                        "description", "Fully qualified name of the type to analyze (e.g., 'com.example.MyClass')"
                    ),
                    "excludePackages", Map.of(
                        "type", "array",
                        "items", Map.of("type", "string"),
                        "description", "Package prefixes to flag as problematic dependencies (e.g., ['org.hibernate', 'com.google.inject'])"
                    ),
                    "includeTransitive", Map.of(
                        "type", "boolean",
                        "description", "Whether to recursively analyze project-local dependencies (capped at 100 types)",
                        "default", false
                    )
                ),
                "required", List.of("typeName")
            )
        ));

        Map<String, Object> result = new HashMap<>();
        result.put("tools", tools);

        return result;
    }
    
    private Map<String, Object> createTool(String name, String description, Map<String, Object> inputSchema) {
        Map<String, Object> tool = new HashMap<>();
        tool.put("name", name);
        tool.put("description", description);
        tool.put("inputSchema", inputSchema);
        return tool;
    }
}