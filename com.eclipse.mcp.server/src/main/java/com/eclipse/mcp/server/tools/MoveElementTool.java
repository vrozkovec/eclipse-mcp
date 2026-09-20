package com.eclipse.mcp.server.tools;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IFolder;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.refactoring.descriptors.MoveDescriptor;
import org.eclipse.ltk.core.refactoring.Refactoring;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ui.PlatformUI;

/**
 * Moves a Java class to another package using Eclipse's LTK refactoring framework.
 *
 * <p>This is the same refactoring Eclipse performs for Alt+Shift+V on a compilation unit: the
 * {@code .java} file is moved into the new package directory, its package declaration is
 * rewritten, and imports and references are updated across the workspace.</p>
 *
 * <p>The destination package is resolved within the <em>same source folder</em> as the class
 * being moved, so a class under {@code src/main/java} cannot accidentally land under
 * {@code src/test/java}. Moving between projects is not supported.</p>
 */
public class MoveElementTool implements Tool {

	@Override
	public Object execute(Map<String, Object> arguments) throws Exception {
		String element = (String) arguments.get("element");
		if (element == null || element.isBlank()) {
			throw new IllegalArgumentException("Required parameter 'element' is missing");
		}

		String destination = (String) arguments.get("destination");
		if (destination == null || destination.isBlank()) {
			throw new IllegalArgumentException("Required parameter 'destination' is missing");
		}

		String projectName = (String) arguments.get("projectName");
		Boolean updateReferencesArg = (Boolean) arguments.get("updateReferences");
		boolean updateReferences = updateReferencesArg == null || updateReferencesArg;
		Boolean createDestinationArg = (Boolean) arguments.get("createDestination");
		boolean createDestination = createDestinationArg != null && createDestinationArg;

		return PlatformUI.getWorkbench().getDisplay().syncCall(() -> {
			try {
				return move(element.trim(), destination.trim(), projectName, updateReferences,
						createDestination);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
	}

	/**
	 * Resolves the class and destination package, then runs the move refactoring.
	 *
	 * @param element           fully qualified name of the class to move
	 * @param destination       fully qualified name of the target package
	 * @param projectName       optional project to limit the search to
	 * @param updateReferences  whether to update references across the workspace
	 * @param createDestination whether to create the destination package if it does not exist
	 * @return result map with status and details
	 */
	private Map<String, Object> move(String element, String destination, String projectName,
			boolean updateReferences, boolean createDestination) throws Exception {

		IType type = JavaElementSelector.findType(element, projectName);
		if (type.getDeclaringType() != null) {
			throw new IllegalArgumentException("'" + element
					+ "' is a nested type. Move its top-level class instead, or extract it first.");
		}

		ICompilationUnit unit = type.getCompilationUnit();
		if (unit == null) {
			throw new IllegalArgumentException("'" + element + "' has no compilation unit and cannot be moved");
		}

		IPackageFragment source = (IPackageFragment) unit.getAncestor(IJavaElement.PACKAGE_FRAGMENT);
		if (source != null && source.getElementName().equals(destination)) {
			Map<String, Object> unchanged = new HashMap<>();
			unchanged.put("status", "unchanged");
			unchanged.put("element", element);
			unchanged.put("destination", destination);
			unchanged.put("reason", "Class is already in package '" + destination + "'");
			return unchanged;
		}

		IPackageFragment target = resolveDestination(unit, destination, createDestination);

		MoveDescriptor descriptor = new MoveDescriptor();
		descriptor.setProject(unit.getJavaProject().getElementName());
		descriptor.setMoveResources(new IFile[0], new IFolder[0], new ICompilationUnit[] { unit });
		descriptor.setDestination(target);
		descriptor.setUpdateReferences(updateReferences);

		RefactoringStatus creationStatus = new RefactoringStatus();
		Refactoring refactoring = descriptor.createRefactoring(creationStatus);
		RefactoringRunner.Outcome outcome = RefactoringRunner.run(refactoring, creationStatus);

		Map<String, Object> result = new HashMap<>();
		result.put("element", element);
		result.put("sourcePackage", source == null ? "" : source.getElementName());
		result.put("destination", destination);
		result.put("projectName", unit.getJavaProject().getElementName());

		if (!outcome.performed()) {
			result.put("status", "failed");
			result.put("reason", outcome.failureReason());
			result.put("problems", outcome.problems());
			return result;
		}

		result.put("status", "moved");
		result.put("updateReferences", updateReferences);
		result.put("changedFiles", outcome.changedFiles());
		result.put("changedFileCount", outcome.changedCount());
		if (!outcome.problems().isEmpty()) {
			result.put("warnings", outcome.problems());
		}
		return result;
	}

	/**
	 * Resolves the destination package within the moved class's own source folder, optionally
	 * creating it.
	 *
	 * <p>Defaulting {@code createDestination} to {@code false} means a typo in the package name
	 * is reported rather than silently producing a new package.</p>
	 */
	private IPackageFragment resolveDestination(ICompilationUnit unit, String destination,
			boolean createDestination) throws Exception {

		IPackageFragmentRoot root = (IPackageFragmentRoot) unit.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT);
		if (root == null) {
			throw new IllegalArgumentException("Could not determine the source folder of " + unit.getElementName());
		}

		IPackageFragment target = root.getPackageFragment(destination);
		if (target.exists()) {
			return target;
		}

		if (!createDestination) {
			throw new IllegalArgumentException("Destination package '" + destination
					+ "' does not exist in source folder '" + root.getPath()
					+ "'. Set createDestination=true to create it, or move to an existing package.");
		}
		return root.createPackageFragment(destination, false, new NullProgressMonitor());
	}
}
