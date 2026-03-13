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
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.formatter.CodeFormatter;
import org.eclipse.text.edits.TextEdit;
import org.eclipse.ui.PlatformUI;

/**
 * Formats Java source files using Eclipse's code formatter with project-specific settings.
 *
 * <p>Accepts an absolute filesystem path to a {@code .java} file or a directory.
 * When a directory is given, all {@code .java} files within it are formatted recursively.</p>
 */
public class FormatCodeTool implements Tool {

	@Override
	public Object execute(Map<String, Object> arguments) throws Exception {
		String path = (String) arguments.get("path");
		if (path == null || path.isBlank()) {
			throw new IllegalArgumentException("Required parameter 'path' is missing");
		}
		return PlatformUI.getWorkbench().getDisplay().syncCall(() -> {
			try {
				return formatPath(path);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
	}

	/**
	 * Formats all Java files at the given path.
	 */
	private Map<String, Object> formatPath(String path) throws Exception {
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

		List<ICompilationUnit> units = collectCompilationUnits(resource);
		List<String> formattedFiles = new ArrayList<>();
		int skipped = 0;

		for (ICompilationUnit cu : units) {
			if (formatCompilationUnit(cu)) {
				formattedFiles.add(cu.getElementName());
			} else {
				skipped++;
			}
		}

		Map<String, Object> result = new HashMap<>();
		result.put("status", "formatted");
		result.put("fileCount", formattedFiles.size());
		result.put("files", formattedFiles);
		result.put("skipped", skipped);
		return result;
	}

	/**
	 * Formats a single compilation unit using project-specific formatter settings.
	 *
	 * @return {@code true} if the file was modified, {@code false} if formatting produced no changes
	 */
	private boolean formatCompilationUnit(ICompilationUnit cu) throws Exception {
		Map<String, String> options = cu.getJavaProject().getOptions(true);
		CodeFormatter formatter = ToolFactory.createCodeFormatter(options);
		String source = cu.getBuffer().getContents();
		TextEdit edit = formatter.format(CodeFormatter.K_COMPILATION_UNIT, source, 0, source.length(), 0, "\n");

		if (edit == null || !edit.hasChildren() && edit.getLength() == 0) {
			return false;
		}

		cu.applyTextEdit(edit, new NullProgressMonitor());
		cu.save(new NullProgressMonitor(), true);
		return true;
	}

	/**
	 * Collects all {@link ICompilationUnit}s from the given resource.
	 */
	private List<ICompilationUnit> collectCompilationUnits(IResource resource) throws Exception {
		List<ICompilationUnit> units = new ArrayList<>();

		if (resource instanceof IFile file && file.getName().endsWith(".java")) {
			ICompilationUnit cu = JavaCore.createCompilationUnitFrom(file);
			if (cu != null) {
				units.add(cu);
			}
		} else if (resource instanceof IContainer container) {
			collectFromContainer(container, units);
		}

		return units;
	}

	/**
	 * Recursively collects compilation units from a container (project or folder).
	 */
	private void collectFromContainer(IContainer container, List<ICompilationUnit> units) throws Exception {
		for (IResource member : container.members()) {
			if (member instanceof IFile file && file.getName().endsWith(".java")) {
				ICompilationUnit cu = JavaCore.createCompilationUnitFrom(file);
				if (cu != null) {
					units.add(cu);
				}
			} else if (member instanceof IContainer subContainer) {
				collectFromContainer(subContainer, units);
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
