package com.eclipse.mcp.server.tools;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.project.IMavenProjectFacade;
import org.eclipse.m2e.core.project.IMavenProjectRegistry;
import java.util.List;
import org.eclipse.ui.PlatformUI;

public class MavenUpdateProjectTool implements Tool {

    @Override
    public Object execute(Map<String, Object> arguments) throws Exception {
        String projectName = (String) arguments.get("projectName");
        Boolean forceUpdate = (Boolean) arguments.getOrDefault("forceUpdate", false);
        
        if (projectName == null || projectName.trim().isEmpty()) {
            throw new IllegalArgumentException("projectName is required");
        }
        
        return PlatformUI.getWorkbench().getDisplay().syncCall(() -> {
            try {
                return updateMavenProject(projectName, forceUpdate);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
    
    private Map<String, Object> updateMavenProject(String projectName, boolean forceUpdate) throws CoreException {
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
            MavenPlugin.getMavenProjectRegistry().refresh(List.of(project.getFile("pom.xml")), monitor);
            
            Map<String, Object> result = new HashMap<>();
            result.put("projectName", projectName);
            result.put("status", "updated");
            result.put("forceUpdate", forceUpdate);
            result.put("projectPath", project.getLocation().toString());
            
            if (projectFacade.getMavenProject() != null) {
                result.put("groupId", projectFacade.getMavenProject().getGroupId());
                result.put("artifactId", projectFacade.getMavenProject().getArtifactId());
                result.put("version", projectFacade.getMavenProject().getVersion());
                result.put("packaging", projectFacade.getMavenProject().getPackaging());
            }
            
            IMavenProjectFacade updatedFacade = projectRegistry.getProject(project);
            if (updatedFacade != null) {
                result.put("lastUpdated", System.currentTimeMillis());
                result.put("pomFile", updatedFacade.getPom().getFullPath().toString());
            }
            
            return result;
            
        } catch (Exception e) {
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("projectName", projectName);
            errorResult.put("status", "failed");
            errorResult.put("error", e.getMessage());
            errorResult.put("forceUpdate", forceUpdate);
            return errorResult;
        }
    }
}