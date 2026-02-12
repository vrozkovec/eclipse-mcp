package com.eclipse.mcp.server.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.IJavaSearchScope;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.core.resources.IProject;
import org.eclipse.ui.PlatformUI;

/**
 * MCP tool that finds all references to a Java element (type, method, or field)
 * across the workspace. Equivalent to Eclipse's Ctrl+Shift+G (Find References).
 */
public class FindReferencesTool implements Tool {

	@Override
	public Object execute(Map<String, Object> arguments) throws Exception {
		String elementName = (String) arguments.get("elementName");
		String elementType = (String) arguments.getOrDefault("elementType", "type");
		String projectScope = (String) arguments.get("projectScope");
		Boolean caseSensitive = (Boolean) arguments.getOrDefault("caseSensitive", true);

		if (elementName == null || elementName.trim().isEmpty()) {
			throw new IllegalArgumentException("elementName is required");
		}

		int searchFor = mapElementType(elementType);

		return PlatformUI.getWorkbench().getDisplay().syncCall(() -> {
			try {
				return findReferences(elementName, searchFor, projectScope, caseSensitive);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
	}

	private int mapElementType(String elementType) {
		return switch (elementType) {
			case "method" -> IJavaSearchConstants.METHOD;
			case "field" -> IJavaSearchConstants.FIELD;
			case "type" -> IJavaSearchConstants.TYPE;
			default -> throw new IllegalArgumentException(
					"Invalid elementType: '" + elementType + "'. Must be 'type', 'method', or 'field'");
		};
	}

	private List<Map<String, Object>> findReferences(String elementName, int searchFor,
			String projectScope, boolean caseSensitive) throws CoreException {

		List<Map<String, Object>> results = new ArrayList<>();

		int matchRule = SearchPattern.R_PATTERN_MATCH;
		if (caseSensitive) {
			matchRule |= SearchPattern.R_CASE_SENSITIVE;
		}

		SearchPattern pattern = SearchPattern.createPattern(
				elementName,
				searchFor,
				IJavaSearchConstants.REFERENCES,
				matchRule);

		if (pattern == null) {
			throw new IllegalArgumentException("Invalid search pattern: " + elementName);
		}

		IJavaSearchScope scope = createSearchScope(projectScope);

		SearchRequestor requestor = new SearchRequestor() {
			@Override
			public void acceptSearchMatch(SearchMatch match) throws CoreException {
				Map<String, Object> result = new HashMap<>();

				if (match.getResource() != null) {
					result.put("filePath", match.getResource().getFullPath().toString());
				}

				result.put("offset", match.getOffset());
				result.put("length", match.getLength());
				result.put("accurate", match.getAccuracy() == SearchMatch.A_ACCURATE);

				if (match.getElement() instanceof IJavaElement element) {
					result.put("enclosingElement", element.getElementName());
					result.put("enclosingElementType", getElementTypeString(element.getElementType()));

					IJavaProject jp = element.getJavaProject();
					if (jp != null) {
						result.put("projectName", jp.getElementName());
					}

					if (element instanceof IMember member) {
						ICompilationUnit cu = member.getCompilationUnit();
						if (cu != null) {
							String source = cu.getSource();
							if (source != null) {
								addLineInfo(result, source, match.getOffset());
							}
						}
					}
				}

				results.add(result);
			}
		};

		new SearchEngine().search(
				pattern,
				new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() },
				scope,
				requestor,
				null);

		return results;
	}

	private IJavaSearchScope createSearchScope(String projectScope) throws CoreException {
		if (projectScope != null && !projectScope.trim().isEmpty()) {
			IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectScope);
			if (!project.exists() || !project.isOpen()) {
				throw new IllegalArgumentException("Project not found or not open: " + projectScope);
			}
			IJavaProject javaProject = JavaCore.create(project);
			return SearchEngine.createJavaSearchScope(new IJavaElement[] { javaProject });
		}

		IJavaProject[] projects = JavaCore.create(
				ResourcesPlugin.getWorkspace().getRoot()).getJavaProjects();
		return SearchEngine.createJavaSearchScope(projects);
	}

	/**
	 * Computes line number, column, and extracts the matching source line from the given offset.
	 */
	private void addLineInfo(Map<String, Object> result, String source, int offset) {
		if (offset < 0 || offset >= source.length()) {
			return;
		}

		int lineNumber = 1;
		int lastNewline = -1;
		for (int i = 0; i < offset; i++) {
			if (source.charAt(i) == '\n') {
				lineNumber++;
				lastNewline = i;
			}
		}
		result.put("lineNumber", lineNumber);
		result.put("column", offset - lastNewline);

		int lineStart = lastNewline + 1;
		int lineEnd = source.indexOf('\n', offset);
		if (lineEnd == -1) {
			lineEnd = source.length();
		}
		result.put("matchText", source.substring(lineStart, lineEnd).trim());
	}

	private String getElementTypeString(int elementType) {
		return switch (elementType) {
			case IJavaElement.TYPE -> "type";
			case IJavaElement.METHOD -> "method";
			case IJavaElement.FIELD -> "field";
			case IJavaElement.PACKAGE_FRAGMENT -> "package";
			case IJavaElement.COMPILATION_UNIT -> "compilationUnit";
			default -> "unknown";
		};
	}
}
