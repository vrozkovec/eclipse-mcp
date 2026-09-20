package com.eclipse.mcp.server.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;

/**
 * Resolves the selector strings used by {@link RenameElementTool} and {@link MoveElementTool}
 * into JDT elements.
 *
 * <p>Supported forms:</p>
 * <pre>
 * com.example.Bar                 top-level type
 * com.example.Bar.Inner           nested type
 * com.example.Bar#doWork          method or field (must be unambiguous)
 * com.example.Bar#doWork(String)  method with an explicit parameter list
 * </pre>
 *
 * <p>Type names must be fully qualified — use the {@code find_type} tool to resolve a simple
 * name first. Only source types are considered; types coming from JARs are skipped because
 * they cannot be refactored.</p>
 */
final class JavaElementSelector {

	private JavaElementSelector() {
	}

	/**
	 * Resolves a selector to the type, method or field it names.
	 *
	 * @param selector    the selector string, see class javadoc
	 * @param projectName optional project to limit the search to
	 * @return the resolved element, never {@code null}
	 * @throws IllegalArgumentException if nothing matches, or the match is ambiguous
	 */
	static IJavaElement resolve(String selector, String projectName) throws Exception {
		int hash = selector.indexOf('#');
		if (hash < 0) {
			return findType(selector, projectName);
		}

		String typeName = selector.substring(0, hash).trim();
		String memberPart = selector.substring(hash + 1).trim();
		if (typeName.isEmpty() || memberPart.isEmpty()) {
			throw new IllegalArgumentException("Malformed selector '" + selector
					+ "'. Expected 'package.Type#member' or 'package.Type#method(ParamType,...)'");
		}
		return findMember(findType(typeName, projectName), memberPart);
	}

	/**
	 * Resolves a fully qualified type name to its (source) {@link IType}.
	 *
	 * <p>The same type is reachable from every project that depends on it, so candidates are
	 * de-duplicated by the workspace path of the backing {@code .java} file. Only a genuine
	 * collision — the same fully qualified name declared in two different files — is reported
	 * as ambiguous.</p>
	 *
	 * @param typeName    fully qualified type name
	 * @param projectName optional project to limit the search to
	 * @return the resolved type, never {@code null}
	 */
	static IType findType(String typeName, String projectName) throws Exception {
		if (typeName.indexOf('.') < 0) {
			throw new IllegalArgumentException("Type name '" + typeName
					+ "' is not fully qualified. Use e.g. 'com.example.Bar' (find_type resolves simple names).");
		}

		Map<String, IType> bySourceFile = new LinkedHashMap<>();
		List<String> searchedProjects = new ArrayList<>();

		for (IJavaProject javaProject : javaProjects(projectName)) {
			searchedProjects.add(javaProject.getElementName());
			IType type = findTypeIn(javaProject, typeName);
			if (type == null || type.isBinary()) {
				continue;
			}
			IResource resource = type.getResource();
			if (resource != null) {
				bySourceFile.putIfAbsent(resource.getFullPath().toString(), type);
			}
		}

		if (bySourceFile.isEmpty()) {
			throw new IllegalArgumentException("Source type '" + typeName + "' not found. Searched projects: "
					+ searchedProjects);
		}
		if (bySourceFile.size() > 1) {
			throw new IllegalArgumentException("Type '" + typeName + "' is declared in multiple source files: "
					+ bySourceFile.keySet() + ". Specify 'projectName' to disambiguate.");
		}
		return bySourceFile.values().iterator().next();
	}

	/**
	 * Looks up a type in one project, retrying with {@code $} separators so that dotted nested
	 * type names such as {@code com.example.Bar.Inner} resolve.
	 */
	private static IType findTypeIn(IJavaProject project, String typeName) throws JavaModelException {
		IType type = project.findType(typeName);
		if (type != null) {
			return type;
		}

		String candidate = typeName;
		int dot;
		while ((dot = candidate.lastIndexOf('.')) > 0) {
			candidate = candidate.substring(0, dot) + "$" + candidate.substring(dot + 1);
			type = project.findType(candidate);
			if (type != null) {
				return type;
			}
		}
		return null;
	}

	/**
	 * Resolves the part of a selector after {@code #} against a type's methods and fields.
	 *
	 * <p>Without an explicit parameter list a bare name may match several overloads, or both a
	 * method and a field. That is reported as an error listing the candidates rather than
	 * guessed at, so the caller can retry with a qualified selector.</p>
	 */
	private static IJavaElement findMember(IType type, String memberPart) throws Exception {
		String name = memberPart;
		String paramSpec = null;

		int paren = memberPart.indexOf('(');
		if (paren >= 0) {
			if (!memberPart.endsWith(")")) {
				throw new IllegalArgumentException("Malformed parameter list in '" + memberPart + "' — expected e.g. 'doWork(String,int)'");
			}
			name = memberPart.substring(0, paren).trim();
			paramSpec = normalizeParameters(memberPart.substring(paren + 1, memberPart.length() - 1));
		}

		List<IJavaElement> candidates = new ArrayList<>();
		List<String> descriptions = new ArrayList<>();

		for (IMethod method : type.getMethods()) {
			if (!method.getElementName().equals(name)) {
				continue;
			}
			String signature = simpleParameterSignature(method);
			if (paramSpec == null || paramSpec.equals(signature)) {
				candidates.add(method);
				descriptions.add(name + "(" + signature + ")");
			}
		}

		if (paramSpec == null) {
			IField field = type.getField(name);
			if (field.exists()) {
				candidates.add(field);
				descriptions.add(name + " (field)");
			}
		}

		if (candidates.isEmpty()) {
			throw new IllegalArgumentException("No member '" + memberPart + "' on type "
					+ type.getFullyQualifiedName() + ". Known members: " + memberNames(type));
		}
		if (candidates.size() > 1) {
			throw new IllegalArgumentException("Selector '" + type.getFullyQualifiedName() + "#" + memberPart
					+ "' is ambiguous: " + descriptions
					+ ". Qualify it with an explicit parameter list, e.g. '" + descriptions.get(0) + "'.");
		}
		return candidates.get(0);
	}

	/** Comma-joined simple names of a method's parameter types, e.g. {@code "String,int"}. */
	private static String simpleParameterSignature(IMethod method) throws JavaModelException {
		StringBuilder joined = new StringBuilder();
		for (String parameterType : method.getParameterTypes()) {
			if (joined.length() > 0) {
				joined.append(',');
			}
			joined.append(Signature.getSimpleName(Signature.toString(parameterType)));
		}
		return joined.toString();
	}

	/**
	 * Normalizes a caller-supplied parameter list to the same shape
	 * {@link #simpleParameterSignature} produces, so the two can be compared directly.
	 */
	private static String normalizeParameters(String spec) {
		if (spec.isBlank()) {
			return "";
		}

		StringBuilder normalized = new StringBuilder();
		int depth = 0;
		int start = 0;
		for (int i = 0; i <= spec.length(); i++) {
			char c = i < spec.length() ? spec.charAt(i) : ',';
			if (c == '<') {
				depth++;
			} else if (c == '>') {
				depth--;
			} else if (c == ',' && depth == 0) {
				if (normalized.length() > 0) {
					normalized.append(',');
				}
				normalized.append(Signature.getSimpleName(spec.substring(start, i).trim()));
				start = i + 1;
			}
		}
		return normalized.toString();
	}

	/** Method and field names of a type, for "no such member" error messages. */
	private static List<String> memberNames(IType type) throws JavaModelException {
		List<String> names = new ArrayList<>();
		for (IMethod method : type.getMethods()) {
			names.add(method.getElementName() + "()");
		}
		for (IField field : type.getFields()) {
			names.add(field.getElementName());
		}
		return names;
	}

	/** The projects to search — the named one, or every Java project in the workspace. */
	private static List<IJavaProject> javaProjects(String projectName) throws Exception {
		if (projectName != null && !projectName.isBlank()) {
			IProject project = ResourcesPlugin.getWorkspace().getRoot().getProject(projectName);
			if (!project.exists() || !project.isOpen()) {
				throw new IllegalArgumentException("Project not found or not open: " + projectName);
			}
			return List.of(JavaCore.create(project));
		}
		return List.of(JavaCore.create(ResourcesPlugin.getWorkspace().getRoot()).getJavaProjects());
	}
}
