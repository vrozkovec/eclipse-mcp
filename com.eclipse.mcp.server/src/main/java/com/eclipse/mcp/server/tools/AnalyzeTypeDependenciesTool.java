package com.eclipse.mcp.server.tools;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MarkerAnnotation;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.NormalAnnotation;
import org.eclipse.jdt.core.dom.ParameterizedType;
import org.eclipse.jdt.core.dom.QualifiedType;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.SingleMemberAnnotation;
import org.eclipse.ui.PlatformUI;

/**
 * MCP tool that analyzes all type dependencies of a given Java type.
 * Returns all types referenced in the source, grouped by package and source (project/JAR).
 * Useful for identifying which types can be safely extracted to another project
 * without bringing unwanted transitive dependencies.
 */
public class AnalyzeTypeDependenciesTool implements Tool {

	private static final int MAX_TRANSITIVE_TYPES = 100;

	@Override
	public Object execute(Map<String, Object> arguments) throws Exception {
		String typeName = (String) arguments.get("typeName");
		@SuppressWarnings("unchecked")
		List<String> excludePackages = (List<String>) arguments.get("excludePackages");
		Boolean includeTransitive = (Boolean) arguments.getOrDefault("includeTransitive", false);

		if (typeName == null || typeName.trim().isEmpty()) {
			throw new IllegalArgumentException("typeName is required (fully qualified, e.g. 'com.example.MyClass')");
		}

		if (excludePackages == null) {
			excludePackages = List.of();
		}

		final List<String> excludes = excludePackages;

		return PlatformUI.getWorkbench().getDisplay().syncCall(() -> {
			try {
				return analyzeTypeDependencies(typeName, excludes, includeTransitive);
			} catch (Exception e) {
				throw new RuntimeException(e);
			}
		});
	}

	private Map<String, Object> analyzeTypeDependencies(String typeName, List<String> excludePackages,
			boolean includeTransitive) throws CoreException {

		IType targetType = resolveType(typeName);
		String targetProject = targetType.getJavaProject().getElementName();

		Map<String, DependencyInfo> allDependencies = new LinkedHashMap<>();
		Set<String> analyzedTypes = new HashSet<>();
		int unresolvedBindings = 0;

		if (includeTransitive) {
			Queue<IType> toAnalyze = new LinkedList<>();
			toAnalyze.add(targetType);
			analyzedTypes.add(typeName);

			while (!toAnalyze.isEmpty() && analyzedTypes.size() < MAX_TRANSITIVE_TYPES) {
				IType currentType = toAnalyze.poll();
				int[] unresolved = { 0 };
				Map<String, DependencyInfo> deps = collectDependencies(currentType, typeName, unresolved);
				unresolvedBindings += unresolved[0];

				for (Map.Entry<String, DependencyInfo> entry : deps.entrySet()) {
					if (!allDependencies.containsKey(entry.getKey())) {
						allDependencies.put(entry.getKey(), entry.getValue());

						DependencyInfo info = entry.getValue();
						if ("project".equals(info.sourceType)
								&& targetProject.equals(info.sourceProject)
								&& !analyzedTypes.contains(info.fqn)) {
							IType nextType = targetType.getJavaProject().findType(info.fqn);
							if (nextType != null && nextType.getCompilationUnit() != null) {
								toAnalyze.add(nextType);
								analyzedTypes.add(info.fqn);
							}
						}
					}
				}
			}
		} else {
			analyzedTypes.add(typeName);
			int[] unresolved = { 0 };
			allDependencies.putAll(collectDependencies(targetType, typeName, unresolved));
			unresolvedBindings = unresolved[0];
		}

		flagExcludedPackages(allDependencies, excludePackages);

		return buildResult(typeName, targetProject, allDependencies, excludePackages,
				includeTransitive, analyzedTypes, unresolvedBindings);
	}

	private IType resolveType(String typeName) throws CoreException {
		IJavaProject[] allProjects = JavaCore.create(
				ResourcesPlugin.getWorkspace().getRoot()).getJavaProjects();

		for (IJavaProject project : allProjects) {
			IType type = project.findType(typeName);
			if (type != null) {
				if (type.getCompilationUnit() == null) {
					throw new IllegalArgumentException(
							"Type has no source available for analysis (binary type): " + typeName);
				}
				return type;
			}
		}

		throw new IllegalArgumentException("Type not found in workspace: " + typeName);
	}

	/**
	 * Collects all type dependencies from the given type's compilation unit using AST analysis.
	 */
	private Map<String, DependencyInfo> collectDependencies(IType type, String rootTypeName,
			int[] unresolvedCount) {

		ICompilationUnit cu = type.getCompilationUnit();
		Map<String, DependencyInfo> dependencies = new LinkedHashMap<>();
		Set<String> seen = new HashSet<>();

		ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
		parser.setKind(ASTParser.K_COMPILATION_UNIT);
		parser.setSource(cu);
		parser.setResolveBindings(true);
		parser.setBindingsRecovery(true);
		parser.setStatementsRecovery(true);

		CompilationUnit astRoot = (CompilationUnit) parser.createAST(null);

		String selfFqn = type.getFullyQualifiedName();

		astRoot.accept(new ASTVisitor() {

			@Override
			public boolean visit(SimpleType node) {
				collectBinding(node.resolveBinding());
				return true;
			}

			@Override
			public boolean visit(QualifiedType node) {
				collectBinding(node.resolveBinding());
				return true;
			}

			@Override
			public boolean visit(ParameterizedType node) {
				collectBinding(node.resolveBinding());
				return true;
			}

			@Override
			public boolean visit(ClassInstanceCreation node) {
				if (node.resolveConstructorBinding() != null) {
					collectBinding(node.resolveConstructorBinding().getDeclaringClass());
				}
				return true;
			}

			@Override
			public boolean visit(MethodInvocation node) {
				if (node.resolveMethodBinding() != null) {
					collectBinding(node.resolveMethodBinding().getDeclaringClass());
				}
				return true;
			}

			@Override
			public boolean visit(MarkerAnnotation node) {
				if (node.resolveAnnotationBinding() != null) {
					collectBinding(node.resolveAnnotationBinding().getAnnotationType());
				}
				return true;
			}

			@Override
			public boolean visit(NormalAnnotation node) {
				if (node.resolveAnnotationBinding() != null) {
					collectBinding(node.resolveAnnotationBinding().getAnnotationType());
				}
				return true;
			}

			@Override
			public boolean visit(SingleMemberAnnotation node) {
				if (node.resolveAnnotationBinding() != null) {
					collectBinding(node.resolveAnnotationBinding().getAnnotationType());
				}
				return true;
			}

			private void collectBinding(ITypeBinding binding) {
				if (binding == null) {
					unresolvedCount[0]++;
					return;
				}

				binding = unwrapBinding(binding);
				if (binding == null) {
					return;
				}

				String fqn = binding.getQualifiedName();
				if (fqn == null || fqn.isEmpty()) {
					return;
				}

				if (fqn.equals(selfFqn) || fqn.equals(rootTypeName)) {
					return;
				}

				if (binding.isPrimitive() || binding.isTypeVariable() || binding.isNullType()) {
					return;
				}

				if (!seen.add(fqn)) {
					return;
				}

				DependencyInfo info = new DependencyInfo();
				info.fqn = fqn;
				info.packageName = binding.getPackage() != null ? binding.getPackage().getName() : "";

				IJavaElement element = binding.getJavaElement();
				if (element instanceof IType itype) {
					IJavaProject jp = itype.getJavaProject();
					info.sourceProject = jp != null ? jp.getElementName() : "unknown";
					try {
						IPackageFragmentRoot root = (IPackageFragmentRoot) itype
								.getAncestor(IJavaElement.PACKAGE_FRAGMENT_ROOT);
						if (root != null) {
							if (root.getKind() == IPackageFragmentRoot.K_SOURCE) {
								info.sourceType = "project";
							} else {
								info.sourceType = "jar";
								info.jarName = root.getElementName();
							}
						}
					} catch (Exception e) {
						info.sourceType = "unknown";
					}
				} else {
					info.sourceProject = "unresolved";
					info.sourceType = "unknown";
				}

				dependencies.put(fqn, info);
			}

			private ITypeBinding unwrapBinding(ITypeBinding binding) {
				if (binding.isArray()) {
					binding = binding.getElementType();
				}

				if (binding.isParameterizedType()) {
					for (ITypeBinding typeArg : binding.getTypeArguments()) {
						collectBinding(typeArg);
					}
					binding = binding.getErasure();
				}

				if (binding.isWildcardType()) {
					ITypeBinding bound = binding.getBound();
					if (bound != null) {
						collectBinding(bound);
					}
					return null;
				}

				return binding;
			}
		});

		return dependencies;
	}

	private void flagExcludedPackages(Map<String, DependencyInfo> dependencies, List<String> excludePackages) {
		if (excludePackages.isEmpty()) {
			return;
		}

		for (DependencyInfo info : dependencies.values()) {
			for (String prefix : excludePackages) {
				if (info.packageName.startsWith(prefix)) {
					info.excluded = true;
					info.excludedByRule = prefix;
					break;
				}
			}
		}
	}

	private Map<String, Object> buildResult(String typeName, String projectName,
			Map<String, DependencyInfo> dependencies, List<String> excludePackages,
			boolean includeTransitive, Set<String> analyzedTypes, int unresolvedBindings) {

		Map<String, List<DependencyInfo>> byPackage = dependencies.values().stream()
				.collect(Collectors.groupingBy(d -> d.packageName, LinkedHashMap::new, Collectors.toList()));

		Map<String, Object> packageGroups = new LinkedHashMap<>();
		for (Map.Entry<String, List<DependencyInfo>> entry : byPackage.entrySet()) {
			Map<String, Object> group = new LinkedHashMap<>();
			DependencyInfo first = entry.getValue().get(0);
			group.put("sourceProject", first.sourceProject);
			group.put("sourceType", first.sourceType);
			if (first.jarName != null) {
				group.put("jarName", first.jarName);
			}
			group.put("types", entry.getValue().stream().map(DependencyInfo::toMap).toList());
			packageGroups.put(entry.getKey(), group);
		}

		long excludedCount = dependencies.values().stream().filter(d -> d.excluded).count();

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("analyzedType", typeName);
		result.put("analyzedTypeProject", projectName);
		result.put("totalDependencies", dependencies.size());
		result.put("excludedDependencies", excludedCount);
		if (unresolvedBindings > 0) {
			result.put("unresolvedBindings", unresolvedBindings);
		}
		if (includeTransitive) {
			result.put("transitiveAnalysis", true);
			result.put("typesAnalyzed", analyzedTypes.size());
			if (analyzedTypes.size() >= MAX_TRANSITIVE_TYPES) {
				result.put("warning", "Transitive analysis capped at " + MAX_TRANSITIVE_TYPES + " types");
			}
		}
		result.put("dependenciesByPackage", packageGroups);

		return result;
	}

	private static class DependencyInfo {

		String fqn;
		String packageName;
		String sourceProject;
		String sourceType;
		String jarName;
		boolean excluded;
		String excludedByRule;

		Map<String, Object> toMap() {
			Map<String, Object> map = new LinkedHashMap<>();
			map.put("fqn", fqn);
			map.put("excluded", excluded);
			if (excludedByRule != null) {
				map.put("excludedByRule", excludedByRule);
			}
			return map;
		}
	}
}
