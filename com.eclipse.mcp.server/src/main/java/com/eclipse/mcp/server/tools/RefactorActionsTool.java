package com.eclipse.mcp.server.tools;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.ui.PlatformUI;

public class RefactorActionsTool implements Tool {

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(Map<String, Object> arguments) throws Exception {
        String filePath = (String) arguments.get("filePath");
        String action = (String) arguments.get("action");
        Map<String, Object> parameters = (Map<String, Object>) arguments.get("parameters");
        
        if (filePath == null || filePath.trim().isEmpty()) {
            throw new IllegalArgumentException("filePath is required");
        }
        
        if (action == null || action.trim().isEmpty()) {
            throw new IllegalArgumentException("action is required");
        }
        
        return PlatformUI.getWorkbench().getDisplay().syncCall(() -> {
            try {
                return executeRefactorAction(filePath, action, parameters);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
    
    private Map<String, Object> executeRefactorAction(String filePath, String action, Map<String, Object> parameters) throws Exception {
        IFile file = ResourcesPlugin.getWorkspace().getRoot().getFile(new Path(filePath));
        if (!file.exists()) {
            throw new IllegalArgumentException("File not found: " + filePath);
        }
        
        ICompilationUnit compilationUnit = JavaCore.createCompilationUnitFrom(file);
        if (compilationUnit == null) {
            throw new IllegalArgumentException("Not a Java file: " + filePath);
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("filePath", filePath);
        result.put("action", action);
        result.put("parameters", parameters);
        
        try {
            switch (action) {
                case "rename":
                    result.put("status", "Rename refactoring would be triggered");
                    result.put("description", "This would open the Rename dialog for the selected element");
                    if (parameters != null && parameters.containsKey("newName")) {
                        result.put("newName", parameters.get("newName"));
                    }
                    break;
                    
                case "extract_method":
                    result.put("status", "Extract Method refactoring would be triggered");
                    result.put("description", "This would open the Extract Method dialog");
                    if (parameters != null && parameters.containsKey("methodName")) {
                        result.put("methodName", parameters.get("methodName"));
                    }
                    break;
                    
                case "extract_variable":
                    result.put("status", "Extract Variable refactoring would be triggered");
                    result.put("description", "This would open the Extract Variable dialog");
                    if (parameters != null && parameters.containsKey("variableName")) {
                        result.put("variableName", parameters.get("variableName"));
                    }
                    break;
                    
                case "inline":
                    result.put("status", "Inline refactoring would be triggered");
                    result.put("description", "This would inline the selected element");
                    break;
                    
                case "move":
                    result.put("status", "Move refactoring would be triggered");
                    result.put("description", "This would open the Move dialog");
                    if (parameters != null && parameters.containsKey("destination")) {
                        result.put("destination", parameters.get("destination"));
                    }
                    break;
                    
                default:
                    throw new IllegalArgumentException("Unknown action: " + action + 
                        ". Supported actions: rename, extract_method, extract_variable, inline, move");
            }
            
            result.put("compilationUnitName", compilationUnit.getElementName());
            if (compilationUnit.getTypes().length > 0) {
                result.put("primaryTypeName", compilationUnit.getTypes()[0].getElementName());
            }
            
        } catch (Exception e) {
            result.put("status", "failed");
            result.put("error", e.getMessage());
        }
        
        return result;
    }
}