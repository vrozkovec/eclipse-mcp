package com.eclipse.mcp.server.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.ui.PlatformUI;

/**
 * Cleans projects in the workspace, equivalent to Project &gt; Clean in Eclipse.
 *
 * <p>If {@code projectName} is provided, only that project is cleaned.
 * Otherwise all open projects are cleaned.</p>
 *
 * <p>If {@code mavenClean} is {@code true}, {@code mvn clean} is run first through an
 * m2e launch (see {@link MavenLauncher}), followed by a refresh from disk, and only then
 * the Eclipse clean — i.e. the phases are: mvn clean &rarr; refresh &rarr; CLEAN_BUILD.
 * Projects without the Maven nature are skipped in the Maven phase.</p>
 */
public class CleanWorkspaceTool implements Tool {

    /** Per-project timeout for the Maven clean phase, in seconds. */
    private static final long MAVEN_CLEAN_TIMEOUT_SECONDS = 120L;

    @Override
    public Object execute(Map<String, Object> arguments) throws Exception {
        String projectName = (String) arguments.get("projectName");
        boolean mavenClean = Boolean.TRUE.equals(arguments.get("mavenClean"));

        List<Map<String, Object>> mavenResults = null;
        if (mavenClean) {
            // Both phases run on the request thread: the Maven launches manage their own
            // UI-thread hops and the resources API is thread-safe — keeping the possibly
            // slow work out of syncCall leaves the Eclipse UI responsive.
            mavenResults = runMavenClean(projectName);
            refreshTargets(projectName);
        }

        Map<String, Object> result = PlatformUI.getWorkbench().getDisplay().syncCall(() -> {
            try {
                if (projectName != null && !projectName.isBlank()) {
                    return cleanProject(projectName);
                }
                return cleanAllProjects();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        if (mavenResults != null) {
            result.put("mavenClean", mavenResults);
            boolean anyFailed = mavenResults.stream()
                    .anyMatch(entry -> Boolean.FALSE.equals(entry.get("success")));
            if (anyFailed) {
                result.put("status", "cleaned_with_maven_failures");
            }
        }
        return result;
    }

    /**
     * Runs {@code mvn clean} on the given project, or sequentially on every open Maven
     * project when no project name is given. Failures do not abort the loop; each
     * processed project contributes one result entry and projects without the Maven
     * nature are reported as {@code skipped_not_maven}.
     *
     * @param projectName optional project scope
     * @return one result entry per processed project
     * @throws Exception if the single explicitly named project cannot be launched
     */
    private List<Map<String, Object>> runMavenClean(String projectName) throws Exception {
        if (projectName != null && !projectName.isBlank()) {
            return List.of(MavenLauncher.run(projectName, List.of("clean"), MAVEN_CLEAN_TIMEOUT_SECONDS));
        }

        // Snapshot the open projects and their Maven status on the UI thread, then run
        // the potentially long Maven launches sequentially on the request thread.
        Map<String, Boolean> mavenStatusByProject = PlatformUI.getWorkbench().getDisplay().syncCall(() -> {
            Map<String, Boolean> statuses = new LinkedHashMap<>();
            for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
                if (project.isOpen()) {
                    statuses.put(project.getName(),
                            MavenPlugin.getMavenProjectRegistry().getProject(project) != null);
                }
            }
            return statuses;
        });

        List<Map<String, Object>> results = new ArrayList<>();
        for (Map.Entry<String, Boolean> entry : mavenStatusByProject.entrySet()) {
            String name = entry.getKey();
            if (!entry.getValue()) {
                results.add(Map.of("projectName", name, "status", "skipped_not_maven"));
                continue;
            }
            try {
                Map<String, Object> mavenResult = MavenLauncher.run(name, List.of("clean"),
                        MAVEN_CLEAN_TIMEOUT_SECONDS);
                if (Boolean.TRUE.equals(mavenResult.get("success"))) {
                    // Keep the aggregate response compact; failures keep their output tail.
                    mavenResult.remove("outputTail");
                    mavenResult.remove("outputTruncated");
                }
                results.add(mavenResult);
            } catch (Exception e) {
                Map<String, Object> error = new HashMap<>();
                error.put("projectName", name);
                error.put("status", "error");
                error.put("success", false);
                error.put("error", e.getMessage());
                results.add(error);
            }
        }
        return results;
    }

    /**
     * Refreshes the target project(s) from disk after the Maven clean removed their
     * {@code target} directories. Deliberately not run inside {@code syncCall}: the
     * resources API is thread-safe and a deep refresh can take seconds.
     *
     * @param projectName optional project scope
     * @throws CoreException if a refresh fails
     */
    private void refreshTargets(String projectName) throws CoreException {
        if (projectName != null && !projectName.isBlank()) {
            IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
            if (project.exists() && project.isOpen()) {
                project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
            }
            return;
        }
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (project.isOpen()) {
                project.refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());
            }
        }
    }

    private Map<String, Object> cleanProject(String projectName) throws Exception {
        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!project.exists() || !project.isOpen()) {
            throw new IllegalArgumentException("Project not found or not open: " + projectName);
        }

        project.build(IncrementalProjectBuilder.CLEAN_BUILD, new NullProgressMonitor());

        Map<String, Object> result = new HashMap<>();
        result.put("status", "cleaned");
        result.put("projectCount", 1);
        result.put("projects", List.of(projectName));
        return result;
    }

    private Map<String, Object> cleanAllProjects() throws Exception {
        var workspace = ResourcesPlugin.getWorkspace();
        IProject[] projects = workspace.getRoot().getProjects();

        List<String> cleanedProjects = new ArrayList<>();
        for (IProject project : projects) {
            if (project.isOpen()) {
                cleanedProjects.add(project.getName());
            }
        }

        workspace.build(IncrementalProjectBuilder.CLEAN_BUILD, new NullProgressMonitor());

        Map<String, Object> result = new HashMap<>();
        result.put("status", "cleaned");
        result.put("projectCount", cleanedProjects.size());
        result.put("projects", cleanedProjects);
        return result;
    }
}
