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
import org.eclipse.jdt.core.manipulation.OrganizeImportsOperation;
import org.eclipse.ui.PlatformUI;

/**
 * Organizes imports in Java source files using Eclipse's import organizer with project-specific settings.
 *
 * <p>Accepts an absolute filesystem path to a {@code .java} file or a directory.
 * When a directory is given, imports are organized in all {@code .java} files
 * (optionally recursively). Ambiguous imports are auto-resolved by picking the first match.</p>
 */
public class OrganizeImportsTool implements Tool {

	@Override
	public Object execute(Map<String, Object> arguments) throws Exception {
		String path = (String) arguments.get("path");
		if (path == null || path.isBlank()) {
			throw new IllegalArgumentException("Required parameter 'path' is missing");
		}
		Boolean recursive = (Boolean) arguments.getOrDefault("recursive", Boolean.TRUE);

		return PlatformUI.getWorkbench().getDisplay().syncCall(() -> {
			try {
				return organizePath(path, recursive);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
	}

	/**
	 * Organizes imports for all Java files at the given path.
	 */
	private Map<String, Object> organizePath(String path, boolean recursive) throws Exception {
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
		List<String> organizedFiles = new ArrayList<>();
		int skipped = 0;

		for (ICompilationUnit cu : units) {
			if (organizeCompilationUnit(cu)) {
				organizedFiles.add(cu.getElementName());
			} else {
				skipped++;
			}
		}

		Map<String, Object> result = new HashMap<>();
		result.put("status", "organized");
		result.put("fileCount", organizedFiles.size());
		result.put("files", organizedFiles);
		result.put("skipped", skipped);
		return result;
	}

	/**
	 * Organizes imports in a single compilation unit.
	 *
	 * @return {@code true} if the operation ran successfully, {@code false} if skipped
	 */
	private boolean organizeCompilationUnit(ICompilationUnit cu) throws Exception {
		OrganizeImportsOperation op = new OrganizeImportsOperation(
				cu,
				null,  // no pre-parsed AST — let it parse fresh
				true,  // ignoreLowerCaseNames
				true,  // save after organizing
				true,  // allowSyntaxErrors
				null   // chooseImportQuery — null means auto-resolve (pick first match)
		);
		op.run(new NullProgressMonitor());
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
