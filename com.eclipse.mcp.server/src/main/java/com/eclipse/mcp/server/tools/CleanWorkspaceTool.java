package com.eclipse.mcp.server.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IBuildConfiguration;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IncrementalProjectBuilder;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.jobs.IJobManager;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.ui.PlatformUI;

/**
 * Cleans projects in the workspace, equivalent to Project &gt; Clean in Eclipse.
 *
 * <p>If {@code projectName} is provided, only that project is cleaned.
 * Otherwise all open projects are cleaned.</p>
 *
 * <p>If {@code mavenClean} is {@code true}, {@code mvn clean} is run first through an
 * m2e launch (see {@link MavenLauncher}). The affected projects are then refreshed from disk
 * and cleaned twice, waiting for Eclipse's rebuild after each round — i.e. the phases are:
 * mvn clean &rarr; refresh &rarr; CLEAN_BUILD &rarr; wait &rarr; refresh &rarr; CLEAN_BUILD
 * &rarr; wait (see {@link #mavenCleanAndRebuild(String)} for why). The affected projects
 * include every open project nested inside the named one: a reactor run on an aggregator
 * deletes its modules' {@code target} directories too, and JDT does not recompile class files
 * deleted behind its back. Projects without the Maven nature are skipped in the Maven phase.</p>
 */
public class CleanWorkspaceTool implements Tool {

    /** Per-project timeout for the Maven clean phase, in seconds. */
    private static final long MAVEN_CLEAN_TIMEOUT_SECONDS = 120L;

    /** Time budget for both rebuilds that follow the Maven clean phase, in seconds. */
    private static final long BUILD_TIMEOUT_SECONDS = 300L;

    /** Poll interval while waiting for a rebuild, in milliseconds. */
    private static final long BUILD_POLL_INTERVAL_MILLIS = 250L;

    @Override
    public Object execute(Map<String, Object> arguments) throws Exception {
        String projectName = (String) arguments.get("projectName");
        boolean mavenClean = Boolean.TRUE.equals(arguments.get("mavenClean"));

        if (mavenClean) {
            return mavenCleanAndRebuild(projectName);
        }

        return PlatformUI.getWorkbench().getDisplay().syncCall(() -> {
            try {
                if (projectName != null && !projectName.isBlank()) {
                    return cleanProject(projectName);
                }
                return cleanAllProjects();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Runs {@code mvn clean}, then refreshes and cleans the affected projects twice, waiting
     * for Eclipse's rebuild after each round.
     *
     * <p>One round is not enough: in the rebuild after a clean the Java builder runs before
     * the Maven builder, and only the Maven builder regenerates sources such as the JPA
     * metamodels in {@code target/generated-sources/apt}. Eclipse never schedules another build
     * for files its own build wrote, so every class using them keeps its errors. Once the first
     * rebuild has finished the generated sources are on disk, and the second one compiles them.</p>
     *
     * <p>Everything runs on the request thread: the Maven launches manage their own UI-thread
     * hops and the resources and jobs APIs are thread-safe — keeping the slow work out of
     * syncCall leaves the Eclipse UI responsive.</p>
     *
     * @param projectName optional project scope
     * @return the clean result plus the Maven results, whether Eclipse was still
     *         {@code building} when the wait timed out, and the {@code errors} left in the
     *         cleaned projects
     * @throws Exception if the named project is invalid or cannot be launched, or if a refresh
     *                   or clean fails
     */
    private Map<String, Object> mavenCleanAndRebuild(String projectName) throws Exception {
        List<IProject> projects = targetProjects(projectName);
        List<Map<String, Object>> mavenResults = runMavenClean(projectName);
        refreshAndClean(projects);

        Map<String, Object> result = new HashMap<>();
        result.put("status", "cleaned");
        result.put("projectCount", projects.size());
        result.put("projects", projects.stream().map(IProject::getName).toList());
        result.put("mavenClean", mavenResults);

        boolean anyFailed = mavenResults.stream()
                .anyMatch(entry -> Boolean.FALSE.equals(entry.get("success")));
        if (anyFailed) {
            result.put("status", "cleaned_with_maven_failures");
            return result;
        }

        long deadline = System.currentTimeMillis() + BUILD_TIMEOUT_SECONDS * 1000L;
        boolean built = waitForBuild(deadline);
        if (built) {
            // Skipped on timeout: a clean started while the first rebuild still runs would race it.
            refreshAndClean(projects);
            built = waitForBuild(deadline);
        }
        result.put("building", !built);
        result.put("errors", countErrors(projects));
        return result;
    }

    /**
     * Returns the projects a Maven clean affects: the named project plus every open project
     * located inside it (the modules of an aggregator), or all open projects when no name is
     * given.
     *
     * @param projectName optional project scope
     * @return the open projects to refresh and clean, the named project first
     * @throws IllegalArgumentException if the named project does not exist or is not open
     */
    private List<IProject> targetProjects(String projectName) {
        IProject[] allProjects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
        if (projectName == null || projectName.isBlank()) {
            return Arrays.stream(allProjects).filter(IProject::isOpen).toList();
        }

        IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
        if (!project.exists() || !project.isOpen()) {
            throw new IllegalArgumentException("Project not found or not open: " + projectName);
        }
        List<IProject> projects = new ArrayList<>();
        projects.add(project);
        IPath root = project.getLocation();
        for (IProject other : allProjects) {
            IPath location = other.getLocation();
            if (!other.equals(project) && other.isOpen() && root != null && location != null
                    && root.isPrefixOf(location)) {
                projects.add(other);
            }
        }
        return projects;
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
     * Refreshes the projects from disk, then cleans them in one workspace build
     * (Project &gt; Clean). One clean for all of them makes Eclipse start a single full
     * rebuild afterwards, rather than one per project interleaved with the remaining cleans.
     * Deliberately not run inside {@code syncCall}: a deep refresh can take seconds and the
     * clean may have to wait for a running build.
     *
     * @param projects the open projects to refresh and clean
     * @throws CoreException if a refresh or the clean fails
     */
    private void refreshAndClean(List<IProject> projects) throws CoreException {
        var monitor = new NullProgressMonitor();
        List<IBuildConfiguration> configs = new ArrayList<>();
        for (IProject project : projects) {
            project.refreshLocal(IResource.DEPTH_INFINITE, monitor);
            configs.add(project.getActiveBuildConfig());
        }
        ResourcesPlugin.getWorkspace().build(configs.toArray(IBuildConfiguration[]::new),
                IncrementalProjectBuilder.CLEAN_BUILD, false, monitor);
    }

    /**
     * Waits until no auto or manual build job is waiting, sleeping or running (the test
     * {@code get_build_status} uses for {@code building}), or until the deadline passes.
     * Sleeping jobs count, so the rebuild a clean schedules with a short delay is included.
     *
     * @param deadlineMillis wall-clock deadline in epoch milliseconds
     * @return {@code true} if the build finished before the deadline
     * @throws InterruptedException if the polling thread is interrupted
     */
    private boolean waitForBuild(long deadlineMillis) throws InterruptedException {
        IJobManager jobManager = Job.getJobManager();
        while (jobManager.find(ResourcesPlugin.FAMILY_AUTO_BUILD).length > 0
                || jobManager.find(ResourcesPlugin.FAMILY_MANUAL_BUILD).length > 0) {
            if (System.currentTimeMillis() > deadlineMillis) {
                return false;
            }
            Thread.sleep(BUILD_POLL_INTERVAL_MILLIS);
        }
        return true;
    }

    /**
     * Counts the problem markers with error severity in the given projects.
     *
     * @param projects the cleaned projects
     * @return the number of errors
     * @throws CoreException if the markers cannot be read
     */
    private int countErrors(List<IProject> projects) throws CoreException {
        return PlatformUI.getWorkbench().getDisplay().syncCall(() -> {
            int errors = 0;
            for (IProject project : projects) {
                if (!project.isAccessible()) {
                    continue;
                }
                for (IMarker marker : project.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE)) {
                    if (marker.getAttribute(IMarker.SEVERITY, -1) == IMarker.SEVERITY_ERROR) {
                        errors++;
                    }
                }
            }
            return errors;
        });
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
