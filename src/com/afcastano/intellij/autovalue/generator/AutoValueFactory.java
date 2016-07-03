package com.afcastano.intellij.autovalue.generator;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiElementFactory;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiModifierList;
import com.intellij.psi.PsiParameter;
import com.intellij.psi.PsiStatement;
import com.intellij.psi.PsiType;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.ImmutableList;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

public class AutoValueFactory {

    private static Logger LOG = Logger.getInstance(AutoValueFactory.class);
    private static final ImmutableList<String> SUPPORTED_AUTOVALUE_LIBRARIES =
            ContainerUtil.immutableList(
                    "com.google.auto.value.AutoValue",
                    "auto.parcel.AutoParcel",
                    "auto.parcelgson.AutoParcelGson"
                    );

    private PsiType builderType;
    private PsiClass builderClass;
    private PsiType targetType;
    private PsiClass targetClass;
    private PsiElementFactory factory;
    private PsiJavaFile javaFile;
    private Project project;
    private PsiAnnotation autoValueAnnotation;
    private PsiMethod buildMehtod;

    public AutoValueFactory(Project project, Editor editor, PsiFile file) {
        this.project = project;

        if (file instanceof PsiJavaFile) {
            this.javaFile = (PsiJavaFile) file;
        } else {
            LOG.debug("Not a java file");
            throw new RuntimeException("Not a java file");
        }

        if (editor == null || javaFile == null) {
            throw new RuntimeException("No class selected");
        }

        this.targetClass = loadTargetClass(editor, javaFile);

        if (targetClass == null) {
            throw new RuntimeException("Target class not found");
        }

        this.autoValueAnnotation = findAutoValueAnnotationClass();

        if (autoValueAnnotation == null) {
            throw new RuntimeException("No auto value annotation detected");
        }

        factory = JavaPsiFacade.getElementFactory(project);
    }

    public PsiClass getTargetClass() {
        return targetClass;
    }

    public Project getProject() {
        return project;
    }

    public PsiType getTargetType() {
        if(targetType == null) {
            targetType = factory.createType(targetClass);
        }

        return targetType;
    }

    public PsiClass getBuilderClass() {

        if(builderClass == null) {
            PsiClass existingBuilder = findExistingBuilderClass(targetClass);

            if (existingBuilder != null) {
                builderClass = existingBuilder;

            } else {

                final PsiClass newBuilderClass = factory.createClass("Builder");
                PsiModifierList modifierList = newBuilderClass.getModifierList();
                modifierList.setModifierProperty("public", true);
                modifierList.setModifierProperty("abstract", true);
                modifierList.setModifierProperty("static", true);
                modifierList.addAnnotation(autoValueAnnotation.getQualifiedName() + ".Builder");

                this.builderClass = newBuilderClass;

            }

        }

        return builderClass;
    }

    public PsiMethod newBuilderSetter(PsiMethod getterMethod) {
        String name = getterMethod.getName();
        String parameterName = name;

        if (name.startsWith("get") && name.length() > 3) {
            if (Character.isUpperCase(name.charAt(3))) {
                name = name.substring(3);
                name = name.substring(0, 1).toLowerCase() + name.substring(1);
                parameterName = name;
            }
        } else if (name.startsWith("is") && name.length() > 2) {
            if (Character.isUpperCase(name.charAt(2))) {
                name = name.substring(2);
                name = name.substring(0, 1).toLowerCase() + name.substring(1);
                parameterName = name;
            }
        }
        final PsiMethod method = factory.createMethod(name, getBuilderType());

        PsiParameter parameter = factory.createParameter(parameterName, getterMethod.getReturnType());
        method.getParameterList().add(parameter);
        method.getBody().delete();
        method.getModifierList().setModifierProperty("public", true);
        method.getModifierList().setModifierProperty("abstract", true);
        return method;
    }

    public PsiMethod getBuildMethod() {
        PsiMethod[] buildMethods = builderClass.findMethodsByName("build", false);

        if (buildMethods.length > 0) {
            this.buildMehtod = buildMethods[0];

        } else {
            final PsiMethod newBuildMethod = factory.createMethod("build", getTargetType());
            newBuildMethod.getBody().delete();
            newBuildMethod.getModifierList().setModifierProperty("public", true);
            newBuildMethod.getModifierList().setModifierProperty("abstract", true);
            this.buildMehtod = newBuildMethod;

        }

        return this.buildMehtod;

    }

    public PsiMethod newBuilderFactoryMethod() {
        final PsiMethod builderMethod = factory.createMethod("builder", getBuilderType());
        builderMethod.getModifierList().setModifierProperty("public", true);
        builderMethod.getModifierList().setModifierProperty("static", true);

        String generatedName = "";

        for(PsiClass parent: findAllParents(getTargetClass())) {
            generatedName = generatedName + parent.getName() + "_";
        }

        generatedName = generatedName + getTargetClass().getName();

        String autoValueAnnotationName = StringUtil.getShortName(autoValueAnnotation.getQualifiedName());
        String returnStatementText = "return new " + autoValueAnnotationName + "_" + generatedName + ".Builder();";

        PsiStatement returnStatement = factory.createStatementFromText(returnStatementText, getTargetClass());

        builderMethod.getBody().add(returnStatement);
        return builderMethod;
    }

    public PsiType getBuilderType() {

        if (builderType == null) {
            this.builderType = factory.createType(getBuilderClass());
        }

        return builderType;
    }

    public boolean containsBuilderClass() {
        return findExistingBuilderClass(getTargetClass()) != null;
    }

    public boolean isBuilderUpToDate() {
        PsiClass builderClass = getBuilderClass();
        if (builderClass == null) {
            return false;
        }
        for (PsiMethod psiMethod : targetClass.getMethods()) {
            if (isAbstractGetter(psiMethod) && !alreadyInBuilder(builderClass, psiMethod)) {
                return false;
            }
        }
        for (PsiMethod psiMethod : builderClass.getMethods()) {
            if (isBuilderSetter(psiMethod) && !alreadyInClass(targetClass, psiMethod)) {
                return false;
            }
        }

        if(!containsBuilderFactoryMethod(getTargetClass())) {
            return false;
        }

        return true;
    }

    private boolean containsBuilderFactoryMethod(PsiClass targetClass) {
        return targetClass.findMethodsByName("builder", true).length != 0;
    }

    @Nullable
    private PsiAnnotation findAutoValueAnnotationClass() {
        for (String autoValueAnnotationName : SUPPORTED_AUTOVALUE_LIBRARIES) {
            PsiAnnotation autoValueAnnotation = getTargetClass().getModifierList().findAnnotation(autoValueAnnotationName);
            if (autoValueAnnotation != null) {
                return autoValueAnnotation;
            }
        }

        return null;
    }

    private PsiClass loadTargetClass(Editor editor, PsiJavaFile javaFile) {
        int caretOffset = editor.getCaretModel().getOffset();
        PsiElement element = javaFile.findElementAt(caretOffset);

        return PsiTreeUtil.getParentOfType(element, PsiClass.class);
    }

    @Nullable
    private PsiClass findExistingBuilderClass(PsiClass targetClass) {
        for ( PsiClass clz: targetClass.getInnerClasses() ) {
            if(isBuilderClass(clz)) {
                return clz;
            }
        }
        return null;
    }

    private boolean isBuilderClass(PsiClass clz) {
        PsiAnnotation[] annotations = clz.getModifierList().getAnnotations();

        for (PsiAnnotation annotation: annotations) {
            if (annotation.getQualifiedName().contains("Builder")) {
                return true;
            }
        }

        return false;
    }

    private List<PsiClass> findAllParents(PsiClass childClass) {
        List<PsiClass> parents = new ArrayList<>();
        PsiClass parent = PsiTreeUtil.getParentOfType(childClass, PsiClass.class);

        if(parent == null) {
            return parents;
        }

        parents.addAll(findAllParents(parent));
        parents.add(parent);
        return parents;
    }

    public boolean alreadyInBuilder(PsiClass builderClass, PsiMethod psiMethod) {
        for (PsiMethod method : builderClass.getMethods()) {
            if (isBuilderSetter(method) && method.getName().equals(psiMethod.getName())) {
                return true;
            }
        }
        return false;
    }

    public boolean alreadyInClass(PsiClass targetClass, PsiMethod psiMethod) {
        for (PsiMethod method : targetClass.getMethods()) {
            if (isAbstractGetter(method) && method.getName().equals(psiMethod.getName())) {
                return true;
            }
        }
        return false;
    }

    public boolean isAbstractGetter(PsiMethod psiMethod) {
        boolean isAbstract = psiMethod.getModifierList().hasExplicitModifier("abstract");
        boolean noParameters = psiMethod.getParameterList().getParametersCount() == 0;
        boolean returnsSomething = !psiMethod.getReturnType().equals(PsiType.VOID);
        boolean noBody = psiMethod.getBody() == null;
        return isAbstract && noParameters && returnsSomething && noBody;
    }

    public boolean isBuilderSetter(PsiMethod psiMethod) {
        String methodReturnName = psiMethod.getReturnType().getPresentableText();
        return methodReturnName.equals("Builder");
    }
}
