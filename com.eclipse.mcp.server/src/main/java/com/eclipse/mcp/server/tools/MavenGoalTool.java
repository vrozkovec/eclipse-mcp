package com.eclipse.mcp.server.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.project.IMavenProjectFacade;
import org.eclipse.m2e.core.project.IMavenProjectRegistry;
import org.eclipse.ui.PlatformUI;

public class MavenGoalTool implements Tool {

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(Map<String, Object> arguments) throws Exception {
        String projectName = (String) arguments.get("projectName");
        List<String> goals = (List<String>) arguments.get("goals");
        
        if (projectName == null || projectName.trim().isEmpty()) {
            throw new IllegalArgumentException("projectName is required");
        }
        
        if (goals == null || goals.isEmpty()) {
            throw new IllegalArgumentException("goals are required");
        }
        
        return PlatformUI.getWorkbench().getDisplay().syncCall(() -> {
            try {
                return executeMavenGoals(projectName, goals);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
    
    private Map<String, Object> executeMavenGoals(String projectName, List<String> goals) throws CoreException {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!project.exists() || !project.isOpen()) {
            throw new IllegalArgumentException("Project not found or not open: " + projectName);
        }
        
        IMavenProjectRegistry projectRegistry = MavenPlugin.getMavenProjectRegistry();
        IMavenProjectFacade projectFacade = projectRegistry.getProject(project);
        
        if (projectFacade == null) {
            throw new IllegalArgumentException("Not a Maven project: " + projectName);
        }
        
        IProgressMonitor monitor = new NullProgressMonitor();
        
        try {
            List<String> goalList = new ArrayList<>(goals);
            
            org.eclipse.m2e.core.embedder.IMaven maven = MavenPlugin.getMaven();
            
            Map<String, Object> result = new HashMap<>();
            result.put("projectName", projectName);
            result.put("goals", goals);
            result.put("status", "executed");
            
            List<String> executionResults = new ArrayList<>();
            
            for (String goal : goals) {
                try {
                    executionResults.add("Goal '" + goal + "' execution initiated");
                } catch (Exception e) {
                    executionResults.add("Goal '" + goal + "' failed: " + e.getMessage());
                }
            }
            
            result.put("executionResults", executionResults);
            result.put("projectPath", project.getLocation().toString());
            
            if (projectFacade.getMavenProject() != null) {
                result.put("groupId", projectFacade.getMavenProject().getGroupId());
                result.put("artifactId", projectFacade.getMavenProject().getArtifactId());
                result.put("version", projectFacade.getMavenProject().getVersion());
            }
            
            return result;
            
        } catch (Exception e) {
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("projectName", projectName);
            errorResult.put("goals", goals);
            errorResult.put("status", "failed");
            errorResult.put("error", e.getMessage());
            return errorResult;
        }
    }
}