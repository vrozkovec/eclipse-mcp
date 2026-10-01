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
            "Launch JUnit tests in run mode (fire-and-forget; does not wait for results — use run_test_class " +
            "for per-test outcomes). Always uses Eclipse JDT's JUnit 6 loader. Scope is chosen by which " +
            "optional argument is set: testClass (single class, optionally narrowed by testMethod), " +
            "packageName (all tests directly in that package — picks the test source root when both src/main " +
            "and src/test contain the package), or neither (all tests in the whole project). testClass and " +
            "packageName are mutually exclusive.",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "projectName", Map.of(
                        "type", "string",
                        "description", "Name of the project containing the tests"
                    ),
                    "testClass", Map.of(
                        "type", "string",
                        "description", "Fully qualified name of a single test class to run (optional). Mutually exclusive with packageName."
                    ),
                    "packageName", Map.of(
                        "type", "string",
                        "description", "Fully qualified package name to run all tests in (e.g., 'com.example.foo'). Only direct contents of the package — subpackages are not included. Mutually exclusive with testClass."
                    ),
                    "testMethod", Map.of(
                        "type", "string",
                        "description", "Specific test method to run within testClass (optional; ignored if testClass is not set)"
                    )
                ),
                "required", List.of("projectName")
            )
        ));
        
        tools.add(createTool(
            "run_test_class",
            "Launch a single JUnit test class (optionally a specific method) in the Eclipse JUnit runner " +
            "and block until the session finishes (5-minute safety timeout). " +
            "Always uses Eclipse JDT's JUnit 6 loader (org.eclipse.jdt.junit.loader.junit6). Eclipse keeps " +
            "separate loaders for JUnit 5 and JUnit 6 — each loader's pre-launch check rejects mismatched " +
            "major versions, so a project must have JUnit 6 on its build path. " +
            "Returns per-test outcomes (OK/FAILURE/ERROR/IGNORED) with failure traces, roll-up counts, " +
            "elapsed time, and overall status (completed|timeout). Designed for fast tests (seconds).",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "projectName", Map.of(
                        "type", "string",
                        "description", "Name of the Eclipse project containing the test class"
                    ),
                    "className", Map.of(
                        "type", "string",
                        "description", "Fully qualified name of the test class (e.g., 'com.example.MyTest')"
                    ),
                    "testMethod", Map.of(
                        "type", "string",
                        "description", "Specific test method to run within the class (optional). Provide just the method name, not 'Class.method'."
                    )
                ),
                "required", List.of("projectName", "className")
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
            "Generate code like Eclipse's Source menu (Alt+Shift+S) and save the file: the missing getters/setters, a constructor "
                + "using fields, hashCode()/equals(), or toString(). Uses the operations behind Eclipse's own dialogs, so the code "
                + "follows the project's code templates (javadoc comments are always generated), formatter and getter/setter naming. "
                + "hashCode(), equals() and toString() always end up as the last members of the class, in that order (generating "
                + "them moves existing ones there too); getters and setters are inserted before them, a constructor after the fields "
                + "and existing constructors. Returns the signatures of the generated methods. Fails if the file has unsaved changes "
                + "in an Eclipse editor.",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "filePath", Map.of(
                        "type", "string",
                        "description", "Absolute filesystem path of the .java file (a workspace path such as /project/src/... as returned by find_type works too)"
                    ),
                    "action", Map.of(
                        "type", "string",
                        "description", "generate_getters_setters: missing accessors, as getter/setter pairs in field order (final fields get no setter); "
                            + "generate_constructor: a public constructor initializing the fields; generate_hashcode_equals: hashCode() and "
                            + "equals() with Objects.hash/Objects.equals; generate_toString: toString() as 'ClassName [field=value, ...]'",
                        "enum", List.of("generate_getters_setters", "generate_constructor", "generate_toString", "generate_hashcode_equals")
                    ),
                    "typeName", Map.of(
                        "type", "string",
                        "description", "Simple or fully qualified name of the type to generate into, e.g. a nested class. Defaults to the file's primary type."
                    ),
                    "fields", Map.of(
                        "type", "array",
                        "items", Map.of("type", "string"),
                        "description", "Names of the fields to use, in this order (the constructor's parameter order). Defaults to the fields "
                            + "Eclipse's dialog preselects: all non-static fields, without transient ones for hashCode/equals and toString, "
                            + "and without final fields that have an initializer for a constructor."
                    ),
                    "replaceExisting", Map.of(
                        "type", "boolean",
                        "description", "generate_hashcode_equals and generate_toString only: regenerate the methods if they already exist. "
                            + "If false, the call fails and the file stays unchanged.",
                        "default", false
                    )
                ),
                "required", List.of("filePath", "action")
            )
        ));
        
        tools.add(createTool(
            "rename_element",
            "Rename a Java type, method or field using Eclipse's refactoring engine (Alt+Shift+R equivalent). Updates references across the whole workspace; renaming a top-level type renames its .java file too. Use rename_package for packages.",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "element", Map.of(
                        "type", "string",
                        "description", "Selector for the element to rename. A fully qualified type ('com.example.Bar'), a nested type ('com.example.Bar.Inner'), or a member ('com.example.Bar#doWork', 'com.example.Bar#count'). Overloaded methods need an explicit parameter list: 'com.example.Bar#doWork(String,int)'. Simple type names are not accepted - resolve them with find_type first."
                    ),
                    "newName", Map.of(
                        "type", "string",
                        "description", "New simple name for the element (not qualified), e.g. 'execute'"
                    ),
                    "projectName", Map.of(
                        "type", "string",
                        "description", "Name of the Eclipse project containing the element. If omitted, all workspace projects are searched."
                    ),
                    "updateReferences", Map.of(
                        "type", "boolean",
                        "description", "Whether to update references to the element across the workspace",
                        "default", true
                    ),
                    "updateTextualOccurrences", Map.of(
                        "type", "boolean",
                        "description", "Whether to also update matches found in comments and string literals",
                        "default", false
                    )
                ),
                "required", List.of("element", "newName")
            )
        ));

        tools.add(createTool(
            "move_element",
            "Move a Java class to another package using Eclipse's refactoring engine (Alt+Shift+V equivalent). Moves the .java file, rewrites its package declaration, and updates imports and references across the workspace. The destination is resolved within the same source folder as the class being moved; moving between projects is not supported.",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "element", Map.of(
                        "type", "string",
                        "description", "Fully qualified name of the top-level class to move, e.g. 'com.example.Bar'. Nested types cannot be moved directly."
                    ),
                    "destination", Map.of(
                        "type", "string",
                        "description", "Fully qualified name of the destination package, e.g. 'com.example.util'"
                    ),
                    "projectName", Map.of(
                        "type", "string",
                        "description", "Name of the Eclipse project containing the class. If omitted, all workspace projects are searched."
                    ),
                    "updateReferences", Map.of(
                        "type", "boolean",
                        "description", "Whether to update references to the class across the workspace",
                        "default", true
                    ),
                    "createDestination", Map.of(
                        "type", "boolean",
                        "description", "Create the destination package if it does not exist. Off by default so that a mistyped package name is reported rather than silently created.",
                        "default", false
                    )
                ),
                "required", List.of("element", "destination")
            )
        ));
        
        tools.add(createTool(
            "maven_goal",
            "Run Maven goals on a project via an m2e launch (same as Run As > Maven build; external JVM using the workspace Maven runtime). Blocks until the build finishes or times out, then returns the exit code, BUILD SUCCESS/FAILURE detection and the tail of the Maven output. goals ['clean'] is equivalent to the Eclipse 'Maven clean' launch shortcut.",
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
                        "description", "Maven goals/phases for a single invocation, e.g. ['clean'] or ['clean', 'install']"
                    ),
                    "timeoutSeconds", Map.of(
                        "type", "integer",
                        "description", "Maximum seconds to wait before the Maven process is terminated",
                        "default", 300
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
            "maven_import_project",
            "Import an existing Maven project from a filesystem path into the workspace — the headless "
                + "equivalent of File > Import > Existing Maven Projects. Scans the directory recursively for "
                + "pom.xml files and imports the project including all nested modules of a multi-module build. "
                + "Projects already in the workspace (same location) are skipped. Blocks until the import "
                + "finishes — a first import may take minutes while dependencies resolve; Eclipse may keep "
                + "building afterwards (poll with get_build_status). Returns per-project entries with status "
                + "imported, skipped_existing, or not_imported.",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "path", Map.of(
                        "type", "string",
                        "description", "Absolute filesystem path to the project directory to scan, or to a pom.xml file (its parent directory is used)"
                    )
                ),
                "required", List.of("path")
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
            "Clean projects in the workspace (Project > Clean). If projectName is provided, only that project is cleaned. "
                + "Otherwise all open projects are cleaned. Returns at once; poll get_build_status for the rebuild. "
                + "If mavenClean is true, 'mvn clean' is run first via an m2e launch. The project and every open project "
                + "nested inside it (an aggregator's modules) are then refreshed from disk and cleaned, and once Eclipse's "
                + "rebuild has finished they are refreshed and cleaned again, so that sources the Maven builder regenerates "
                + "during the rebuild (e.g. JPA metamodels) get compiled. In this mode the call blocks until the second "
                + "rebuild finishes (up to 5 minutes after Maven) and also returns building (true only if that wait timed "
                + "out) and errors (error markers left in the cleaned projects).",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "projectName", Map.of(
                        "type", "string",
                        "description", "Name of a specific project to clean. If omitted, all open projects are cleaned."
                    ),
                    "mavenClean", Map.of(
                        "type", "boolean",
                        "description", "Run 'mvn clean' on the target project(s) first, then refresh and clean them twice, waiting for each rebuild (see the tool description). Projects without the Maven nature are skipped. Runs sequentially - slow on large workspaces; prefer passing projectName (an aggregator project cleans all its modules in one run).",
                        "default", false
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
            "Relaunch a launch configuration in debug mode: the most recently launched one, or configurationName. "
                + "Equivalent to Ctrl+F2 (Terminate) followed by F11 (Debug Last Launched). By default it first stops only the "
                + "running instances of that configuration, so other Java applications keep running (see terminate). "
                + "Each launch writes its console output to its own new file, returned as logFile: read that file, not the "
                + "configuration's Output File. Optional one-off vmArguments / programArguments / environment apply to this "
                + "launch only. The saved launch configuration is never modified: the tool launches an unsaved copy of it, "
                + "which also means the launch is not added to Eclipse's launch history (F11, Run History).",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "configurationName", Map.of(
                        "type", "string",
                        "description", "Name of a specific launch configuration to use. If omitted, the most recently launched "
                            + "configuration is used; the call is then refused while applications of several configurations are running."
                    ),
                    "terminate", Map.of(
                        "type", "string",
                        "enum", List.of("same", "all", "none"),
                        "description", "Which running Java applications to stop before launching: 'same' (default) stops the running "
                            + "instances of the relaunched configuration; 'all' stops every running Java application except attached "
                            + "remote debug sessions; 'none' starts alongside them, e.g. a second instance with vmArguments '-Dport=8081'."
                    ),
                    "vmArguments", Map.of(
                        "type", "string",
                        "description", "Extra JVM arguments for this launch only, appended after the configuration's own VM arguments, "
                            + "e.g. '-Dport=8081 -Xmx2g'. The JVM honours the last occurrence of a -D or -X option, so this also "
                            + "overrides values set in the configuration. Eclipse variables such as ${workspace_loc} are expanded."
                    ),
                    "programArguments", Map.of(
                        "type", "string",
                        "description", "Extra program arguments for this launch only, appended after the configuration's own program arguments."
                    ),
                    "environment", Map.of(
                        "type", "object",
                        "additionalProperties", Map.of("type", "string"),
                        "description", "Environment variables for this launch only, e.g. {\"FOO\": \"bar\"}. Added to the configuration's own, "
                            + "replacing variables of the same name."
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
            "Stop running Java applications (local apps, JUnit runs): all of them, or only the running instances of one launch "
                + "configuration. External tools and other non-Java launches are left untouched; stopping all also leaves attached "
                + "remote debug sessions attached. Equivalent to Ctrl+F2 (Terminate): stopped launches stay in the Debug view, so "
                + "debug_relaunch can still relaunch them. The result lists the applications that are still running.",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "configurationName", Map.of(
                        "type", "string",
                        "description", "Name of the launch configuration whose running instances to stop (exact match, as listed by "
                            + "list_running_applications). If omitted, all running Java applications are stopped."
                    )
                )
            )
        ));

        tools.add(createTool(
            "list_running_applications",
            "List the Java applications currently running from Eclipse (local apps, JUnit runs, remote debug sessions) without "
                + "starting or stopping anything. Each entry has configurationName, launchType, launchMode (run/debug), pid, "
                + "launchedAt and logFile, the file its console output is written to. Pass configurationName to "
                + "stop_java_application or debug_relaunch.",
            Map.of(
                "type", "object",
                "properties", Map.of()
            )
        ));

        tools.add(createTool(
            "cleanup_code",
            "Run Eclipse's 'Source > Clean Up' on Java source files using the project's cleanup profile. Applies code modernization rules like adding @Override, converting to enhanced for-loops, using lambda expressions, pattern matching instanceof, removing unnecessary casts, and more. Accepts a path to a single .java file or a directory (recursively).",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "path", Map.of(
                        "type", "string",
                        "description", "Absolute filesystem path to a .java file or directory containing Java files"
                    )
                ),
                "required", List.of("path")
            )
        ));

        tools.add(createTool(
            "format_code",
            "Format Java source files using Eclipse's code formatter with project-specific settings (e.g., EclipseCodeStyle.xml). Accepts a path to a single .java file or a directory (recursively).",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "path", Map.of(
                        "type", "string",
                        "description", "Absolute filesystem path to a .java file or directory containing Java files"
                    )
                ),
                "required", List.of("path")
            )
        ));

        tools.add(createTool(
            "organize_imports",
            "Organize imports in Java source files using Eclipse's import organizer with project-specific settings. Removes unused imports, adds missing ones, and sorts them according to project preferences. Accepts a path to a single .java file or a directory (recursively).",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "path", Map.of(
                        "type", "string",
                        "description", "Absolute filesystem path to a .java file or directory containing Java files"
                    )
                ),
                "required", List.of("path")
            )
        ));

        tools.add(createTool(
            "rename_package",
            "Rename a Java package using Eclipse's refactoring engine. Updates the package declaration in all files, rewrites import statements across the workspace, and moves files to the new directory structure. Optionally renames sub-packages.",
            Map.of(
                "type", "object",
                "properties", Map.of(
                    "packageName", Map.of(
                        "type", "string",
                        "description", "Fully qualified name of the package to rename (e.g., 'com.example.old')"
                    ),
                    "newPackageName", Map.of(
                        "type", "string",
                        "description", "Fully qualified new package name (e.g., 'com.example.renamed')"
                    ),
                    "projectName", Map.of(
                        "type", "string",
                        "description", "Name of the Eclipse project containing the package. If omitted, all workspace projects are searched."
                    ),
                    "renameSubpackages", Map.of(
                        "type", "boolean",
                        "description", "Whether to also rename sub-packages (e.g., com.example.old.util -> com.example.renamed.util)",
                        "default", true
                    )
                ),
                "required", List.of("packageName", "newPackageName")
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