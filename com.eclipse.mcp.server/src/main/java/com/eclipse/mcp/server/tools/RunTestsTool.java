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
import org.eclipse.debug.core.ILaunch;
import org.eclipse.debug.core.ILaunchConfiguration;
import org.eclipse.debug.core.ILaunchConfigurationWorkingCopy;
import org.eclipse.debug.core.ILaunchManager;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.junit.launcher.JUnitLaunchConfigurationDelegate;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;
import org.eclipse.ui.PlatformUI;

public class RunTestsTool implements Tool {

    @Override
    public Object execute(Map<String, Object> arguments) throws Exception {
        String projectName = (String) arguments.get("projectName");
        String testClass = (String) arguments.get("testClass");
        String testMethod = (String) arguments.get("testMethod");
        
        if (projectName == null || projectName.trim().isEmpty()) {
            throw new IllegalArgumentException("projectName is required");
        }
        
        return PlatformUI.getWorkbench().getDisplay().syncCall(() -> {
            try {
                return runTests(projectName, testClass, testMethod);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
    
    private Map<String, Object> runTests(String projectName, String testClass, String testMethod) throws CoreException {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!project.exists() || !project.isOpen()) {
            throw new IllegalArgumentException("Project not found or not open: " + projectName);
        }
        
        IJavaProject javaProject = JavaCore.create(project);
        if (!javaProject.exists()) {
            throw new IllegalArgumentException("Not a Java project: " + projectName);
        }
        
        ILaunchManager launchManager = org.eclipse.debug.core.DebugPlugin.getDefault().getLaunchManager();
        
        String configName = "MCP-JUnit-" + projectName;
        if (testClass != null) {
            configName += "-" + testClass;
        }
        if (testMethod != null) {
            configName += "-" + testMethod;
        }
        
        ILaunchConfigurationWorkingCopy config = launchManager
            .getLaunchConfigurationType("org.eclipse.jdt.junit.launchconfig")
            .newInstance(null, configName);
        
        config.setAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, projectName);
        
        if (testClass != null) {
            config.setAttribute("org.eclipse.jdt.junit.CONTAINER", "");
            config.setAttribute("org.eclipse.jdt.junit.TEST_KIND", "org.eclipse.jdt.junit.loader.junit4");
            config.setAttribute("org.eclipse.jdt.junit.TESTNAME", testClass);
            
            if (testMethod != null) {
                config.setAttribute("org.eclipse.jdt.junit.TESTNAME", testClass + "." + testMethod);
            }
        } else {
            String containerHandle = "=" + projectName;
            config.setAttribute("org.eclipse.jdt.junit.CONTAINER", containerHandle);
            config.setAttribute("org.eclipse.jdt.junit.TEST_KIND", "org.eclipse.jdt.junit.loader.junit4");
        }
        
        ILaunchConfiguration savedConfig = config.doSave();
        
        IProgressMonitor monitor = new NullProgressMonitor();
        ILaunch launch = savedConfig.launch(ILaunchManager.RUN_MODE, monitor);
        
        Map<String, Object> result = new HashMap<>();
        result.put("status", "launched");
        result.put("configurationName", configName);
        result.put("projectName", projectName);
        result.put("testClass", testClass);
        result.put("testMethod", testMethod);
        result.put("launchMode", ILaunchManager.RUN_MODE);
        
        List<String> launchedProcesses = new ArrayList<>();
        if (launch.getProcesses() != null) {
            for (int i = 0; i < launch.getProcesses().length; i++) {
                launchedProcesses.add(launch.getProcesses()[i].getLabel());
            }
        }
        result.put("processes", launchedProcesses);
        
        return result;
    }
}