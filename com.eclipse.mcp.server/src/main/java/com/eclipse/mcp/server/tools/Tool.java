package com.eclipse.mcp.server.tools;

import java.util.Map;

public interface Tool {
    Object execute(Map<String, Object> arguments) throws Exception;
}