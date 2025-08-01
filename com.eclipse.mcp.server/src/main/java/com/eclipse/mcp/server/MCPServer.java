package com.eclipse.mcp.server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.eclipse.mcp.server.handlers.MCPRequestHandler;
import com.eclipse.mcp.server.protocol.MCPError;
import com.eclipse.mcp.server.protocol.MCPMessage;
import com.fasterxml.jackson.databind.ObjectMapper;

public class MCPServer {
    
    private static final int DEFAULT_PORT = 8080;
    private ServerSocket serverSocket;
    private ExecutorService executor;
    private boolean running = false;
    private ObjectMapper objectMapper;
    private ConcurrentHashMap<String, MCPRequestHandler> handlers;
    
    public MCPServer() {
        this.objectMapper = new ObjectMapper();
        this.handlers = new ConcurrentHashMap<>();
        this.executor = Executors.newCachedThreadPool();
        
        registerHandlers();
    }
    
    private void registerHandlers() {
        handlers.put("tools/list", new com.eclipse.mcp.server.handlers.ToolsListHandler());
        handlers.put("tools/call", new com.eclipse.mcp.server.handlers.ToolsCallHandler());
        handlers.put("resources/list", new com.eclipse.mcp.server.handlers.ResourcesListHandler());
        handlers.put("resources/read", new com.eclipse.mcp.server.handlers.ResourcesReadHandler());
        handlers.put("initialize", new com.eclipse.mcp.server.handlers.InitializeHandler());
    }
    
    public void start() throws IOException {
        if (running) {
            return;
        }
        
        int port = getConfiguredPort();
        serverSocket = new ServerSocket(port);
        running = true;
        
        executor.submit(() -> {
            while (running && !serverSocket.isClosed()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    executor.submit(() -> handleClient(clientSocket));
                } catch (IOException e) {
                    if (running) {
                        e.printStackTrace();
                    }
                }
            }
        });
        
        System.out.println("MCP Server started on port " + port);
    }
    
    public void stop() {
        running = false;
        
        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        
        if (executor != null) {
            executor.shutdown();
        }
    }
    
    private void handleClient(Socket clientSocket) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream()))) {
            
            String line;
            while ((line = reader.readLine()) != null && running) {
                processMessage(line, writer);
            }
            
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                clientSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
    
    private void processMessage(String messageStr, BufferedWriter writer) {
        try {
            MCPMessage message = objectMapper.readValue(messageStr, MCPMessage.class);
            
            if (message.isRequest()) {
                handleRequest(message, writer);
            } else if (message.isNotification()) {
                handleNotification(message);
            }
            
        } catch (Exception e) {
            sendError(null, MCPError.PARSE_ERROR, "Parse error", writer);
        }
    }
    
    private void handleRequest(MCPMessage request, BufferedWriter writer) {
        String method = request.getMethod();
        MCPRequestHandler handler = handlers.get(method);
        
        if (handler == null) {
            sendError(request.getId(), MCPError.METHOD_NOT_FOUND, "Method not found: " + method, writer);
            return;
        }
        
        executor.submit(() -> {
            try {
                Object result = handler.handle(request.getParams());
                sendResponse(request.getId(), result, writer);
            } catch (Exception e) {
                sendError(request.getId(), MCPError.INTERNAL_ERROR, e.getMessage(), writer);
            }
        });
    }
    
    private void handleNotification(MCPMessage notification) {
        String method = notification.getMethod();
        MCPRequestHandler handler = handlers.get(method);
        
        if (handler != null) {
            executor.submit(() -> {
                try {
                    handler.handle(notification.getParams());
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }
    }
    
    private void sendResponse(Object id, Object result, BufferedWriter writer) {
        try {
            MCPMessage response = new MCPMessage(id, result);
            String responseStr = objectMapper.writeValueAsString(response);
            synchronized (writer) {
                writer.write(responseStr);
                writer.newLine();
                writer.flush();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    private void sendError(Object id, int code, String message, BufferedWriter writer) {
        try {
            MCPError error = new MCPError(code, message);
            MCPMessage response = new MCPMessage(id, error);
            String responseStr = objectMapper.writeValueAsString(response);
            synchronized (writer) {
                writer.write(responseStr);
                writer.newLine();
                writer.flush();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    private int getConfiguredPort() {
        String portStr = System.getProperty("mcp.server.port", String.valueOf(DEFAULT_PORT));
        try {
            return Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            return DEFAULT_PORT;
        }
    }
}