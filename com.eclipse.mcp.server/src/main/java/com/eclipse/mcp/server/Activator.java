package com.eclipse.mcp.server;

import org.eclipse.ui.plugin.AbstractUIPlugin;
import org.osgi.framework.BundleContext;

public class Activator extends AbstractUIPlugin {

    public static final String PLUGIN_ID = "com.eclipse.mcp.server";
    
    private static Activator plugin;
    private MCPServer mcpServer;

    public Activator() {
    }

    @Override
    public void start(BundleContext context) throws Exception {
        super.start(context);
        plugin = this;
        
        mcpServer = new MCPServer();
        mcpServer.start();
    }

    @Override
    public void stop(BundleContext context) throws Exception {
        if (mcpServer != null) {
            mcpServer.stop();
        }
        
        plugin = null;
        super.stop(context);
    }

    public static Activator getDefault() {
        return plugin;
    }
    
    public MCPServer getMCPServer() {
        return mcpServer;
    }
}