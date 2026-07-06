# Eclipse MCP Server Plugin

An Eclipse plugin that provides MCP (Model Context Protocol) server functionality, allowing external clients (like Claude Code) to interact with Eclipse IDE features programmatically.

## Features

This plugin exposes **22 tools** through the MCP protocol, covering code search, workspace management, code quality, application lifecycle, and Maven integration.

### Search & Navigation
| Tool | Description | Eclipse Equivalent |
|------|-------------|--------------------|
| `find_type` | Find Java types by name/pattern | Ctrl+Shift+T |
| `find_resource` | Find workspace resources by name/path | Ctrl+Shift+R |
| `find_references` | Find all references to a type, method, or field | Ctrl+Shift+G |
| `analyze_type_dependencies` | Analyze all types referenced by a given type, with optional transitive analysis | — |

### Workspace Management
| Tool | Description | Eclipse Equivalent |
|------|-------------|--------------------|
| `list_projects` | List all projects in the workspace with name, location, and open status | — |
| `resolve_project` | Resolve a filesystem path to its containing Eclipse project | — |
| `refresh_workspace` | Refresh projects (picks up external file changes) | F5 |
| `clean_workspace` | Clean/rebuild projects; optional `mavenClean` runs `mvn clean` first (then refresh, then clean) | Project > Clean |
| `get_problems` | Get compilation errors/warnings for a project | Problems view |
| `get_build_status` | Check if Eclipse is currently building; includes error/warning counts | — |

### Code Quality
| Tool | Description | Eclipse Equivalent |
|------|-------------|--------------------|
| `organize_imports` | Remove unused imports, add missing ones, sort per project settings | Ctrl+Shift+O |
| `format_code` | Format Java source files using project-specific formatter settings | Ctrl+Shift+F |
| `cleanup_code` | Apply Source > Clean Up rules (@Override, lambdas, pattern matching, etc.) | Source > Clean Up |

### Source Actions & Refactoring
| Tool | Description | Eclipse Equivalent |
|------|-------------|--------------------|
| `source_actions` | Generate getters/setters, constructors, toString, hashCode/equals | Alt+Shift+S |
| `refactor_actions` | Rename, extract method/variable, inline, move | Alt+Shift+T |
| `rename_package` | Rename a package across the workspace | Refactor > Rename |

### Application Lifecycle
| Tool | Description | Eclipse Equivalent |
|------|-------------|--------------------|
| `run_tests` | Run JUnit tests for a project, package, or class | — |
| `run_test_class` | Run a single test class (or method) and return per-test results | — |
| `debug_relaunch` | Stop running app and relaunch last (or named) config in debug mode | Ctrl+F2, F11 |
| `stop_java_application` | Stop all running Java applications | Ctrl+F2 |

### Maven Integration
| Tool | Description |
|------|-------------|
| `maven_goal` | Run Maven goals on a project via an m2e launch (`Run As > Maven build`); blocks and returns exit code, BUILD SUCCESS/FAILURE and output tail. `goals: ["clean"]` = the `Maven clean` launch shortcut |
| `maven_update_project` | Update Maven project configuration (Alt+F5) |

## Architecture

The plugin consists of:

- **MCP Server**: JSON-RPC 2.0 compliant TCP server on configurable port (default: 8099)
- **Tool System**: Modular tool implementations for each Eclipse feature
- **Resource System**: Access to workspace projects and files
- **Eclipse Integration**: Deep integration with Eclipse JDT, Maven (M2E), and debug frameworks
- **stdio Bridge**: Python bridge script (`mcp-bridge.py`) for Claude Code integration

### Claude Code Integration

Claude Code doesn't support raw TCP. The `mcp-bridge.py` script bridges stdio to TCP:

```bash
# Register with Claude Code:
claude mcp add --transport stdio EclipseMCP -- python3 /path/to/eclipse-mcp/mcp-bridge.py
```

The bridge connects to `localhost:8099` and forwards messages bidirectionally. Requires Eclipse to be running with the plugin installed.

## Building

This project uses Eclipse Tycho. Java 21 is required.

```bash
export JAVA_HOME=/usr/lib/jvm/temurin-21-jdk-amd64/
mvn clean install
```

## Installation

### From Update Site
1. In Eclipse, go to Help > Install New Software
2. Add the update site URL (built in `com.eclipse.mcp.updatesite/target/repository`)
3. Select "MCP (Model Context Protocol)" category
4. Install and restart Eclipse

### Manual Installation
1. Copy the built JAR from `com.eclipse.mcp.server/target/` to Eclipse's `dropins` folder
2. Restart Eclipse

## Configuration

Configure the MCP Server through Eclipse preferences:
- Go to Window > Preferences > MCP Server
- Enable/disable the server
- Configure server port (default: 8099)
- Set auto-start behavior

## Project Structure

```
eclipse-mcp/
├── com.eclipse.mcp.server/          # Main plugin bundle
│   ├── src/main/java/
│   │   └── com/eclipse/mcp/server/
│   │       ├── MCPServer.java       # TCP server, JSON-RPC routing
│   │       ├── handlers/            # MCP message handlers
│   │       ├── tools/               # Tool implementations (22 tools)
│   │       ├── protocol/            # MCP protocol classes
│   │       ├── startup/             # Eclipse startup integration
│   │       └── preferences/         # Preference pages
│   ├── META-INF/MANIFEST.MF         # OSGi bundle manifest
│   ├── plugin.xml                   # Eclipse plugin configuration
│   └── build.properties             # Build configuration
├── com.eclipse.mcp.feature/         # Eclipse feature definition
├── com.eclipse.mcp.updatesite/      # P2 update site
├── mcp-bridge.py                    # stdio-to-TCP bridge for Claude Code
└── pom.xml                          # Parent Maven POM
```

## Adding New Tools

1. Implement the `Tool` interface in `com.eclipse.mcp.server.tools`
2. Wrap Eclipse API calls in `PlatformUI.getWorkbench().getDisplay().syncCall()`
3. Register the tool in `ToolsCallHandler.registerTools()`
4. Add JSON Schema definition in `ToolsListHandler.handle()`

## Requirements

- Eclipse 2024-12 or later
- Java 21 or later
- Maven projects require M2E plugin
- Jackson OSGi bundles (typically included in Eclipse IDE packages)

## License

Eclipse Public License v2.0
