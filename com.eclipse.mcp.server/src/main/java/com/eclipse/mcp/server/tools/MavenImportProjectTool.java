package com.eclipse.mcp.server.tools;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.maven.model.Model;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.m2e.core.MavenPlugin;
import org.eclipse.m2e.core.project.IMavenProjectImportResult;
import org.eclipse.m2e.core.project.LocalProjectScanner;
import org.eclipse.m2e.core.project.MavenProjectInfo;
import org.eclipse.m2e.core.project.ProjectImportConfiguration;

/**
 * Imports an existing Maven project (single or multi-module) from a filesystem path into
 * the workspace — the headless equivalent of File &gt; Import &gt; Existing Maven Projects.
 *
 * <p>The given directory is scanned recursively with m2e's {@link LocalProjectScanner}
 * (descending into {@code <modules>} of aggregator poms), the resulting tree is flattened,
 * and every project not already present in the workspace is imported and configured via
 * {@code IProjectConfigurationManager.importProjects()}. Projects whose basedir matches an
 * existing workspace project location are reported as {@code skipped_existing}.</p>
 *
 * <p>Threading: the scan and the import run on the MCP request thread, deliberately NOT
 * inside {@code syncCall} — a first-time import resolves dependencies and can take
 * minutes, which would freeze the Eclipse UI. m2e's own ImportMavenProjectsJob runs in
 * the background the same way; like its AbstractCreateMavenProjectsOperation, the import
 * phase holds the workspace-root scheduling rule so it is serialized against builds and
 * other workspace jobs.</p>
 *
 * <p>There is deliberately no timeout parameter: cancelling an import midway would leave
 * a half-imported workspace, so the tool blocks until m2e finishes — the same trade-off
 * the m2e import wizard makes.</p>
 */
public class MavenImportProjectTool implements Tool {

    @Override
    public Object execute(Map<String, Object> arguments) throws Exception {
        String path = (String) arguments.get("path");
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Required parameter 'path' is missing");
        }
        File scanRoot = resolveScanRoot(path);
        long start = System.currentTimeMillis();

        // Phase 1: scan. Pure filesystem I/O — needs neither the UI thread nor a rule.
        LocalProjectScanner scanner = new LocalProjectScanner(
                List.of(scanRoot.getAbsolutePath()), false, MavenPlugin.getMavenModelManager());
        scanner.run(new NullProgressMonitor());
        List<String> scanErrors = scanner.getErrors().stream()
                .map(error -> error.getMessage() != null ? error.getMessage() : error.toString())
                .toList();
        Set<MavenProjectInfo> found = MavenPlugin.getProjectConfigurationManager()
                .collectProjects(scanner.getProjects());

        if (found.isEmpty()) {
            return buildResult("no_projects_found", scanRoot, List.of(), scanErrors, start);
        }

        // Phase 2: skip projects already in the workspace. Reading project locations uses
        // the thread-safe resources API, so this also stays off the UI thread.
        Map<String, String> existingByLocation = snapshotWorkspaceLocations();
        List<Map<String, Object>> entries = new ArrayList<>();
        List<MavenProjectInfo> toImport = new ArrayList<>();
        for (MavenProjectInfo info : found) {
            String basedir = info.getPomFile().getParentFile().getCanonicalPath();
            String existingName = existingByLocation.get(basedir);
            if (existingName != null) {
                entries.add(describe(info, "skipped_existing", existingName));
            } else {
                toImport.add(info);
            }
        }

        if (toImport.isEmpty()) {
            return buildResult("all_already_imported", scanRoot, entries, scanErrors, start);
        }

        // Phase 3: import under the workspace-root scheduling rule.
        List<IMavenProjectImportResult> importResults = importInfos(toImport);
        for (IMavenProjectImportResult importResult : importResults) {
            IProject project = importResult.getProject();
            if (project != null) {
                entries.add(describe(importResult.getMavenProjectInfo(), "imported", project.getName()));
            } else {
                // m2e's create() logs and returns null instead of throwing, e.g. when a
                // project with the same name exists at a different location.
                entries.add(describe(importResult.getMavenProjectInfo(), "not_imported", null));
            }
        }

        boolean hasWarnings = count(entries, "not_imported") > 0 || !scanErrors.isEmpty();
        return buildResult(hasWarnings ? "imported_with_warnings" : "imported",
                scanRoot, entries, scanErrors, start);
    }

    /**
     * Resolves and validates the input path: a directory is used as-is, a pom.xml file
     * resolves to its parent directory; anything else is rejected.
     *
     * @param path user-supplied filesystem path
     * @return the canonical directory to scan
     * @throws IOException if the path cannot be canonicalized
     */
    private File resolveScanRoot(String path) throws IOException {
        File file = new File(path).getCanonicalFile();
        if (!file.exists()) {
            throw new IllegalArgumentException("Path does not exist: " + path);
        }
        if (file.isFile()) {
            if (!"pom.xml".equals(file.getName())) {
                throw new IllegalArgumentException("Path must be a directory or a pom.xml file: " + path);
            }
            return file.getParentFile();
        }
        return file;
    }

    /**
     * Snapshots {@code canonical project location -> project name} for every workspace
     * project (open or closed) so scanned projects that are already imported can be
     * skipped instead of failing inside m2e.
     *
     * @return map of canonical filesystem locations to workspace project names
     * @throws IOException if a project location cannot be canonicalized
     */
    private Map<String, String> snapshotWorkspaceLocations() throws IOException {
        Map<String, String> locations = new LinkedHashMap<>();
        for (IProject project : ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            if (project.getLocation() != null) {
                locations.put(project.getLocation().toFile().getCanonicalPath(), project.getName());
            }
        }
        return locations;
    }

    /**
     * Imports the given projects while holding the workspace-root scheduling rule, exactly
     * as m2e's AbstractCreateMavenProjectsOperation does, so the import is serialized
     * against builds and other workspace jobs without blocking the UI thread.
     *
     * @param toImport flattened project infos not yet present in the workspace
     * @return one import result per info; {@code getProject()} is null when not imported
     * @throws Exception if the import fails as a whole
     */
    private List<IMavenProjectImportResult> importInfos(List<MavenProjectInfo> toImport) throws Exception {
        NullProgressMonitor monitor = new NullProgressMonitor();
        ISchedulingRule rule = ResourcesPlugin.getWorkspace().getRoot();
        Job.getJobManager().beginRule(rule, monitor);
        try {
            return MavenPlugin.getProjectConfigurationManager()
                    .importProjects(toImport, new ProjectImportConfiguration(), monitor);
        } finally {
            Job.getJobManager().endRule(rule);
        }
    }

    /**
     * Builds one per-project result entry. The Maven coordinates fall back to the parent
     * declaration when groupId/version are inherited; all accessors are null-safe.
     *
     * @param info        the scanned project info
     * @param status      {@code imported}, {@code skipped_existing} or {@code not_imported}
     * @param projectName workspace project name, or null when not imported
     * @return result entry for the {@code projects} list
     */
    private Map<String, Object> describe(MavenProjectInfo info, String status, String projectName) {
        Map<String, Object> entry = new HashMap<>();
        entry.put("projectName", projectName);
        entry.put("status", status);
        File pomFile = info.getPomFile();
        if (pomFile != null) {
            entry.put("pomFile", pomFile.getAbsolutePath());
            entry.put("location", pomFile.getParentFile().getAbsolutePath());
        }
        Model model = info.getModel();
        if (model != null) {
            entry.put("artifactId", model.getArtifactId());
            entry.put("groupId", model.getGroupId() != null ? model.getGroupId()
                    : model.getParent() != null ? model.getParent().getGroupId() : null);
            entry.put("version", model.getVersion() != null ? model.getVersion()
                    : model.getParent() != null ? model.getParent().getVersion() : null);
        }
        return entry;
    }

    /**
     * Assembles the top-level result map with per-status counts and elapsed time.
     *
     * @param status     overall tool status
     * @param scanRoot   the scanned directory
     * @param entries    per-project result entries
     * @param scanErrors non-fatal errors reported by the scanner
     * @param start      start timestamp in epoch milliseconds
     * @return the result map returned to the MCP client
     */
    private Map<String, Object> buildResult(String status, File scanRoot,
            List<Map<String, Object>> entries, List<String> scanErrors, long start) {
        Map<String, Object> result = new HashMap<>();
        result.put("status", status);
        result.put("path", scanRoot.getAbsolutePath());
        result.put("totalFound", entries.size());
        result.put("importedCount", count(entries, "imported"));
        result.put("skippedExistingCount", count(entries, "skipped_existing"));
        result.put("notImportedCount", count(entries, "not_imported"));
        result.put("projects", entries);
        result.put("scanErrors", scanErrors);
        result.put("elapsedMillis", System.currentTimeMillis() - start);
        return result;
    }

    /**
     * Counts entries with the given per-project status.
     *
     * @param entries per-project result entries
     * @param status  status value to count
     * @return number of matching entries
     */
    private long count(List<Map<String, Object>> entries, String status) {
        return entries.stream().filter(entry -> status.equals(entry.get("status"))).count();
    }
}
