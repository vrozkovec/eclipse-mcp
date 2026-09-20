package com.eclipse.mcp.server.tools;

import java.util.HashMap;
import java.util.Map;

import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.refactoring.IJavaRefactorings;
import org.eclipse.jdt.core.refactoring.descriptors.RenameJavaElementDescriptor;
import org.eclipse.ltk.core.refactoring.Refactoring;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ui.PlatformUI;

/**
 * Renames a Java type, method or field using Eclipse's LTK refactoring framework.
 *
 * <p>This is the same refactoring Eclipse performs for Alt+Shift+R: references are updated
 * across the whole workspace, and renaming a top-level type renames its compilation unit too.</p>
 *
 * <p>The element is addressed with a selector string — see {@link JavaElementSelector}. Use
 * {@code rename_package} for packages.</p>
 */
public class RenameElementTool implements Tool {

	@Override
	public Object execute(Map<String, Object> arguments) throws Exception {
		String element = (String) arguments.get("element");
		if (element == null || element.isBlank()) {
			throw new IllegalArgumentException("Required parameter 'element' is missing");
		}

		String newName = (String) arguments.get("newName");
		if (newName == null || newName.isBlank()) {
			throw new IllegalArgumentException("Required parameter 'newName' is missing");
		}

		String projectName = (String) arguments.get("projectName");
		Boolean updateReferencesArg = (Boolean) arguments.get("updateReferences");
		boolean updateReferences = updateReferencesArg == null || updateReferencesArg;
		Boolean textualArg = (Boolean) arguments.get("updateTextualOccurrences");
		boolean updateTextualOccurrences = textualArg != null && textualArg;

		return PlatformUI.getWorkbench().getDisplay().syncCall(() -> {
			try {
				return rename(element.trim(), newName.trim(), projectName, updateReferences,
						updateTextualOccurrences);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
	}

	/**
	 * Resolves the selector and runs the matching rename refactoring.
	 *
	 * @param element                  selector for the type, method or field to rename
	 * @param newName                  the new simple name
	 * @param projectName              optional project to limit the search to
	 * @param updateReferences         whether to update references across the workspace
	 * @param updateTextualOccurrences whether to also update matches in comments and strings
	 * @return result map with status and details
	 */
	private Map<String, Object> rename(String element, String newName, String projectName,
			boolean updateReferences, boolean updateTextualOccurrences) throws Exception {

		IJavaElement target = JavaElementSelector.resolve(element, projectName);
		String oldName = target.getElementName();

		RenameJavaElementDescriptor descriptor = new RenameJavaElementDescriptor(refactoringIdFor(target));
		descriptor.setProject(target.getJavaProject().getElementName());
		descriptor.setJavaElement(target);
		descriptor.setNewName(newName);
		descriptor.setUpdateReferences(updateReferences);
		if (updateTextualOccurrences) {
			descriptor.setUpdateTextualOccurrences(true);
		}

		RefactoringStatus creationStatus = new RefactoringStatus();
		Refactoring refactoring = descriptor.createRefactoring(creationStatus);
		RefactoringRunner.Outcome outcome = RefactoringRunner.run(refactoring, creationStatus);

		Map<String, Object> result = new HashMap<>();
		result.put("element", element);
		result.put("elementKind", kindOf(target));
		result.put("oldName", oldName);
		result.put("newName", newName);
		result.put("projectName", target.getJavaProject().getElementName());

		if (!outcome.performed()) {
			result.put("status", "failed");
			result.put("reason", outcome.failureReason());
			result.put("problems", outcome.problems());
			return result;
		}

		result.put("status", "renamed");
		result.put("updateReferences", updateReferences);
		result.put("changedFiles", outcome.changedFiles());
		result.put("changedFileCount", outcome.changedCount());
		if (!outcome.problems().isEmpty()) {
			result.put("warnings", outcome.problems());
		}
		return result;
	}

	/** Maps the resolved element to the refactoring contribution that renames it. */
	private String refactoringIdFor(IJavaElement element) throws Exception {
		return switch (element.getElementType()) {
			case IJavaElement.TYPE -> IJavaRefactorings.RENAME_TYPE;
			case IJavaElement.METHOD -> IJavaRefactorings.RENAME_METHOD;
			case IJavaElement.FIELD -> ((IField) element).isEnumConstant()
					? IJavaRefactorings.RENAME_ENUM_CONSTANT
					: IJavaRefactorings.RENAME_FIELD;
			default -> throw new IllegalArgumentException(
					"Cannot rename " + kindOf(element) + " '" + element.getElementName()
							+ "'. Supported: type, method, field. Use rename_package for packages.");
		};
	}

	/** Human-readable element kind, used in results and error messages. */
	private String kindOf(IJavaElement element) {
		return switch (element.getElementType()) {
			case IJavaElement.TYPE -> "type";
			case IJavaElement.METHOD -> "method";
			case IJavaElement.FIELD -> "field";
			case IJavaElement.PACKAGE_FRAGMENT -> "package";
			case IJavaElement.COMPILATION_UNIT -> "compilation unit";
			default -> "element";
		};
	}
}
