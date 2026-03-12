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

        tools.add(createTool(
            "clean_workspace",
            "Clean projects in the workspace (Project > Clean). If projectName is provided, only that project is cleaned. Otherwise all open projects are cleaned.",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "projectName", Map.of(
                        "type", "string",
                        "description", "Name of a specific project to clean. If omitted, all open projects are cleaned."
                    )
                )
            )
        ));

        tools.add(createTool(
            "list_projects",
            "List all projects in the Eclipse workspace with their name, location, and open status.",
            Map.of(
                "type", "object",
                "properties", Map.of()
            )
        ));

        tools.add(createTool(
            "resolve_project",
            "Resolve an absolute filesystem path to the Eclipse workspace project that contains it. Returns the project name, location, and open status. Useful for determining which projectName to pass to tools like get_problems, clean_workspace, or refresh_workspace.",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "path", Map.of(
                        "type", "string",
                        "description", "Absolute filesystem path to resolve (e.g., '/speedy/dev/name.berries/wicket-common/src/main/java')"
                    )
                ),
                "required", List.of("path")
            )
        ));

        tools.add(createTool(
            "refresh_workspace",
            "Refresh projects in the workspace (F5). If projectName is provided, only that project is refreshed. Otherwise all open projects are refreshed.",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "projectName", Map.of(
                        "type", "string",
                        "description", "Name of a specific project to refresh. If omitted, all open projects are refreshed."
                    )
                )
            )
        ));

        tools.add(createTool(
            "debug_relaunch",
            "Stop any currently running program and relaunch the most recently used launch configuration in debug mode. Equivalent to Ctrl+F2 (Terminate) followed by F11 (Debug Last Launched). Optionally specify a launch configuration by name.",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "configurationName", Map.of(
                        "type", "string",
                        "description", "Name of a specific launch configuration to use. If omitted, the most recently launched configuration is used."
                    )
                )
            )
        ));

        tools.add(createTool(
            "get_build_status",
            "Check whether Eclipse is currently building the workspace. Returns build-in-progress flag, auto-build status, and current error/warning counts. Useful for polling after clean_workspace to know when the build is complete.",
            Map.of(
                "type", "object",
                "properties", Map.of()
            )
        ));

        tools.add(createTool(
            "stop_java_application",
            "Stop all currently running Java applications. Only terminates Java launches (local apps, JUnit) — external tools and other non-Java launches are left untouched. Equivalent to Ctrl+F2 (Terminate) for Java processes.",
            Map.of(
                "type", "object",
                "properties", Map.of()
            )
        ));

        tools.add(createTool(
            "cleanup_code",
            "Run Eclipse's 'Source > Clean Up' on Java source files using the project's cleanup profile. Applies code modernization rules like adding @Override, converting to enhanced for-loops, using lambda expressions, pattern matching instanceof, removing unnecessary casts, and more. Accepts a path to a single .java file or a directory.",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "path", Map.of(
                        "type", "string",
                        "description", "Absolute filesystem path to a .java file or directory containing Java files"
                    ),
                    "recursive", Map.of(
                        "type", "boolean",
                        "description", "Whether to recurse into subdirectories when path is a directory",
                        "default", true
                    )
                ),
                "required", List.of("path")
            )
        ));

        tools.add(createTool(
            "format_code",
            "Format Java source files using Eclipse's code formatter with project-specific settings (e.g., EclipseCodeStyle.xml). Accepts a path to a single .java file or a directory to format all Java files within it.",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "path", Map.of(
                        "type", "string",
                        "description", "Absolute filesystem path to a .java file or directory containing Java files"
                    ),
                    "recursive", Map.of(
                        "type", "boolean",
                        "description", "Whether to recurse into subdirectories when path is a directory",
                        "default", true
                    )
                ),
                "required", List.of("path")
            )
        ));

        tools.add(createTool(
            "organize_imports",
            "Organize imports in Java source files using Eclipse's import organizer with project-specific settings. Removes unused imports, adds missing ones, and sorts them according to project preferences. Accepts a path to a single .java file or a directory.",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "path", Map.of(
                        "type", "string",
                        "description", "Absolute filesystem path to a .java file or directory containing Java files"
                    ),
                    "recursive", Map.of(
                        "type", "boolean",
                        "description", "Whether to recurse into subdirectories when path is a directory",
                        "default", true
                    )
                ),
                "required", List.of("path")
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