package org.jetbrains.plugins.innerbuilder;

import com.intellij.diagnostic.PluginException;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.util.PropertyUtil;
import com.intellij.util.IncorrectOperationException;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * @author Mathias Bogaert
 */
public class GenerateInnerBuilderWorker {
    public static String BUILDER_CLASS_NAME = "Builder";
    private static final Logger log = Logger.getInstance("#org.jetbrains.plugins.innerbuilder.GenerateInnerBuilderWorker");

    private final PsiElementFactory psiElementFactory;
    private final CodeStyleManager codeStyleManager;
    private final PsiClass clazz;

    public GenerateInnerBuilderWorker(PsiClass clazz, Editor editor) {
        this.clazz = clazz;
        this.psiElementFactory = JavaPsiFacade.getInstance(clazz.getProject()).getElementFactory();
        this.codeStyleManager = CodeStyleManager.getInstance(clazz.getProject());
    }

    public void execute(Iterable<PsiField> fields) throws IncorrectOperationException {
        boolean containingClassIsAbstract = clazz.getModifierList().hasModifierProperty(PsiModifier.ABSTRACT);

        PsiClass builderClass = clazz.findInnerClassByName(BUILDER_CLASS_NAME, false);
        PsiClass superBuilderClass = findBuilderClass(clazz.getSuperClass());

        if (builderClass == null) {
            builderClass = (PsiClass) clazz.add(psiElementFactory.createClass(BUILDER_CLASS_NAME));
            if (superBuilderClass != null) {
                builderClass.getExtendsList().add(psiElementFactory.createKeyword("extends"));
                builderClass.getExtendsList().add(psiElementFactory.createReferenceExpression(superBuilderClass));
            }

            // builder classes are static
            builderClass.getModifierList().setModifierProperty(PsiModifier.STATIC, true);
            if (containingClassIsAbstract) {
                builderClass.getModifierList().setModifierProperty(PsiModifier.PROTECTED, true);
                builderClass.getModifierList().setModifierProperty(PsiModifier.ABSTRACT, true);
            }
        }

        // the owning class should have a private constructor accepting a builder
        StringBuilder constructorTakingBuilder = new StringBuilder();
        if (!containingClassIsAbstract) {
            constructorTakingBuilder.append("private ");
        }
        else {
            constructorTakingBuilder.append("protected ");
        }
        constructorTakingBuilder.append(clazz.getName()).append("(Builder builder) {");
        if (superBuilderClass != null) {
            constructorTakingBuilder.append("super(builder);");
        }
        for (PsiField field : fields) {
            final PsiMethod setterPrototype = PropertyUtil.generateSetterPrototype(field);
            final PsiMethod setter = clazz.findMethodBySignature(setterPrototype, true);

            if (setter == null) {
                constructorTakingBuilder.append(field.getName()).append("= builder.").append(field.getName()).append(";");
            } else {
                constructorTakingBuilder.append(setter.getName()).append("(builder.").append(field.getName()).append(");");
            }
        }
        constructorTakingBuilder.append("}");
        codeStyleManager.reformat(addOrReplaceMethod(clazz, constructorTakingBuilder.toString()));

        // final fields become constructor fields in the builder
        List<PsiField> finalFields = new ArrayList<PsiField>();
        for (PsiField field : fields) {
            if (field.hasModifierProperty(PsiModifier.FINAL)) {
                finalFields.add(field);
            }
        }

        // add all final fields to the builder
        for (PsiField field : finalFields) {
            if (!hasField((builderClass), field)) {
                PsiField builderField = psiElementFactory.createField(field.getName(), field.getType());
                builderField.getModifierList().setModifierProperty(PsiModifier.FINAL, true);
                builderClass.add(builderField);
            }
        }

        // add all non-final fields to the builder
        List<PsiField> nonFinalFields = new ArrayList<PsiField>();
        for (PsiField field : fields) {
            if (!hasField((builderClass), field) && !field.hasModifierProperty(PsiModifier.FINAL)) {
                nonFinalFields.add(field);

                PsiField builderField = psiElementFactory.createField(field.getName(), field.getType());
                builderClass.add(builderField);
            }
        }

        // builder constructor, accepting the final fields
        StringBuilder constructor = new StringBuilder();
        constructor.append("public Builder(");

        for (Iterator<PsiField> iterator = finalFields.iterator(); iterator.hasNext(); ) {
            PsiField field = iterator.next();
            constructor.append(field.getTypeElement().getType().getCanonicalText()).append(" ").append(field.getName());

            if (iterator.hasNext()) {
                constructor.append(",");
            }
        }
        constructor.append(") {");
        for (PsiField field : finalFields) {
            constructor.append("this.").append(field.getName()).append("=").append(field.getName()).append(";");
        }
        constructor.append("}");
        addOrReplaceMethod(builderClass, constructor.toString());

        // copy builder constructor, accepting a clazz instance
        StringBuilder copyConstructor = new StringBuilder();
        copyConstructor.append("public Builder(").append(clazz.getName()).append(" copy) {");
        for (PsiField field : finalFields) {
            copyConstructor.append(field.getName()).append("= copy.").append(field.getName()).append(";");
        }
        for (PsiField field : nonFinalFields) {
            copyConstructor.append(field.getName()).append("= copy.").append(field.getName()).append(";");
        }
        copyConstructor.append("}");
        addOrReplaceMethod(builderClass, copyConstructor.toString());

        // builder methods
        for (PsiField field : nonFinalFields) {
            String setMethodText = "public "
                    + "Builder" + " " + field.getName() + "(" + field.getTypeElement().getType().getCanonicalText()
                    + " " + field.getName() + "){"
                    + "this." + field.getName() + "=" + field.getName() + ";"
                    + "return this;"
                    + "}";
            addOrReplaceMethod(builderClass, setMethodText);
        }

        if (!containingClassIsAbstract) {
            // builder.build() method
            StringBuilder buildMethod = new StringBuilder("public ")
                    .append(clazz.getName())
                    .append(" build() { return new ")
                    .append(clazz.getName())
                    .append("(this);}");

            addOrReplaceMethod(builderClass, buildMethod.toString());
        }

        codeStyleManager.reformat(builderClass);
    }

    protected PsiClass findBuilderClass(PsiClass clazz) {
        if (clazz == null) return null;
        PsiClass builderClass = clazz.findInnerClassByName(BUILDER_CLASS_NAME, false);
        if (builderClass == null) {
            return findBuilderClass(clazz.getSuperClass());
        }
        return builderClass;
    }

    protected boolean hasField(PsiClass clazz, PsiField field) {
        return (clazz.findFieldByName(field.getName(), false) != null);
    }

    protected PsiMethod addOrReplaceMethod(PsiClass target, String methodText) {
        PsiMethod newMethod = psiElementFactory.createMethodFromText(methodText, null);
        PsiMethod existingMethod = target.findMethodBySignature(newMethod, false);

        if (existingMethod != null) {
            existingMethod.replace(newMethod);
        } else {
            target.add(newMethod);
        }
        return newMethod;
    }

    /**
     * Generates the builder code for the specified class and selected
     * fields, doing the work through a WriteAction ran by a CommandProcessor.
     */
    public static void executeGenerateActionLater(final PsiClass clazz,
                                                  final Editor editor,
                                                  final Iterable<PsiField> selectedFields) {
        Runnable writeCommand = new Runnable() {
            public void run() {
                ApplicationManager.getApplication().runWriteAction(new Runnable() {
                    public void run() {
                        try {
                            new GenerateInnerBuilderWorker(clazz, editor).execute(selectedFields);
                        } catch (Exception e) {
                            handleExeption(clazz.getProject(), e);
                        }
                    }
                });
            }
        };

        CommandProcessor.getInstance().executeCommand(clazz.getProject(), writeCommand, "GenerateBuilder", null);
    }

    /**
     * Handles any exception during the executing on this plugin.
     *
     * @param project PSI project
     * @param e       the caused exception.
     * @throws RuntimeException is thrown for severe exceptions
     */
    public static void handleExeption(Project project, Exception e) throws RuntimeException {
        log.info(e);

        if (e instanceof PluginException) {
            // plugin related error - could be recoverable.
            Messages.showMessageDialog(project, "A PluginException was thrown while performing the action - see IDEA log for details (stacktrace should be in idea.log):\n" + e.getMessage(), "Warning", Messages.getWarningIcon());
        } else if (e instanceof RuntimeException) {
            // unknown error (such as NPE) - not recoverable
            Messages.showMessageDialog(project, "An unrecoverable exception was thrown while performing the action - see IDEA log for details (stacktrace should be in idea.log):\n" + e.getMessage(), "Error", Messages.getErrorIcon());
            throw (RuntimeException) e; // throw to make IDEA alert user
        } else {
            // unknown error (such as NPE) - not recoverable
            Messages.showMessageDialog(project, "An unrecoverable exception was thrown while performing the action - see IDEA log for details (stacktrace should be in idea.log):\n" + e.getMessage(), "Error", Messages.getErrorIcon());
            throw new RuntimeException(e); // rethrow as runtime to make IDEA alert user
        }
    }
}
