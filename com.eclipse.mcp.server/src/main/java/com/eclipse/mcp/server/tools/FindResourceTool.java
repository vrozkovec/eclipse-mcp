package com.eclipse.mcp.server.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceVisitor;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.ui.PlatformUI;

public class FindResourceTool implements Tool {

    @Override
    public Object execute(Map<String, Object> arguments) throws Exception {
        String resourceName = (String) arguments.get("resourceName");
        String fileExtension = (String) arguments.get("fileExtension");
        
        if (resourceName == null || resourceName.trim().isEmpty()) {
            throw new IllegalArgumentException("resourceName is required");
        }
        
        return PlatformUI.getWorkbench().getDisplay().syncCall(() -> {
            try {
                return findResources(resourceName, fileExtension);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
    
    private List<Map<String, Object>> findResources(String resourceName, String fileExtension) throws CoreException {
        List<Map<String, Object>> results = new ArrayList<>();
        
        String pattern = resourceName.toLowerCase();
        boolean isPattern = pattern.contains("*") || pattern.contains("?");
        
        IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
        
        for (IProject project : projects) {
            if (project.isOpen()) {
                project.accept(new IResourceVisitor() {
                    @Override
                    public boolean visit(IResource resource) throws CoreException {
                        if (resource instanceof IFile) {
                            IFile file = (IFile) resource;
                            String fileName = file.getName().toLowerCase();
                            
                            boolean matches = false;
                            if (isPattern) {
                                matches = matchesPattern(fileName, pattern);
                            } else {
                                matches = fileName.contains(pattern);
                            }
                            
                            if (matches) {
                                if (fileExtension == null || file.getFileExtension() != null 
                                    && file.getFileExtension().equalsIgnoreCase(fileExtension)) {
                                    
                                    Map<String, Object> result = new HashMap<>();
                                    result.put("fileName", file.getName());
                                    result.put("filePath", file.getFullPath().toString());
                                    result.put("projectName", file.getProject().getName());
                                    result.put("fileExtension", file.getFileExtension());
                                    result.put("location", file.getLocation().toString());
                                    
                                    try {
                                        result.put("size", file.getLocation().toFile().length());
                                        result.put("lastModified", file.getLocation().toFile().lastModified());
                                    } catch (Exception e) {
                                        // Ignore if we can't get file stats
                                    }
                                    
                                    results.add(result);
                                }
                            }
                        }
                        return true;
                    }
                });
            }
        }
        
        return results;
    }
    
    private boolean matchesPattern(String text, String pattern) {
        String regex = pattern
            .replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".");
        
        return text.matches(regex);
    }
}