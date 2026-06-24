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
import org.eclipse.jdt.core.IClasspathAttribute;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.launching.IJavaLaunchConfigurationConstants;
import org.eclipse.ui.PlatformUI;

public class RunTestsTool implements Tool {

    private static final String LOADER_JUNIT6 = "org.eclipse.jdt.junit.loader.junit6";

    @Override
    public Object execute(Map<String, Object> arguments) throws Exception {
        String projectName = (String) arguments.get("projectName");
        String testClass = (String) arguments.get("testClass");
        String testMethod = (String) arguments.get("testMethod");
        String packageName = (String) arguments.get("packageName");

        if (projectName == null || projectName.trim().isEmpty()) {
            throw new IllegalArgumentException("projectName is required");
        }
        if (testClass != null && packageName != null) {
            throw new IllegalArgumentException("Specify either testClass or packageName, not both");
        }

        return PlatformUI.getWorkbench().getDisplay().syncCall(() -> {
            try {
                return runTests(projectName, testClass, testMethod, packageName);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private Map<String, Object> runTests(String projectName, String testClass, String testMethod, String packageName)
            throws CoreException {
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
        if (packageName != null) {
            configName += "-pkg-" + packageName;
        }
        if (testMethod != null) {
            configName += "-" + testMethod;
        }

        ILaunchConfigurationWorkingCopy config = launchManager
            .getLaunchConfigurationType("org.eclipse.jdt.junit.launchconfig")
            .newInstance(null, configName);

        config.setAttribute(IJavaLaunchConfigurationConstants.ATTR_PROJECT_NAME, projectName);
        config.setAttribute("org.eclipse.jdt.junit.TEST_KIND", LOADER_JUNIT6);

        if (testClass != null) {
            config.setAttribute("org.eclipse.jdt.junit.CONTAINER", "");
            config.setAttribute("org.eclipse.jdt.junit.TESTNAME", testClass);
            if (testMethod != null) {
                config.setAttribute("org.eclipse.jdt.junit.TESTNAME", testClass + "." + testMethod);
            }
        } else if (packageName != null) {
            IPackageFragment pkg = findPackageFragment(javaProject, packageName);
            if (pkg == null) {
                throw new IllegalArgumentException(
                    "Package '" + packageName + "' not found in any source root of project " + projectName);
            }
            config.setAttribute("org.eclipse.jdt.junit.CONTAINER", pkg.getHandleIdentifier());
        } else {
            config.setAttribute("org.eclipse.jdt.junit.CONTAINER", javaProject.getHandleIdentifier());
        }

        ILaunchConfiguration savedConfig = config.doSave();

        IProgressMonitor monitor = new NullProgressMonitor();
        ILaunch launch = savedConfig.launch(ILaunchManager.RUN_MODE, monitor);

        Map<String, Object> result = new HashMap<>();
        result.put("status", "launched");
        result.put("configurationName", configName);
        result.put("projectName", projectName);
        result.put("testClass", testClass);
        result.put("packageName", packageName);
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

    /**
     * Resolves a package name to an {@link IPackageFragment} on the project's source path,
     * preferring fragments under a test source root (classpath entry with attribute {@code test=true}
     * set by M2E for {@code src/test/java}) so that {@code com.example} resolves to the test-side
     * package rather than the production one when both exist. Falls back to the first matching
     * production-side fragment.
     */
    private IPackageFragment findPackageFragment(IJavaProject javaProject, String packageName)
            throws JavaModelException {
        IPackageFragment fallback = null;
        for (IPackageFragmentRoot root : javaProject.getPackageFragmentRoots()) {
            if (root.getKind() != IPackageFragmentRoot.K_SOURCE) {
                continue;
            }
            IPackageFragment pkg = root.getPackageFragment(packageName);
            if (!pkg.exists() || !pkg.hasChildren()) {
                continue;
            }
            if (isTestSourceRoot(root)) {
                return pkg;
            }
            if (fallback == null) {
                fallback = pkg;
            }
        }
        return fallback;
    }

    private static boolean isTestSourceRoot(IPackageFragmentRoot root) throws JavaModelException {
        IClasspathEntry entry = root.getRawClasspathEntry();
        if (entry == null) {
            return false;
        }
        for (IClasspathAttribute attr : entry.getExtraAttributes()) {
            if ("test".equals(attr.getName()) && "true".equals(attr.getValue())) {
                return true;
            }
        }
        return false;
    }
}