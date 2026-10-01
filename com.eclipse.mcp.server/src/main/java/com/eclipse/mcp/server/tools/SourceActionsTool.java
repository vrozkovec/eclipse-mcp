package com.eclipse.mcp.server.tools;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IAnnotation;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.Initializer;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jdt.core.manipulation.SharedASTProviderCore;
import org.eclipse.jdt.internal.corext.codemanipulation.AddCustomConstructorOperation;
import org.eclipse.jdt.internal.corext.codemanipulation.AddGetterSetterOperation;
import org.eclipse.jdt.internal.corext.codemanipulation.CodeGenerationSettings;
import org.eclipse.jdt.internal.corext.codemanipulation.GenerateHashCodeEqualsOperation;
import org.eclipse.jdt.internal.corext.codemanipulation.GetterSetterUtil;
import org.eclipse.jdt.internal.corext.codemanipulation.StubUtility2Core;
import org.eclipse.jdt.internal.corext.codemanipulation.tostringgeneration.GenerateToStringOperation;
import org.eclipse.jdt.internal.corext.codemanipulation.tostringgeneration.ToStringGenerationSettingsCore;
import org.eclipse.jdt.internal.corext.codemanipulation.tostringgeneration.ToStringTemplateParser;
import org.eclipse.jdt.internal.corext.fix.CleanUpConstants;
import org.eclipse.jdt.internal.corext.util.JavaModelUtil;
import org.eclipse.jdt.internal.ui.preferences.JavaPreferencesSettings;
import org.eclipse.jdt.ui.PreferenceConstants;
import org.eclipse.jdt.ui.cleanup.CleanUpOptions;
import org.eclipse.ui.PlatformUI;

/**
 * Generates code like Eclipse's Source menu (Alt+Shift+S) and saves the file: the missing
 * getters and setters, a constructor using fields, {@code hashCode()}/{@code equals()}, or
 * {@code toString()}.
 *
 * <p>The work is delegated to the operations behind Eclipse's own dialogs, so the code follows
 * the project's code templates, formatter and getter/setter naming. The defaults mirror those
 * dialogs (the same fields are preselected, toString() uses the default template and string
 * concatenation), except that javadoc comments are always generated from the code templates.
 * The operations are internal JDT API, like the ones {@link CleanupCodeTool} uses, so an Eclipse
 * update can change them.</p>
 *
 * <p>Member order: {@code hashCode()}, {@code equals()} and {@code toString()} are kept as the
 * last members of the class, in that order. Generating any of them moves the existing ones there
 * too. Getters and setters are inserted before them, and a constructor after the fields and the
 * existing constructors.</p>
 */
public class SourceActionsTool implements Tool {

    private static final String GETTERS_SETTERS = "generate_getters_setters";
    private static final String CONSTRUCTOR = "generate_constructor";
    private static final String HASHCODE_EQUALS = "generate_hashcode_equals";
    private static final String TO_STRING = "generate_toString";

    /** The methods kept as the last members of a class, in this order. */
    private static final List<String> OBJECT_METHODS = List.of("hashCode", "equals", "toString");

    @Override
    @SuppressWarnings("unchecked")
    public Object execute(Map<String, Object> arguments) throws Exception {
        String filePath = (String) arguments.get("filePath");
        String action = (String) arguments.get("action");
        String typeName = (String) arguments.get("typeName");
        List<String> fieldNames = (List<String>) arguments.get("fields");
        boolean replaceExisting = Boolean.TRUE.equals(arguments.get("replaceExisting"));

        if (filePath == null || filePath.trim().isEmpty()) {
            throw new IllegalArgumentException("filePath is required");
        }
        if (!List.of(GETTERS_SETTERS, CONSTRUCTOR, HASHCODE_EQUALS, TO_STRING).contains(action)) {
            throw new IllegalArgumentException("Unknown action: " + action + ". Supported actions: "
                    + String.join(", ", GETTERS_SETTERS, CONSTRUCTOR, HASHCODE_EQUALS, TO_STRING));
        }

        return PlatformUI.getWorkbench().getDisplay().syncCall(() -> {
            try {
                return executeSourceAction(filePath, action, typeName, fieldNames, replaceExisting);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Runs one source action on a type and saves the file. Must run on the UI thread.
     *
     * @param filePath        absolute filesystem path or workspace path of the {@code .java} file
     * @param action          one of the supported action names
     * @param typeName        simple or qualified name of the target type, or {@code null} for the
     *                        file's primary type
     * @param fieldNames      the fields to use, or {@code null} for the action's default selection
     * @param replaceExisting whether existing hashCode()/equals()/toString() may be regenerated
     * @return the result: status, the fields used and the signatures of the generated methods
     * @throws Exception if the file, type or fields cannot be resolved, or the generation fails
     */
    private Map<String, Object> executeSourceAction(String filePath, String action, String typeName,
            List<String> fieldNames, boolean replaceExisting) throws Exception {
        IProgressMonitor monitor = new NullProgressMonitor();
        // The operations edit Eclipse's copy of the file and save it: resolveFile() picks up edits
        // made outside Eclipse first, and unsaved changes in an editor are never saved over.
        IFile file = resolveFile(filePath, monitor);
        ICompilationUnit cu = JavaCore.createCompilationUnitFrom(file);
        if (cu == null || !cu.exists()) {
            throw new IllegalArgumentException("Not a Java source file on the build path: " + filePath);
        }
        if (cu.hasUnsavedChanges()) {
            throw new IllegalStateException(file.getName() + " has unsaved changes in an Eclipse editor; save or revert them first");
        }

        IType type = findType(cu, typeName);
        if (type.isInterface()) {
            throw new IllegalArgumentException(type.getElementName() + " is an interface or annotation, not a class");
        }
        CompilationUnit astRoot = SharedASTProviderCore.getAST(cu, SharedASTProviderCore.WAIT_YES, monitor);
        AbstractTypeDeclaration declaration = astRoot != null ? findDeclaration(astRoot, type) : null;
        ITypeBinding typeBinding = declaration != null ? declaration.resolveBinding() : null;
        if (typeBinding == null) {
            throw new IllegalStateException("Cannot resolve " + type.getElementName() + "; fix its compile errors first");
        }

        List<IField> fields = fieldNames == null || fieldNames.isEmpty()
                ? defaultFields(type, action, astRoot)
                : namedFields(type, fieldNames);
        CodeGenerationSettings settings = JavaPreferencesSettings.getCodeGenerationSettings(cu.getJavaProject());
        settings.createComments = true;

        Set<String> before = methodSignatures(declaration);
        List<String> replaced = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<IField> used = switch (action) {
            case GETTERS_SETTERS -> generateGettersSetters(type, declaration, fields, settings, monitor);
            case CONSTRUCTOR -> generateConstructor(type, declaration, typeBinding, fields, settings, monitor);
            case HASHCODE_EQUALS -> generateHashCodeEquals(declaration, typeBinding, fields, settings, replaceExisting,
                    replaced, monitor);
            default -> generateToString(declaration, typeBinding, fields, settings, replaceExisting, replaced, monitor);
        };
        if (action.equals(HASHCODE_EQUALS) || action.equals(TO_STRING)) {
            moveObjectMethodsLast(cu, type, monitor);
        }

        List<String> generated = new ArrayList<>(methodSignatures(findDeclaration(parse(cu, monitor), type)));
        generated.removeAll(before);
        if (action.equals(CONSTRUCTOR) && isJpaEntity(type) && !before.contains(type.getElementName() + "()")) {
            warnings.add(type.getElementName() + " is a JPA entity and now has no no-arg constructor, which JPA requires; add one, e.g. protected "
                    + type.getElementName() + "() {}");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", generated.isEmpty() && replaced.isEmpty() ? "nothing_to_generate" : "generated");
        result.put("action", action);
        result.put("file", file.getLocation().toOSString());
        result.put("type", type.getFullyQualifiedName('.'));
        result.put("fields", used.stream().map(IField::getElementName).toList());
        result.put("generated", generated);
        if (!replaced.isEmpty()) {
            result.put("replaced", replaced);
        }
        if (!warnings.isEmpty()) {
            result.put("warnings", warnings);
        }
        return result;
    }

    /**
     * Adds the missing getters and setters, in getter/setter pairs following the field order,
     * before the trailing hashCode()/equals()/toString() methods. Existing accessors are kept.
     * Final fields only get a getter: Eclipse would drop the final modifier to add a setter.
     *
     * @return the fields that received an accessor
     */
    private List<IField> generateGettersSetters(IType type, AbstractTypeDeclaration declaration, List<IField> fields,
            CodeGenerationSettings settings, IProgressMonitor monitor) throws CoreException {
        List<IField> pairs = new ArrayList<>();
        List<IField> getters = new ArrayList<>();
        List<IField> setters = new ArrayList<>();
        for (IField field : fields) {
            boolean needsGetter = GetterSetterUtil.getGetter(field) == null;
            boolean needsSetter = !Flags.isFinal(field.getFlags()) && GetterSetterUtil.getSetter(field) == null;
            if (needsGetter && needsSetter) {
                pairs.add(field);
            } else if (needsGetter) {
                getters.add(field);
            } else if (needsSetter) {
                setters.add(field);
            }
        }
        List<IField> used = fields.stream()
                .filter(field -> pairs.contains(field) || getters.contains(field) || setters.contains(field))
                .toList();
        if (used.isEmpty()) {
            return used;
        }
        // A null skip-existing query makes the operation skip accessors that already exist.
        new AddGetterSetterOperation(type, getters.toArray(IField[]::new), setters.toArray(IField[]::new),
                pairs.toArray(IField[]::new), (CompilationUnit) declaration.getRoot(), null,
                javaElement(objectMethodsStart(declaration)), settings, true, true).run(monitor);
        return used;
    }

    /**
     * Adds a public constructor initializing the fields, after the fields and the existing
     * constructors. It calls the superclass's no-arg constructor implicitly, or the first visible
     * superclass constructor (whose parameters it then takes first) if there is no no-arg one.
     * Refuses a constructor that would not compile: one leaving a final field without a value, or
     * one with the parameter types of an existing constructor.
     *
     * @return the fields the constructor initializes
     */
    private List<IField> generateConstructor(IType type, AbstractTypeDeclaration declaration, ITypeBinding typeBinding,
            List<IField> fields, CodeGenerationSettings settings, IProgressMonitor monitor) throws CoreException {
        if (fields.isEmpty()) {
            throw new IllegalStateException(type.getElementName() + " has no fields a constructor could initialize");
        }
        CompilationUnit astRoot = (CompilationUnit) declaration.getRoot();
        List<String> uninitialized = new ArrayList<>();
        for (IField field : type.getFields()) {
            int flags = field.getFlags();
            if (Flags.isFinal(flags) && !Flags.isStatic(flags) && !fields.contains(field) && !hasInitializer(field, astRoot)) {
                uninitialized.add(field.getElementName());
            }
        }
        if (!uninitialized.isEmpty()) {
            throw new IllegalArgumentException("The constructor must also initialize the final field(s) " + uninitialized
                    + "; add them to fields");
        }
        IMethodBinding[] superConstructors = StubUtility2Core.getVisibleConstructors(typeBinding, false, true);
        if (superConstructors.length == 0) {
            throw new IllegalStateException("The superclass of " + type.getElementName() + " has no visible constructor");
        }
        IMethodBinding superConstructor = superConstructors[0];
        for (IMethodBinding candidate : superConstructors) {
            if (candidate.getParameterTypes().length == 0) {
                superConstructor = candidate;
                break;
            }
        }

        IVariableBinding[] fieldBindings = bindings(typeBinding, fields);
        List<String> parameterTypes = new ArrayList<>();
        for (ITypeBinding parameterType : superConstructor.getParameterTypes()) {
            parameterTypes.add(parameterType.getErasure().getQualifiedName());
        }
        for (IVariableBinding fieldBinding : fieldBindings) {
            parameterTypes.add(fieldBinding.getType().getErasure().getQualifiedName());
        }
        for (IMethodBinding method : typeBinding.getDeclaredMethods()) {
            if (method.isConstructor() && erasedParameterTypes(method).equals(parameterTypes)) {
                throw new IllegalStateException("A constructor with the parameter types " + parameterTypes + " already exists in "
                        + type.getElementName());
            }
        }

        AddCustomConstructorOperation operation = new AddCustomConstructorOperation(astRoot, typeBinding, fieldBindings,
                superConstructor, javaElement(constructorPosition(declaration)), settings, true, true);
        operation.setVisibility(type.isEnum() ? Modifier.PRIVATE : Modifier.PUBLIC);
        operation.setOmitSuper(superConstructor.getParameterTypes().length == 0);
        operation.run(monitor);
        return fields;
    }

    /**
     * Adds hashCode() and equals() using {@code Objects.hash}/{@code Objects.equals} and a
     * {@code getClass()} comparison (Eclipse's defaults), in front of an existing toString().
     *
     * @return the fields compared
     */
    private List<IField> generateHashCodeEquals(AbstractTypeDeclaration declaration, ITypeBinding typeBinding,
            List<IField> fields, CodeGenerationSettings settings, boolean replaceExisting, List<String> replaced,
            IProgressMonitor monitor) throws CoreException {
        if (fields.isEmpty()) {
            throw new IllegalStateException(typeBinding.getName() + " has no fields to compare");
        }
        List<String> existing = existingObjectMethods(declaration, "hashCode", "equals");
        if (!existing.isEmpty() && !replaceExisting) {
            throw new IllegalStateException(String.join(" and ", existing) + " already exist(s) in " + typeBinding.getName()
                    + "; pass replaceExisting: true to regenerate");
        }
        replaced.addAll(existing);

        GenerateHashCodeEqualsOperation operation = new GenerateHashCodeEqualsOperation(typeBinding, bindings(typeBinding, fields),
                (CompilationUnit) declaration.getRoot(), javaElement(findObjectMethod(declaration, "toString")), settings,
                false, true, replaceExisting, true, true);
        operation.setUseBlocksForThen(useBlocks(typeBinding));
        operation.run(monitor);
        return fields;
    }

    /**
     * Adds toString() with Eclipse's default template
     * ({@code ClassName [field=value, ...]}, string concatenation) at the end of the type.
     *
     * @return the fields printed
     */
    private List<IField> generateToString(AbstractTypeDeclaration declaration, ITypeBinding typeBinding, List<IField> fields,
            CodeGenerationSettings settings, boolean replaceExisting, List<String> replaced, IProgressMonitor monitor)
            throws CoreException {
        List<String> existing = existingObjectMethods(declaration, "toString");
        if (!existing.isEmpty() && !replaceExisting) {
            throw new IllegalStateException("toString() already exists in " + typeBinding.getName()
                    + "; pass replaceExisting: true to regenerate it");
        }
        replaced.addAll(existing);

        ToStringGenerationSettingsCore toStringSettings = new ToStringGenerationSettingsCore();
        settings.setSettings(toStringSettings);
        toStringSettings.toStringStyle = GenerateToStringOperation.STRING_CONCATENATION;
        toStringSettings.stringFormatTemplate = ToStringTemplateParser.DEFAULT_TEMPLATE;
        toStringSettings.customArrayToString = true;
        toStringSettings.limitValue = 10;
        toStringSettings.useBlocks = useBlocks(typeBinding);
        toStringSettings.customBuilderSettings = new ToStringGenerationSettingsCore.CustomBuilderSettings();
        // An existing toString() is replaced in place; moveObjectMethodsLast() fixes its position.
        GenerateToStringOperation.createOperation(typeBinding, bindings(typeBinding, fields), (CompilationUnit) declaration.getRoot(),
                null, toStringSettings, true, true).run(monitor);
        return fields;
    }

    /**
     * Moves hashCode(), equals() and toString(), as far as they exist, to the end of the type in
     * this order, unless they already are its last members, and saves the file.
     */
    private void moveObjectMethodsLast(ICompilationUnit cu, IType type, IProgressMonitor monitor) throws CoreException {
        CompilationUnit astRoot = parse(cu, monitor);
        AbstractTypeDeclaration declaration = findDeclaration(astRoot, type);
        List<MethodDeclaration> objectMethods = new ArrayList<>();
        for (String name : OBJECT_METHODS) {
            MethodDeclaration method = findObjectMethod(declaration, name);
            if (method != null) {
                objectMethods.add(method);
            }
        }
        List<?> members = declaration.bodyDeclarations();
        if (objectMethods.isEmpty() || members.subList(members.size() - objectMethods.size(), members.size()).equals(objectMethods)) {
            return;
        }
        ASTRewrite rewrite = ASTRewrite.create(astRoot.getAST());
        ListRewrite memberRewrite = rewrite.getListRewrite(declaration, declaration.getBodyDeclarationsProperty());
        for (MethodDeclaration method : objectMethods) {
            memberRewrite.insertLast(rewrite.createMoveTarget(method), null);
            memberRewrite.remove(method, null);
        }
        JavaModelUtil.applyEdit(cu, rewrite.rewriteAST(), true, monitor);
    }

    /**
     * Resolves a {@code .java} file given as a workspace path (as {@code find_type} returns it) or
     * as an absolute filesystem path, and refreshes it from disk. A file inside nested projects
     * resolves to the deepest one; a file created outside Eclipse is added to the workspace,
     * together with any new folders.
     */
    private IFile resolveFile(String filePath, IProgressMonitor monitor) throws CoreException {
        IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
        IPath workspacePath = IPath.fromOSString(filePath);
        if (workspacePath.segmentCount() > 1 && root.getFile(workspacePath).exists()) {
            IFile file = root.getFile(workspacePath);
            file.refreshLocal(IResource.DEPTH_ZERO, monitor);
            return file;
        }
        File location = new File(filePath).getAbsoluteFile();
        IFile best = null;
        for (IFile candidate : root.findFilesForLocationURI(location.toURI())) {
            if (candidate.getProject().isOpen() && (best == null || projectDepth(candidate) > projectDepth(best))) {
                best = candidate;
            }
        }
        if (best == null || !location.isFile()) {
            throw new IllegalArgumentException("File not found in the workspace: " + filePath);
        }
        IResource missing = best;
        while (!missing.getParent().exists()) {
            missing = missing.getParent();
        }
        if (missing.exists()) {
            missing.refreshLocal(IResource.DEPTH_ZERO, monitor);
        } else {
            // A new file, possibly in new folders: refresh from the deepest folder Eclipse knows.
            missing.getParent().refreshLocal(missing == best ? IResource.DEPTH_ONE : IResource.DEPTH_INFINITE, monitor);
        }
        return best;
    }

    /**
     * Returns the number of segments of the location of the file's project.
     */
    private static int projectDepth(IFile file) {
        IPath location = file.getProject().getLocation();
        return location != null ? location.segmentCount() : 0;
    }

    /**
     * Finds the target type by simple or fully qualified name, or the file's primary type.
     */
    private IType findType(ICompilationUnit cu, String typeName) throws JavaModelException {
        if (typeName == null || typeName.isBlank()) {
            IType primary = cu.findPrimaryType();
            if (primary != null) {
                return primary;
            }
            IType[] types = cu.getTypes();
            if (types.length == 0) {
                throw new IllegalArgumentException("No type is declared in " + cu.getElementName());
            }
            return types[0];
        }
        for (IType type : cu.getAllTypes()) {
            if (type.getElementName().equals(typeName) || type.getFullyQualifiedName('.').equals(typeName)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Type " + typeName + " not found in " + cu.getElementName());
    }

    /**
     * Returns the fields Eclipse's dialog preselects for the action, in declaration order: the
     * non-static fields, without transient ones for hashCode/equals and toString, and without
     * final fields that have an initializer for a constructor.
     */
    private List<IField> defaultFields(IType type, String action, CompilationUnit astRoot) throws JavaModelException {
        List<IField> fields = new ArrayList<>();
        for (IField field : type.getFields()) {
            int flags = field.getFlags();
            if (Flags.isStatic(flags) || Flags.isEnum(flags)) {
                continue;
            }
            boolean selected = switch (action) {
                case HASHCODE_EQUALS, TO_STRING -> !Flags.isTransient(flags);
                case CONSTRUCTOR -> !Flags.isFinal(flags) || !hasInitializer(field, astRoot);
                default -> true;
            };
            if (selected) {
                fields.add(field);
            }
        }
        return fields;
    }

    /**
     * Resolves explicitly named fields, keeping the given order.
     */
    private List<IField> namedFields(IType type, List<String> fieldNames) throws JavaModelException {
        List<IField> fields = new ArrayList<>();
        for (String name : fieldNames) {
            IField field = type.getField(name);
            if (!field.exists()) {
                List<String> available = new ArrayList<>();
                for (IField candidate : type.getFields()) {
                    available.add(candidate.getElementName());
                }
                throw new IllegalArgumentException("No field " + name + " in " + type.getElementName() + "; its fields are " + available);
            }
            fields.add(field);
        }
        return fields;
    }

    /**
     * Tells whether the field's declaration assigns an initial value.
     */
    private static boolean hasInitializer(IField field, CompilationUnit astRoot) throws JavaModelException {
        ASTNode name = NodeFinder.perform(astRoot, field.getNameRange());
        return name != null && name.getParent() instanceof VariableDeclarationFragment fragment && fragment.getInitializer() != null;
    }

    /**
     * Maps fields to their bindings in the given order.
     */
    private static IVariableBinding[] bindings(ITypeBinding typeBinding, List<IField> fields) {
        Map<String, IVariableBinding> byName = new HashMap<>();
        for (IVariableBinding binding : typeBinding.getDeclaredFields()) {
            byName.put(binding.getName(), binding);
        }
        IVariableBinding[] bindings = new IVariableBinding[fields.size()];
        for (int i = 0; i < bindings.length; i++) {
            bindings[i] = byName.get(fields.get(i).getElementName());
            if (bindings[i] == null) {
                throw new IllegalStateException("Cannot resolve field " + fields.get(i).getElementName());
            }
        }
        return bindings;
    }

    /**
     * Returns the qualified erasures of a method's parameter types.
     */
    private static List<String> erasedParameterTypes(IMethodBinding method) {
        List<String> types = new ArrayList<>();
        for (ITypeBinding parameterType : method.getParameterTypes()) {
            types.add(parameterType.getErasure().getQualifiedName());
        }
        return types;
    }

    /**
     * Returns the first member of the hashCode()/equals()/toString() run that ends the type, or
     * {@code null} if the type does not end with one of these methods.
     */
    private static BodyDeclaration objectMethodsStart(AbstractTypeDeclaration declaration) {
        List<?> members = declaration.bodyDeclarations();
        int start = members.size();
        while (start > 0 && isObjectMethod((BodyDeclaration) members.get(start - 1))) {
            start--;
        }
        return start < members.size() ? (BodyDeclaration) members.get(start) : null;
    }

    /**
     * Returns the member that follows the last field, initializer or constructor, or
     * {@code null} if there is none.
     */
    private static BodyDeclaration constructorPosition(AbstractTypeDeclaration declaration) {
        List<?> members = declaration.bodyDeclarations();
        int last = -1;
        for (int i = 0; i < members.size(); i++) {
            Object member = members.get(i);
            if (member instanceof FieldDeclaration || member instanceof Initializer
                    || member instanceof MethodDeclaration method && method.isConstructor()) {
                last = i;
            }
        }
        return last + 1 < members.size() ? (BodyDeclaration) members.get(last + 1) : null;
    }

    /**
     * Returns the Java element of a member declaration, to be used as an insertion point, or
     * {@code null} (insert at the end) if there is no member or it cannot be resolved.
     */
    private static IJavaElement javaElement(BodyDeclaration member) {
        IBinding binding = member instanceof MethodDeclaration method ? method.resolveBinding()
                : member instanceof AbstractTypeDeclaration type ? type.resolveBinding()
                : null;
        return binding != null ? binding.getJavaElement() : null;
    }

    /**
     * Returns the signatures of the given hashCode()/equals()/toString() methods that the type
     * declares.
     */
    private static List<String> existingObjectMethods(AbstractTypeDeclaration declaration, String... names) {
        List<String> existing = new ArrayList<>();
        for (String name : names) {
            MethodDeclaration method = findObjectMethod(declaration, name);
            if (method != null) {
                existing.add(signature(method));
            }
        }
        return existing;
    }

    /**
     * Finds the type's own {@code hashCode()}, {@code equals(Object)} or {@code toString()}.
     */
    private static MethodDeclaration findObjectMethod(AbstractTypeDeclaration declaration, String name) {
        for (Object member : declaration.bodyDeclarations()) {
            if (isObjectMethod((BodyDeclaration) member) && ((MethodDeclaration) member).getName().getIdentifier().equals(name)) {
                return (MethodDeclaration) member;
            }
        }
        return null;
    }

    /**
     * Tells whether the member declares {@code hashCode()}, {@code equals(Object)} or
     * {@code toString()}.
     */
    private static boolean isObjectMethod(BodyDeclaration member) {
        if (!(member instanceof MethodDeclaration method) || method.isConstructor()) {
            return false;
        }
        List<?> parameters = method.parameters();
        return switch (method.getName().getIdentifier()) {
            case "hashCode", "toString" -> parameters.isEmpty();
            case "equals" -> parameters.size() == 1
                    && ((SingleVariableDeclaration) parameters.get(0)).getType().toString().matches("(java\\.lang\\.)?Object");
            default -> false;
        };
    }

    /**
     * Returns the signatures of the methods and constructors the type declares.
     */
    private static Set<String> methodSignatures(AbstractTypeDeclaration declaration) {
        Set<String> signatures = new LinkedHashSet<>();
        for (Object member : declaration.bodyDeclarations()) {
            if (member instanceof MethodDeclaration method) {
                signatures.add(signature(method));
            }
        }
        return signatures;
    }

    /**
     * Returns a readable signature such as {@code setName(String)}.
     */
    private static String signature(MethodDeclaration method) {
        StringJoiner joiner = new StringJoiner(", ", method.getName().getIdentifier() + "(", ")");
        for (Object parameter : method.parameters()) {
            joiner.add(((SingleVariableDeclaration) parameter).getType().toString());
        }
        return joiner.toString();
    }

    /**
     * Finds the declaration of a top-level or member type in an AST by its name path, so that it
     * also works on an AST parsed after the file changed.
     */
    private static AbstractTypeDeclaration findDeclaration(CompilationUnit astRoot, IType type) {
        List<String> path = new ArrayList<>();
        for (IType current = type; current != null; current = current.getDeclaringType()) {
            path.add(0, current.getElementName());
        }
        List<?> candidates = astRoot.types();
        AbstractTypeDeclaration found = null;
        for (String name : path) {
            found = null;
            for (Object candidate : candidates) {
                if (candidate instanceof AbstractTypeDeclaration declaration && declaration.getName().getIdentifier().equals(name)) {
                    found = declaration;
                    break;
                }
            }
            if (found == null) {
                throw new IllegalStateException("Cannot find the declaration of " + type.getElementName());
            }
            candidates = found.bodyDeclarations();
        }
        return found;
    }

    /**
     * Parses the current content of the compilation unit without bindings.
     */
    private static CompilationUnit parse(ICompilationUnit cu, IProgressMonitor monitor) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        return (CompilationUnit) parser.createAST(monitor);
    }

    /**
     * Tells whether the type is annotated as a JPA entity, embeddable or mapped superclass.
     */
    private static boolean isJpaEntity(IType type) throws JavaModelException {
        for (IAnnotation annotation : type.getAnnotations()) {
            String name = annotation.getElementName();
            String simpleName = name.substring(name.lastIndexOf('.') + 1);
            if (simpleName.equals("Entity") || simpleName.equals("Embeddable") || simpleName.equals("MappedSuperclass")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tells whether generated if-statements should use blocks, as Eclipse's generate actions
     * decide it: from the project's clean-up settings for control statements.
     */
    private static boolean useBlocks(ITypeBinding typeBinding) {
        IJavaProject project = typeBinding.getJavaElement().getJavaProject();
        return CleanUpOptions.TRUE.equals(PreferenceConstants.getPreference(CleanUpConstants.CONTROL_STATEMENTS_USE_BLOCKS, project))
                && (CleanUpOptions.TRUE.equals(PreferenceConstants.getPreference(CleanUpConstants.CONTROL_STATEMENTS_USE_BLOCKS_ALWAYS, project))
                        || CleanUpOptions.TRUE.equals(PreferenceConstants.getPreference(
                                CleanUpConstants.CONTROL_STATEMENTS_USE_BLOCKS_NO_FOR_RETURN_AND_THROW, project)));
    }
}
