package com.eclipse.mcp.server.tools;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.ui.PlatformUI;

public class SourceActionsTool implements Tool {

    @Override
    public Object execute(Map<String, Object> arguments) throws Exception {
        String filePath = (String) arguments.get("filePath");
        String action = (String) arguments.get("action");
        
        if (filePath == null || filePath.trim().isEmpty()) {
            throw new IllegalArgumentException("filePath is required");
        }
        
        if (action == null || action.trim().isEmpty()) {
            throw new IllegalArgumentException("action is required");
        }
        
        return PlatformUI.getWorkbench().getDisplay().syncCall(() -> {
            try {
                return executeSourceAction(filePath, action);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
    
    private Map<String, Object> executeSourceAction(String filePath, String action) throws Exception {
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
        
        try {
            switch (action) {
                case "generate_getters_setters":
                    result.put("status", "Getter/Setter generation would be triggered");
                    result.put("description", "This would open the Generate Getters and Setters dialog");
                    break;
                    
                case "generate_constructor":
                    result.put("status", "Constructor generation would be triggered");
                    result.put("description", "This would open the Generate Constructor using Fields dialog");
                    break;
                    
                case "generate_toString":
                    result.put("status", "toString() generation would be triggered");
                    result.put("description", "This would open the Generate toString() dialog");
                    break;
                    
                case "generate_hashcode_equals":
                    result.put("status", "hashCode() and equals() generation would be triggered");
                    result.put("description", "This would open the Generate hashCode() and equals() dialog");
                    break;
                    
                default:
                    throw new IllegalArgumentException("Unknown action: " + action + 
                        ". Supported actions: generate_getters_setters, generate_constructor, generate_toString, generate_hashcode_equals");
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