# Eclipse MCP Server Plugin

An Eclipse plugin that provides MCP (Model Context Protocol) server functionality, allowing external clients to interact with Eclipse IDE features programmatically.

## Features

This plugin exposes the following Eclipse functionality through MCP tools:

### Core Tools
- **find_type**: Locate Java types by name (similar to "Open Type" - Ctrl+Shift+T)
- **find_resource**: Locate resources by name (similar to "Open Resource" - Ctrl+Shift+R)
- **run_tests**: Run JUnit tests for individual files or methods
- **get_problems**: Read problems (errors/warnings) by project

### Source Actions (Alt+Shift+S equivalent)
- **source_actions**: Execute Eclipse source actions
  - Generate getters/setters
  - Generate constructors
  - Generate toString()
  - Generate hashCode() and equals()

### Refactoring Actions (Alt+Shift+T equivalent)
- **refactor_actions**: Execute Eclipse refactoring actions
  - Rename
  - Extract method
  - Extract variable
  - Inline
  - Move

### Maven Integration
- **maven_goal**: Run Maven goals on projects
- **maven_update_project**: Update Maven project configuration

## Architecture

The plugin consists of:

- **MCP Server**: JSON-RPC 2.0 compliant server running on configurable port (default: 8080)
- **Tool System**: Modular tool implementations for each Eclipse feature
- **Resource System**: Access to workspace projects and files
- **Eclipse Integration**: Deep integration with Eclipse JDT, Maven (M2E), and debug frameworks

## Building

This project uses Eclipse Tycho for building. To build the plugin:

```bash
mvn clean install
```

To build for specific target platform:

```bash
mvn clean install -Dtycho.targetPlatform=/path/to/eclipse/installation
```

## Installation

### From Update Site
1. In Eclipse, go to Help → Install New Software
2. Add the update site URL (built in `com.eclipse.mcp.updatesite/target/repository`)
3. Select "MCP (Model Context Protocol)" category
4. Install and restart Eclipse

### Manual Installation
1. Copy the built JAR from `com.eclipse.mcp.server/target/` to Eclipse's `dropins` folder
2. Restart Eclipse

## Configuration

Configure the MCP Server through Eclipse preferences:
- Go to Window → Preferences → MCP Server
- Enable/disable the server
- Configure server port (default: 8080)
- Set auto-start behavior

## Usage

Once installed and configured, the MCP server will start automatically when Eclipse starts. External MCP clients can connect to the server on the configured port.

### Example MCP Client Usage

```json
{
  "jsonrpc": "2.0",
  "id": 1,
  "method": "tools/call",
  "params": {
    "name": "find_type",
    "arguments": {
      "typeName": "String",
      "caseSensitive": false
    }
  }
}
```

## Requirements

- Eclipse 2024-12 or later
- Java 21 or later
- Maven projects require M2E plugin
- Jackson OSGi bundles (typically included in Eclipse IDE packages)

## Development

### Project Structure

```
eclipse-mcp/
├── com.eclipse.mcp.server/          # Main plugin bundle
│   ├── src/main/java/
│   │   └── com/eclipse/mcp/server/
│   │       ├── handlers/            # MCP message handlers
│   │       ├── tools/              # Tool implementations
│   │       ├── protocol/           # MCP protocol classes
│   │       ├── startup/            # Eclipse startup integration
│   │       └── preferences/        # Preference pages
│   ├── META-INF/MANIFEST.MF        # OSGi bundle manifest
│   ├── plugin.xml                  # Eclipse plugin configuration
│   └── build.properties            # Build configuration
├── com.eclipse.mcp.feature/         # Eclipse feature definition
├── com.eclipse.mcp.updatesite/      # P2 update site
└── pom.xml                         # Parent Maven POM
```

### Adding New Tools

1. Implement the `Tool` interface in `com.eclipse.mcp.server.tools`
2. Register the tool in `ToolsCallHandler`
3. Add tool definition in `ToolsListHandler`
4. Update documentation

### Testing

The plugin can be tested by:
1. Running as Eclipse Application from the development environment
2. Using a MCP client to connect to port 8080
3. Sending MCP requests to test tool functionality

## License

Eclipse Public License v2.0

## Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Add tests if applicable
5. Submit a pull request