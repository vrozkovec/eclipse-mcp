package com.eclipse.mcp.server.tools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.internal.corext.fix.CleanUpRefactoring;
import org.eclipse.jdt.internal.corext.fix.CleanUpRegistry;
import org.eclipse.jdt.internal.ui.JavaPlugin;
import org.eclipse.jdt.ui.cleanup.ICleanUp;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ui.PlatformUI;

/**
 * Runs Eclipse's "Source > Clean Up" on Java source files using project-specific cleanup profile settings.
 *
 * <p>Applies code modernization rules such as adding {@code @Override}, converting to enhanced
 * for-loops, using lambda expressions, pattern matching instanceof, removing unnecessary casts,
 * and more — depending on the configured cleanup profile.</p>
 *
 * <p>Accepts an absolute filesystem path to a {@code .java} file or a directory.
 * When a directory is given, all {@code .java} files within it are cleaned up
 * (optionally recursively).</p>
 */
@SuppressWarnings("restriction")
public class CleanupCodeTool implements Tool {

	@Override
	public Object execute(Map<String, Object> arguments) throws Exception {
		String path = (String) arguments.get("path");
		if (path == null || path.isBlank()) {
			throw new IllegalArgumentException("Required parameter 'path' is missing");
		}
		Boolean recursive = (Boolean) arguments.getOrDefault("recursive", Boolean.TRUE);

		return PlatformUI.getWorkbench().getDisplay().syncCall(() -> {
			try {
				return cleanupPath(path, recursive);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
	}

	/**
	 * Runs cleanup on all Java files at the given path.
	 */
	private Map<String, Object> cleanupPath(String path, boolean recursive) throws Exception {
		String normalizedPath = Path.of(path).normalize().toString();
		IProject project = resolveProject(normalizedPath);
		if (!project.isOpen()) {
			throw new IllegalStateException("Project '" + project.getName() + "' is not open");
		}

		String projectPath = project.getLocation().toOSString();
		String relativePath = normalizedPath.substring(projectPath.length());
		IResource resource = relativePath.isEmpty()
				? project
				: project.findMember(relativePath);

		if (resource == null) {
			throw new IllegalArgumentException("Resource not found in project: " + path);
		}

		List<ICompilationUnit> units = collectCompilationUnits(resource, recursive);
		List<String> cleanedFiles = new ArrayList<>();
		int skipped = 0;

		for (ICompilationUnit cu : units) {
			if (cleanupCompilationUnit(cu)) {
				cleanedFiles.add(cu.getElementName());
			} else {
				skipped++;
			}
		}

		Map<String, Object> result = new HashMap<>();
		result.put("status", "cleaned");
		result.put("fileCount", cleanedFiles.size());
		result.put("files", cleanedFiles);
		result.put("skipped", skipped);
		return result;
	}

	/**
	 * Runs Eclipse cleanup on a single compilation unit using project/workspace cleanup profile.
	 *
	 * @return {@code true} if the file was modified, {@code false} if no changes were needed
	 */
	private boolean cleanupCompilationUnit(ICompilationUnit cu) throws Exception {
		CleanUpRefactoring refactoring = new CleanUpRefactoring();
		refactoring.addCompilationUnit(cu);
		refactoring.setUseOptionsFromProfile(true);

		CleanUpRegistry registry = JavaPlugin.getDefault().getCleanUpRegistry();
		ICleanUp[] cleanUps = registry.createCleanUps();
		for (ICleanUp cleanUp : cleanUps) {
			refactoring.addCleanUp(cleanUp);
		}

		NullProgressMonitor monitor = new NullProgressMonitor();
		refactoring.checkInitialConditions(monitor);
		RefactoringStatus status = refactoring.checkFinalConditions(monitor);

		if (status.hasFatalError()) {
			return false;
		}

		Change change = refactoring.createChange(monitor);
		if (change == null) {
			return false;
		}

		change.perform(monitor);
		return true;
	}

	/**
	 * Collects all {@link ICompilationUnit}s from the given resource.
	 */
	private List<ICompilationUnit> collectCompilationUnits(IResource resource, boolean recursive) throws Exception {
		List<ICompilationUnit> units = new ArrayList<>();

		if (resource instanceof IFile file && file.getName().endsWith(".java")) {
			ICompilationUnit cu = JavaCore.createCompilationUnitFrom(file);
			if (cu != null) {
				units.add(cu);
			}
		} else if (resource instanceof IContainer container) {
			collectFromContainer(container, recursive, units);
		}

		return units;
	}

	/**
	 * Recursively collects compilation units from a container (project or folder).
	 */
	private void collectFromContainer(IContainer container, boolean recursive, List<ICompilationUnit> units)
			throws Exception {
		for (IResource member : container.members()) {
			if (member instanceof IFile file && file.getName().endsWith(".java")) {
				ICompilationUnit cu = JavaCore.createCompilationUnitFrom(file);
				if (cu != null) {
					units.add(cu);
				}
			} else if (recursive && member instanceof IContainer subContainer) {
				collectFromContainer(subContainer, true, units);
			}
		}
	}

	/**
	 * Resolves an absolute filesystem path to the deepest enclosing Eclipse project.
	 */
	private IProject resolveProject(String normalizedPath) {
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
			throw new IllegalArgumentException("No Eclipse project found for path: " + normalizedPath);
		}
		return bestMatch;
	}
}
