package com.eclipse.mcp.server.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchEngine;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jdt.core.search.SearchParticipant;
import org.eclipse.jdt.core.search.SearchPattern;
import org.eclipse.jdt.core.search.SearchRequestor;
import org.eclipse.ui.PlatformUI;

public class FindTypeTool implements Tool {

    @Override
    public Object execute(Map<String, Object> arguments) throws Exception {
        String typeName = (String) arguments.get("typeName");
        Boolean caseSensitive = (Boolean) arguments.getOrDefault("caseSensitive", false);
        
        if (typeName == null || typeName.trim().isEmpty()) {
            throw new IllegalArgumentException("typeName is required");
        }
        
        return PlatformUI.getWorkbench().getDisplay().syncCall(() -> {
            try {
                return findTypes(typeName, caseSensitive);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
    
    private List<Map<String, Object>> findTypes(String typeName, boolean caseSensitive) throws CoreException {
        List<Map<String, Object>> results = new ArrayList<>();
        
        SearchEngine searchEngine = new SearchEngine();
        
        int matchRule = SearchPattern.R_PATTERN_MATCH;
        if (caseSensitive) {    
            matchRule |= SearchPattern.R_CASE_SENSITIVE;
        }
        
        SearchPattern pattern = SearchPattern.createPattern(
            typeName,
            IJavaSearchConstants.TYPE,
            IJavaSearchConstants.DECLARATIONS,
            matchRule
        );
        
        SearchRequestor requestor = new SearchRequestor() {
            @Override
            public void acceptSearchMatch(SearchMatch match) throws CoreException {
                if (match.getElement() instanceof IType) {
                    IType type = (IType) match.getElement();
                    
                    Map<String, Object> result = new HashMap<>();
                    result.put("typeName", type.getFullyQualifiedName());
                    result.put("elementName", type.getElementName());
                    result.put("packageName", type.getPackageFragment().getElementName());
                    
                    IJavaProject javaProject = type.getJavaProject();
                    if (javaProject != null) {
                        result.put("projectName", javaProject.getElementName());
                    }
                    
                    if (type.getCompilationUnit() != null) {
                        result.put("fileName", type.getCompilationUnit().getElementName());
                        result.put("filePath", type.getCompilationUnit().getResource().getFullPath().toString());
                    } else if (type.getClassFile() != null) {
                        result.put("fileName", type.getClassFile().getElementName());
                        result.put("filePath", type.getClassFile().getResource().getFullPath().toString());
                        result.put("isFromJar", true);
                    }
                    
                    try {
                        result.put("isInterface", type.isInterface());
                        result.put("isClass", type.isClass());
                        result.put("isEnum", type.isEnum());
                        result.put("isAnnotation", type.isAnnotation());
                    } catch (Exception e) {
                        // Ignore if we can't determine type information
                    }
                    
                    results.add(result);
                }
            }
        };
        
        IJavaProject[] projects = JavaCore.create(org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot()).getJavaProjects();
        
        searchEngine.search(
            pattern,
            new SearchParticipant[] { SearchEngine.getDefaultSearchParticipant() },
            SearchEngine.createJavaSearchScope(projects),
            requestor,
            null
        );
        
        return results;
    }
}