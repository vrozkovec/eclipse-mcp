package com.eclipse.mcp.server.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.internal.corext.refactoring.rename.RenamePackageProcessor;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.RefactoringStatusEntry;
import org.eclipse.ltk.core.refactoring.participants.RenameRefactoring;
import org.eclipse.ui.PlatformUI;

/**
 * Renames a Java package using Eclipse's LTK refactoring framework.
 *
 * <p>Updates the package declaration in all files, rewrites import statements across the workspace,
 * and moves files to the new directory structure. Optionally renames sub-packages.</p>
 *
 * <p>Uses {@link RenamePackageProcessor} under the hood, which triggers the full LTK participant
 * framework — the same refactoring Eclipse performs when you rename a package in the UI.</p>
 */
@SuppressWarnings("restriction")
public class RenamePackageTool implements Tool {

	@Override
	public Object execute(Map<String, Object> arguments) throws Exception {
		String packageName = (String) arguments.get("packageName");
		if (packageName == null || packageName.isBlank()) {
			throw new IllegalArgumentException("Required parameter 'packageName' is missing");
		}

		String newPackageName = (String) arguments.get("newPackageName");
		if (newPackageName == null || newPackageName.isBlank()) {
			throw new IllegalArgumentException("Required parameter 'newPackageName' is missing");
		}

		String projectName = (String) arguments.get("projectName");
		Boolean renameSubpackagesArg = (Boolean) arguments.get("renameSubpackages");
		boolean renameSubpackages = renameSubpackagesArg == null || renameSubpackagesArg;

		return PlatformUI.getWorkbench().getDisplay().syncCall(() -> {
			try {
				return renamePackage(packageName, newPackageName, projectName, renameSubpackages);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
	}

	/**
	 * Performs the package rename using Eclipse's refactoring engine.
	 *
	 * @param packageName       fully qualified current package name
	 * @param newPackageName    fully qualified new package name
	 * @param projectName       optional project name to limit search scope
	 * @param renameSubpackages whether to also rename sub-packages
	 * @return result map with status and details
	 */
	private Map<String, Object> renamePackage(String packageName, String newPackageName,
			String projectName, boolean renameSubpackages) throws Exception {

		IPackageFragment pkg = findPackageFragment(packageName, projectName);

		RenamePackageProcessor processor = new RenamePackageProcessor(pkg);
		processor.setNewElementName(newPackageName);
		processor.setRenameSubpackages(renameSubpackages);

		RenameRefactoring refactoring = new RenameRefactoring(processor);
		NullProgressMonitor monitor = new NullProgressMonitor();

		RefactoringStatus initialStatus = refactoring.checkInitialConditions(monitor);
		if (initialStatus.hasFatalError()) {
			return buildFailureResult(packageName, newPackageName,
					"Initial precondition check failed", initialStatus);
		}

		RefactoringStatus finalStatus = refactoring.checkFinalConditions(monitor);
		if (finalStatus.hasFatalError()) {
			return buildFailureResult(packageName, newPackageName,
					"Final precondition check failed", finalStatus);
		}

		Change change = refactoring.createChange(monitor);
		if (change == null) {
			Map<String, Object> result = new HashMap<>();
			result.put("status", "failed");
			result.put("oldPackageName", packageName);
			result.put("newPackageName", newPackageName);
			result.put("reason", "No changes to apply");
			return result;
		}

		change.perform(monitor);

		Map<String, Object> result = new HashMap<>();
		result.put("status", "renamed");
		result.put("oldPackageName", packageName);
		result.put("newPackageName", newPackageName);
		result.put("projectName", pkg.getJavaProject().getElementName());
		result.put("renameSubpackages", renameSubpackages);
		return result;
	}

	/**
	 * Locates the {@link IPackageFragment} for the given package name.
	 *
	 * <p>Searches source roots only (not JARs). If multiple source roots contain
	 * the package, prefers non-empty ones (with compilation units). If still
	 * ambiguous, throws with details so the caller can specify {@code projectName}.</p>
	 *
	 * @param packageName fully qualified package name
	 * @param projectName optional project name to limit search scope
	 * @return the resolved package fragment
	 */
	private IPackageFragment findPackageFragment(String packageName, String projectName) throws Exception {
		List<IPackageFragment> candidates = new ArrayList<>();
		List<String> searchedProjects = new ArrayList<>();

		IJavaProject[] projects;
		if (projectName != null && !projectName.isBlank()) {
			IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
			if (!project.exists() || !project.isOpen()) {
				throw new IllegalArgumentException("Project not found or not open: " + projectName);
			}
			projects = new IJavaProject[] { JavaCore.create(project) };
		} else {
			projects = JavaCore.create(ResourcesPlugin.getWorkspace().getRoot()).getJavaProjects();
		}

		for (IJavaProject javaProject : projects) {
			searchedProjects.add(javaProject.getElementName());
			for (IPackageFragmentRoot root : javaProject.getPackageFragmentRoots()) {
				if (root.getKind() != IPackageFragmentRoot.K_SOURCE) {
					continue;
				}
				IPackageFragment pkg = root.getPackageFragment(packageName);
				if (pkg != null && pkg.exists()) {
					candidates.add(pkg);
				}
			}
		}

		if (candidates.isEmpty()) {
			throw new IllegalArgumentException(
					"Package '" + packageName + "' not found in source roots. Searched projects: " + searchedProjects);
		}

		if (candidates.size() == 1) {
			return candidates.get(0);
		}

		// Multiple candidates — prefer non-empty packages (ones with compilation units)
		List<IPackageFragment> nonEmpty = candidates.stream()
				.filter(pkg -> {
					try {
						return pkg.getCompilationUnits().length > 0;
					} catch (Exception e) {
						return false;
					}
				})
				.toList();

		if (nonEmpty.size() == 1) {
			return nonEmpty.get(0);
		}

		if (nonEmpty.size() > 1) {
			List<String> locations = nonEmpty.stream()
					.map(pkg -> pkg.getJavaProject().getElementName() + ":"
							+ pkg.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT).getElementName())
					.toList();
			throw new IllegalArgumentException(
					"Package '" + packageName + "' found in multiple source roots: " + locations
							+ ". Specify 'projectName' to disambiguate.");
		}

		// All are empty — pick the first
		return candidates.get(0);
	}

	/**
	 * Builds a structured failure result from a {@link RefactoringStatus}.
	 */
	private Map<String, Object> buildFailureResult(String packageName, String newPackageName,
			String reason, RefactoringStatus status) {
		Map<String, Object> result = new HashMap<>();
		result.put("status", "failed");
		result.put("oldPackageName", packageName);
		result.put("newPackageName", newPackageName);
		result.put("reason", reason);

		List<Map<String, String>> problems = new ArrayList<>();
		for (RefactoringStatusEntry entry : status.getEntries()) {
			Map<String, String> problem = new HashMap<>();
			problem.put("message", entry.getMessage());
			problem.put("severity", mapSeverity(entry.getSeverity()));
			problems.add(problem);
		}
		result.put("problems", problems);
		return result;
	}

	/**
	 * Maps a {@link RefactoringStatus} severity constant to a human-readable label.
	 */
	private String mapSeverity(int severity) {
		return switch (severity) {
			case RefactoringStatus.FATAL -> "FATAL";
			case RefactoringStatus.ERROR -> "ERROR";
			case RefactoringStatus.WARNING -> "WARNING";
			case RefactoringStatus.INFO -> "INFO";
			default -> "OK";
		};
	}
}
