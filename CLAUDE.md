# Claude Instructions for Eclipse MCP Plugin

This project is an Eclipse plugin that provides MCP (Model Context Protocol) server functionality.

## Project Overview
- **Type**: Eclipse OSGi Plugin with Tycho build
- **Language**: Java 21
- **Framework**: Eclipse Platform, OSGi
- **Build System**: Maven Tycho
- **MCP Protocol**: JSON-RPC 2.0 over TCP

## Development Guidelines

### Dependencies
- **NEVER** mix Maven dependencies with OSGi bundles
- Use only `Require-Bundle` in MANIFEST.MF for dependencies  
- Jackson libraries are OSGi bundles: `com.fasterxml.jackson.core.jackson-*`
- All Eclipse dependencies must be OSGi bundles

### Code Structure
- Main plugin: `com.eclipse.mcp.server/`
- MCP tools: `com.eclipse.mcp.server.tools/`
- Protocol classes: `com.eclipse.mcp.server.protocol/`
- Eclipse integration: Deep JDT, M2E, Debug API usage

### Build Commands
- Build: `mvn clean verify`
- Test: `mvn clean test`
- Package: `mvn clean package`

### MCP Tools Implemented
1. `find_type` - Java type search (Ctrl+Shift+T equivalent)
2. `find_resource` - Resource search (Ctrl+Shift+R equivalent) 
3. `run_tests` - JUnit test execution
4. `get_problems` - Project problems/errors
5. `source_actions` - Code generation (Alt+Shift+S)
6. `refactor_actions` - Refactoring (Alt+Shift+T)
7. `maven_goal` - Maven goal execution
8. `maven_update_project` - Maven project updates

### Key Files
- `MANIFEST.MF` - OSGi bundle configuration
- `plugin.xml` - Eclipse extension points
- `pom.xml` - Tycho build configuration
- `MCPServer.java` - Main server implementation

### Testing
- Use Eclipse Application run configuration for testing
- Connect MCP client to localhost:8080
- Test with JSON-RPC 2.0 messages

### Common Issues
- Jackson must be OSGi bundles, not Maven deps
- Eclipse APIs require UI thread for many operations
- Use `PlatformUI.getWorkbench().getDisplay().syncCall()` for UI thread execution

## Git Workflow
- Main branch: `main`
- Feature branches: `feature/tool-name` or `feature/description`
- Bug fixes: `fix/issue-description`
- Always create PRs for changes