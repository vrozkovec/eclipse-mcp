# Eclipse MCP Server Plugin

## Purpose

An Eclipse IDE plugin that exposes Eclipse's development capabilities (code search, refactoring, dependency analysis, Maven integration) through the **Model Context Protocol (MCP)**. This allows AI assistants like Claude Code to programmatically interact with an Eclipse workspace — searching types, finding references, analyzing dependencies, running tests, and more — all through a standard JSON-RPC 2.0 interface.

The primary use case is enabling AI-assisted development workflows where Claude Code can leverage Eclipse's JDT (Java Development Tools) for operations that require a full IDE: type resolution, reference finding across a workspace, AST-based dependency analysis, and project-aware refactoring.

## Technology Stack

- **Language**: Java 21
- **Framework**: Eclipse Platform 4.32 (2024-12), OSGi
- **Build**: Maven Tycho 4.0.13
- **Protocol**: MCP (JSON-RPC 2.0, newline-delimited) over TCP
- **Serialization**: Jackson (OSGi bundles)
- **Claude Code integration**: stdio bridge script (`mcp-bridge.py`)

## Build

```bash
export JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64/
mvn clean install
```

JDK 21 is required — M2E 2.7.0 in the Eclipse 2024-12 target platform requires `JavaSE-21`. The default system JDK (25) is not recognized by Tycho 4.0.13.

## Project Structure

```
eclipse-mcp/
├── com.eclipse.mcp.server/          # Main OSGi plugin bundle
│   ├── META-INF/MANIFEST.MF         # OSGi dependencies (Require-Bundle)
│   ├── plugin.xml                   # Eclipse extension points (startup, prefs)
│   ├── pom.xml                      # Tycho eclipse-plugin packaging
│   └── src/main/java/com/eclipse/mcp/server/
│       ├── MCPServer.java           # TCP server, JSON-RPC routing, logging
│       ├── Activator.java           # OSGi bundle lifecycle
│       ├── handlers/
│       │   ├── MCPRequestHandler.java    # Handler interface
│       │   ├── InitializeHandler.java    # MCP handshake (protocol version, capabilities)
│       │   ├── ToolsListHandler.java     # Advertises tools + JSON Schema to clients
│       │   ├── ToolsCallHandler.java     # Routes tool execution, wraps results as MCP content
│       │   ├── ResourcesListHandler.java # Workspace resource listing
│       │   └── ResourcesReadHandler.java # Workspace resource reading
│       ├── tools/
│       │   ├── Tool.java                      # Interface: execute(Map args) -> Object
│       │   ├── FindTypeTool.java              # JDT type search (Ctrl+Shift+T)
│       │   ├── FindResourceTool.java          # Workspace resource search (Ctrl+Shift+R)
│       │   ├── FindReferencesTool.java        # Find all references (Ctrl+Shift+G)
│       │   ├── AnalyzeTypeDependenciesTool.java # AST-based dependency analysis
│       │   ├── GetProblemsTool.java           # Project error/warning markers
│       │   ├── RunTestsTool.java              # JUnit test execution
│       │   ├── SourceActionsTool.java         # Code generation (Alt+Shift+S) [stub]
│       │   ├── RefactorActionsTool.java       # Refactoring (Alt+Shift+T) [stub]
│       │   ├── MavenGoalTool.java             # Maven goal execution [stub]
│       │   └── MavenUpdateProjectTool.java    # Maven project refresh
│       ├── protocol/
│       │   ├── MCPMessage.java       # JSON-RPC 2.0 message (Jackson annotations)
│       │   └── MCPError.java         # Standard JSON-RPC error codes
│       ├── preferences/
│       │   ├── PreferenceInitializer.java  # Default port (8099), auto-start
│       │   └── MCPPreferencePage.java      # Eclipse preferences UI
│       └── startup/
│           └── MCPServerStartup.java       # IStartup extension
├── com.eclipse.mcp.feature/         # Eclipse feature for packaging
├── com.eclipse.mcp.updatesite/      # P2 update site for distribution
├── mcp-bridge.py                    # stdio-to-TCP bridge for Claude Code
└── pom.xml                          # Parent POM (Tycho reactor)
```

## MCP Tools (10 total)

### Fully implemented

| Tool | Description | Key Eclipse API |
|------|-------------|-----------------|
| `find_type` | Search Java types by name/pattern | `SearchEngine`, `IJavaSearchConstants.TYPE` |
| `find_resource` | Search workspace files by name/pattern | `IResourceVisitor` |
| `find_references` | Find all references to a type/method/field | `SearchEngine`, `IJavaSearchConstants.REFERENCES` |
| `analyze_type_dependencies` | Analyze all types referenced by a given type, grouped by package/source, with excluded-package flagging and optional transitive analysis | `ASTParser`, `ASTVisitor`, `ITypeBinding` |
| `get_problems` | Get compilation errors/warnings for a project | `IMarker.PROBLEM` |
| `run_tests` | Execute JUnit tests | `JUnitLaunchConfigurationDelegate` |
| `maven_update_project` | Refresh Maven project configuration | `IMavenProjectRegistry.refresh()` |

### Stubs (framework ready, return status messages)

| Tool | Description |
|------|-------------|
| `source_actions` | Code generation (getters, constructors, toString, hashCode/equals) |
| `refactor_actions` | Refactoring (rename, extract method/variable, inline, move) |
| `maven_goal` | Execute Maven goals |

## Architecture

### Request flow

1. Client connects via TCP to port 8099
2. Server reads newline-delimited JSON-RPC messages
3. `MCPServer.processMessage()` deserializes to `MCPMessage`
4. Routes to handler by `method` field: `initialize`, `tools/list`, `tools/call`, `resources/*`
5. `ToolsCallHandler` looks up tool, executes, serializes result as `{"type":"text","text":"..."}` content
6. Response written back as single-line JSON + newline

### UI thread requirement

All Eclipse JDT/M2E API calls must run on the UI thread. Every tool wraps its logic in:
```java
PlatformUI.getWorkbench().getDisplay().syncCall(() -> { ... });
```

### Claude Code integration

Claude Code doesn't support raw TCP. The `mcp-bridge.py` script bridges stdio (what Claude Code speaks) to TCP (what the Eclipse plugin speaks):

```bash
# Register with Claude Code (already done for this project):
claude mcp add --transport stdio EclipseMCP -- python3 /data/code/sources/eclipse-mcp/mcp-bridge.py
```

Claude Code launches the bridge as a subprocess. The bridge connects to `localhost:8099` and forwards stdin/stdout bidirectionally. Requires Eclipse to be running with the plugin installed.

### Logging

All communication is logged to `/data/tmp/eclipse-mcp.log` with timestamps. JSON messages (RECV, SEND) are pretty-printed. Monitor with:
```bash
tail -f /data/tmp/eclipse-mcp.log
```

## Development Guidelines

### Dependencies

- **NEVER** mix Maven dependencies with OSGi bundles
- Use only `Require-Bundle` in `MANIFEST.MF` for dependencies
- Jackson libraries are OSGi bundles: `com.fasterxml.jackson.core.jackson-*`
- All Eclipse dependencies must be OSGi bundles

### Adding a new tool

1. Create `YourTool.java` in `tools/` implementing `Tool` interface
2. Wrap Eclipse API calls in `PlatformUI.getWorkbench().getDisplay().syncCall()`
3. Return `Map<String, Object>` or `List<Map<String, Object>>`
4. Register in `ToolsCallHandler.registerTools()`: `tools.put("your_tool", new YourTool())`
5. Add JSON Schema definition in `ToolsListHandler.handle()`
6. The `ToolsCallHandler` automatically serializes results as MCP text content

### MCP response format

Tool results are automatically wrapped by `ToolsCallHandler` into the MCP-compliant format:
```json
{"content": [{"type": "text", "text": "<JSON-serialized tool result>"}], "isError": false}
```

### Common issues

- Eclipse APIs require UI thread — always use `syncCall()`
- `ASTParser.setResolveBindings(true)` requires a built project; unresolved bindings return null
- `SearchPattern.createPattern()` returns null for invalid patterns — check before calling `search()`
- Jackson `is*()` methods on model classes get serialized as boolean properties — use `@JsonIgnore`
