package com.eclipse.mcp.server.startup;

import org.eclipse.ui.IStartup;

import com.eclipse.mcp.server.Activator;

public class MCPServerStartup implements IStartup {

    @Override
    public void earlyStartup() {
        Activator activator = Activator.getDefault();
        if (activator != null && activator.getMCPServer() != null) {
            System.out.println("MCP Server startup completed");
        }
    }
}