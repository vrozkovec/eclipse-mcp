package com.eclipse.mcp.server.tools;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.Refactoring;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.RefactoringStatusEntry;

/**
 * Runs an LTK {@link Refactoring} to completion and reports what happened.
 *
 * <p>Mirrors the sequence Eclipse's own {@code PerformChangeOperation} uses: check initial
 * conditions, check final conditions, create the change, initialize its validation data,
 * validate, then perform. Any of the checks may veto the refactoring, in which case nothing
 * is written and the offending status entries are reported back.</p>
 */
final class RefactoringRunner {

	/** Upper bound on the number of changed file paths reported, to keep responses small. */
	private static final int MAX_REPORTED_FILES = 200;

	private RefactoringRunner() {
	}

	/**
	 * The outcome of a refactoring attempt.
	 *
	 * @param performed     whether the change was applied
	 * @param failureReason which stage vetoed it, {@code null} when performed
	 * @param problems      status entries — the blocking ones on failure, warnings on success
	 * @param changedFiles  workspace paths touched by the change
	 * @param changedCount  total number of touched files, which may exceed {@code changedFiles}
	 */
	record Outcome(boolean performed, String failureReason, List<Map<String, String>> problems,
			List<String> changedFiles, int changedCount) {
	}

	/**
	 * Executes the full refactoring sequence.
	 *
	 * @param refactoring   the refactoring to run
	 * @param creationStatus status collected while building the refactoring descriptor
	 */
	static Outcome run(Refactoring refactoring, RefactoringStatus creationStatus) throws CoreException {
		NullProgressMonitor monitor = new NullProgressMonitor();

		if (refactoring == null || creationStatus.hasFatalError()) {
			return failure("Refactoring could not be created", creationStatus);
		}

		RefactoringStatus initial = refactoring.checkInitialConditions(monitor);
		if (initial.hasFatalError()) {
			return failure("Initial precondition check failed", initial);
		}

		RefactoringStatus finalConditions = refactoring.checkFinalConditions(monitor);
		if (finalConditions.hasFatalError()) {
			return failure("Final precondition check failed", finalConditions);
		}

		Change change = refactoring.createChange(monitor);
		if (change == null) {
			return failure("No changes to apply", new RefactoringStatus());
		}

		// Collect before performing — the change tree is disposed once applied.
		Set<String> changedFiles = new LinkedHashSet<>();
		collectChangedFiles(change, changedFiles);

		change.initializeValidationData(monitor);
		RefactoringStatus validation = change.isValid(monitor);
		if (validation.hasFatalError()) {
			return failure("Change validation failed", validation);
		}

		change.perform(monitor);

		List<Map<String, String>> warnings = describe(initial);
		warnings.addAll(describe(finalConditions));

		List<String> reported = changedFiles.stream().limit(MAX_REPORTED_FILES).toList();
		return new Outcome(true, null, warnings, reported, changedFiles.size());
	}

	/** Walks the change tree collecting the workspace paths of the files it touches. */
	private static void collectChangedFiles(Change change, Set<String> collected) {
		Object modified = change.getModifiedElement();
		if (modified instanceof IFile file) {
			collected.add(file.getFullPath().toString());
		} else if (modified instanceof ICompilationUnit unit && unit.getResource() != null) {
			collected.add(unit.getResource().getFullPath().toString());
		}

		if (change instanceof CompositeChange composite) {
			for (Change child : composite.getChildren()) {
				collectChangedFiles(child, collected);
			}
		}
	}

	private static Outcome failure(String reason, RefactoringStatus status) {
		return new Outcome(false, reason, describe(status), List.of(), 0);
	}

	/** Flattens a {@link RefactoringStatus} into serializable message/severity pairs. */
	private static List<Map<String, String>> describe(RefactoringStatus status) {
		List<Map<String, String>> problems = new ArrayList<>();
		for (RefactoringStatusEntry entry : status.getEntries()) {
			problems.add(Map.of(
					"message", entry.getMessage(),
					"severity", mapSeverity(entry.getSeverity())));
		}
		return problems;
	}

	private static String mapSeverity(int severity) {
		return switch (severity) {
			case RefactoringStatus.FATAL -> "FATAL";
			case RefactoringStatus.ERROR -> "ERROR";
			case RefactoringStatus.WARNING -> "WARNING";
			case RefactoringStatus.INFO -> "INFO";
			default -> "OK";
		};
	}
}
