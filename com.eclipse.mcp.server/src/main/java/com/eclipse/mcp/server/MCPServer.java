package com.eclipse.mcp.server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.FileWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.eclipse.mcp.server.handlers.MCPRequestHandler;
import com.eclipse.mcp.server.protocol.MCPError;
import com.eclipse.mcp.server.protocol.MCPMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

public class MCPServer {
    
    private static final int DEFAULT_PORT = 8099;
    private static final Path LOG_FILE = Path.of("/data/tmp/eclipse-mcp.log");
    private static final DateTimeFormatter LOG_TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private ServerSocket serverSocket;
    private ExecutorService executor;
    private boolean running = false;
    private ObjectMapper objectMapper;
    private ObjectMapper prettyMapper;
    private ConcurrentHashMap<String, MCPRequestHandler> handlers;
    private PrintWriter logWriter;
    
    public MCPServer() {
        this.objectMapper = new ObjectMapper();
        this.prettyMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        this.handlers = new ConcurrentHashMap<>();
        this.executor = Executors.newCachedThreadPool();

        try {
            LOG_FILE.getParent().toFile().mkdirs();
            this.logWriter = new PrintWriter(new FileWriter(LOG_FILE.toFile(), true), true);
        } catch (IOException e) {
            System.err.println("Failed to open MCP log file: " + e.getMessage());
            this.logWriter = new PrintWriter(System.out, true);
        }

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

        log("SERVER", "MCP Server starting on port " + port);

        executor.submit(() -> {
            while (running && !serverSocket.isClosed()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    String clientAddr = clientSocket.getRemoteSocketAddress().toString();
                    log("CONNECT", "Client connected: " + clientAddr);
                    executor.submit(() -> handleClient(clientSocket));
                } catch (IOException e) {
                    if (running) {
                        log("ERROR", "Accept failed: " + e.getMessage());
                    }
                }
            }
        });

        log("SERVER", "MCP Server started on port " + port);
        System.out.println("MCP Server started on port " + port);
    }
    
    public void stop() {
        log("SERVER", "MCP Server stopping");
        running = false;

        if (serverSocket != null && !serverSocket.isClosed()) {
            try {
                serverSocket.close();
            } catch (IOException e) {
                log("ERROR", "Error closing server socket: " + e.getMessage());
            }
        }

        if (executor != null) {
            executor.shutdown();
        }

        if (logWriter != null) {
            logWriter.close();
        }
    }
    
    private void handleClient(Socket clientSocket) {
        String clientAddr = clientSocket.getRemoteSocketAddress().toString();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(clientSocket.getOutputStream()))) {

            String line;
            while ((line = reader.readLine()) != null && running) {
                logJson("RECV", line);
                processMessage(line, writer);
            }

        } catch (IOException e) {
            log("ERROR", "Client " + clientAddr + " error: " + e.getMessage());
        } finally {
            log("DISCONNECT", "Client disconnected: " + clientAddr);
            try {
                clientSocket.close();
            } catch (IOException e) {
                log("ERROR", "Error closing client socket: " + e.getMessage());
            }
        }
    }
    
    private void processMessage(String messageStr, BufferedWriter writer) {
        try {
            MCPMessage message = objectMapper.readValue(messageStr, MCPMessage.class);

            if (message.isRequest()) {
                log("REQUEST", "id=" + message.getId() + " method=" + message.getMethod());
                handleRequest(message, writer);
            } else if (message.isNotification()) {
                log("NOTIFICATION", "method=" + message.getMethod());
                handleNotification(message);
            }

        } catch (Exception e) {
            log("PARSE_ERROR", "Failed to parse: " + messageStr);
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
                log("RESULT", "id=" + request.getId() + " method=" + method + " completed");
                sendResponse(request.getId(), result, writer);
            } catch (Exception e) {
                log("ERROR", "id=" + request.getId() + " method=" + method + " failed: " + e.getMessage());
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
            logJson("SEND", responseStr);
            synchronized (writer) {
                writer.write(responseStr);
                writer.newLine();
                writer.flush();
            }
        } catch (IOException e) {
            log("ERROR", "Failed to send response: " + e.getMessage());
        }
    }

    private void sendError(Object id, int code, String message, BufferedWriter writer) {
        try {
            MCPError error = new MCPError(code, message);
            MCPMessage response = new MCPMessage(id, error);
            String responseStr = objectMapper.writeValueAsString(response);
            logJson("SEND_ERROR", responseStr);
            synchronized (writer) {
                writer.write(responseStr);
                writer.newLine();
                writer.flush();
            }
        } catch (IOException e) {
            log("ERROR", "Failed to send error response: " + e.getMessage());
        }
    }

    private void log(String category, String message) {
        String timestamp = LocalDateTime.now().format(LOG_TIME_FMT);
        logWriter.println(timestamp + " [" + category + "] " + message);
    }

    /**
     * Logs a message with pretty-printed JSON. Falls back to raw string if not valid JSON.
     */
    private void logJson(String category, String json) {
        String timestamp = LocalDateTime.now().format(LOG_TIME_FMT);
        try {
            Object parsed = objectMapper.readValue(json, Object.class);
            String pretty = prettyMapper.writeValueAsString(parsed);
            logWriter.println(timestamp + " [" + category + "]");
            logWriter.println(pretty);
        } catch (Exception e) {
            logWriter.println(timestamp + " [" + category + "] " + json);
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