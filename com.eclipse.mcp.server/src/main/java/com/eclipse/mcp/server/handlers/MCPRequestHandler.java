package com.eclipse.mcp.server.handlers;

public interface MCPRequestHandler {
    Object handle(Object params) throws Exception;
}