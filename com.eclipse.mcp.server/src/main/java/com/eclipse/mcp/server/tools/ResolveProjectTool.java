package com.eclipse.mcp.server.tools;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.ui.PlatformUI;

/**
 * Resolves an absolute filesystem path to the Eclipse workspace project that contains it.
 *
 * <p>Returns the project name, location, and open status. Useful for determining
 * which {@code projectName} to pass to tools like {@code get_problems},
 * {@code clean_workspace}, or {@code refresh_workspace}.</p>
 */
public class ResolveProjectTool implements Tool {

    @Override
    public Object execute(Map<String, Object> arguments) throws Exception {
        String path = (String) arguments.get("path");
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Required parameter 'path' is missing");
        }

        return PlatformUI.getWorkbench().getDisplay().syncCall(() -> {
            try {
                return resolveProject(path);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Resolves the given filesystem path to the most specific (deepest) enclosing Eclipse project.
     *
     * <p>When multiple projects have locations that are prefixes of the given path
     * (e.g. {@code berries-parent} at {@code /speedy/dev/name.berries} and
     * {@code eu.svetit.skyport.common} at {@code .../skyport-common}),
     * the project with the longest matching location wins.</p>
     */
    private Map<String, Object> resolveProject(String path) {
        String normalizedPath = Path.of(path).normalize().toString();
        IProject bestMatch = null;
        int bestMatchLength = -1;

        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            IPath location = project.getLocation();
            if (location == null) {
                continue;
            }
            String projectPath = location.toOSString();
            if (normalizedPath.equals(projectPath) || normalizedPath.startsWith(projectPath + "/")) {
                if (projectPath.length() > bestMatchLength) {
                    bestMatch = project;
                    bestMatchLength = projectPath.length();
                }
            }
        }

        if (bestMatch == null) {
            throw new IllegalArgumentException("No Eclipse project found for path: " + path);
        }

        return buildResult(bestMatch);
    }

    /**
     * Builds the result map for a resolved project.
     */
    private Map<String, Object> buildResult(IProject project) {
        Map<String, Object> result = new HashMap<>();
        result.put("projectName", project.getName());
        result.put("isOpen", project.isOpen());
        if (project.getLocation() != null) {
            result.put("projectLocation", project.getLocation().toString());
        }
        return result;
    }
}
