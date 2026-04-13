package org.eljhoset.lazyinit;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Converts a selector-method and null-case-method pair into a single three-branch caching getter.
 *
 * <p>Triggered when the inspection detects:
 * <ol>
 *   <li>A <em>selector method</em> that (a) assigns a "varying" instance field and (b)
 *       subsequently assigns the target field using an expression that depends on that
 *       varying field.</li>
 *   <li>A complementary <em>null-case method</em> that assigns the same target field with
 *       a constant (parameter-free, varying-field-free) expression.</li>
 * </ol>
 *
 * <p>The generated getter has the form:
 * <pre>
 *   if (varyingField == null) {
 *       targetField = &lt;nullCaseRhs&gt;;
 *   } else if (!targetFieldCache.containsKey(&lt;keyExpr&gt;)) {
 *       ValueType value = &lt;selectorRhs&gt;;
 *       targetFieldCache.put(&lt;keyExpr&gt;, value);
 *       targetField = value;
 *   } else {
 *       targetField = targetFieldCache.get(&lt;keyExpr&gt;);
 *   }
 *   return targetField;
 * </pre>
 */
public class SelectorLazyGetterQuickFix implements LocalQuickFix {
    private static final Logger LOG = Logger.getInstance(SelectorLazyGetterQuickFix.class);

    private final String fieldName;
    private final String varyingFieldName;
    private final String selectorMethodName;
    private final String nullCaseMethodName;
    private final String keyExprText;
    private final String keyTypeName;

    public SelectorLazyGetterQuickFix(@NotNull String fieldName, @NotNull String varyingFieldName,
                                       @NotNull String selectorMethodName, @NotNull String nullCaseMethodName,
                                       @NotNull String keyExprText, @NotNull String keyTypeName) {
        this.fieldName = fieldName;
        this.varyingFieldName = varyingFieldName;
        this.selectorMethodName = selectorMethodName;
        this.nullCaseMethodName = nullCaseMethodName;
        this.keyExprText = keyExprText;
        this.keyTypeName = keyTypeName;
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Convert to selector-based lazy getter (keyed by " + keyExprText + ")";
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        PsiClass cls = com.intellij.psi.util.PsiTreeUtil.getParentOfType(descriptor.getPsiElement(), PsiClass.class);
        if (cls == null) return;

        PsiField field = cls.findFieldByName(fieldName, false);
        if (field == null) {
            debug("Skipping: field '" + fieldName + "' not found in class");
            return;
        }

        PsiMethod getter = LazyInitInspection.findSimpleGetter(field);
        if (getter == null) {
            debug("Skipping: no simple getter for field '" + fieldName + "'");
            return;
        }

        PsiMethod selectorMethod = findMethodByName(cls, selectorMethodName);
        if (selectorMethod == null) {
            debug("Skipping: selector method '" + selectorMethodName + "' not found");
            return;
        }
        PsiAssignmentExpression selectorAssignment = LazyInitInspection.findFieldAssignmentInMethod(selectorMethod, field);
        if (selectorAssignment == null) {
            debug("Skipping: no assignment to '" + fieldName + "' in selector method '" + selectorMethodName + "'");
            return;
        }
        PsiExpression selectorRhs = selectorAssignment.getRExpression();
        if (selectorRhs == null) return;

        PsiMethod nullCaseMethod = findMethodByName(cls, nullCaseMethodName);
        if (nullCaseMethod == null) {
            debug("Skipping: null-case method '" + nullCaseMethodName + "' not found");
            return;
        }
        PsiAssignmentExpression nullCaseAssignment = LazyInitInspection.findFieldAssignmentInMethod(nullCaseMethod, field);
        if (nullCaseAssignment == null) {
            debug("Skipping: no assignment to '" + fieldName + "' in null-case method");
            return;
        }
        PsiExpression nullCaseRhs = nullCaseAssignment.getRExpression();
        if (nullCaseRhs == null) return;

        String fieldName    = field.getName();
        String cacheName    = fieldName + "Cache";
        String valueTypeName = LazyInitInspection.boxedTypeName(field.getType(), field);

        PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);

        // 1. Add cache field before the target field
        PsiField cacheField = factory.createFieldFromText(
                "private final java.util.Map<" + keyTypeName + ", " + valueTypeName + "> "
                        + cacheName + " = new java.util.HashMap<>();",
                cls);
        PsiElement insertedField = cls.addBefore(cacheField, field);
        JavaCodeStyleManager.getInstance(project).shortenClassReferences(insertedField);
        debug("Added cache field '" + cacheName + "' to class '" + cls.getName() + "'");

        // 2. Replace getter body with three-branch caching getter
        String newBodyText = buildSelectorGetterBody(fieldName, cacheName, varyingFieldName,
                keyExprText, nullCaseRhs.getText(), selectorRhs.getText(), valueTypeName);
        PsiCodeBlock newBody = factory.createCodeBlockFromText(newBodyText, null);
        PsiCodeBlock getterBody = getter.getBody();
        if (getterBody == null) {
            debug("Skipping: getter body is null for '" + getter.getName() + "'");
            return;
        }
        getterBody.replace(newBody);
        JavaCodeStyleManager.getInstance(project).shortenClassReferences(getter);
        debug("Replaced getter body for '" + getter.getName() + "'");

        // 3. Remove the field assignment from the selector method
        LazyInitQuickFix.removeAssignmentAndCleanup(selectorAssignment, selectorMethod);

        // 4. Remove the field assignment from the null-case method (deletes it if it becomes empty)
        if (nullCaseMethod.isValid()) {
            LazyInitQuickFix.removeAssignmentAndCleanup(nullCaseAssignment, nullCaseMethod);
        }
    }

    static String buildSelectorGetterBody(String fieldName, String cacheName, String varyingFieldName,
                                           String keyExpr, String nullCaseRhsText,
                                           String selectorRhsText, String valueTypeName) {
        return "{\n"
                + "    if (" + varyingFieldName + " == null) {\n"
                + "        " + fieldName + " = " + nullCaseRhsText + ";\n"
                + "    } else if (!" + cacheName + ".containsKey(" + keyExpr + ")) {\n"
                + "        " + valueTypeName + " fetched = " + selectorRhsText + ";\n"
                + "        " + cacheName + ".put(" + keyExpr + ", fetched);\n"
                + "        " + fieldName + " = fetched;\n"
                + "    } else {\n"
                + "        " + fieldName + " = " + cacheName + ".get(" + keyExpr + ");\n"
                + "    }\n"
                + "    return " + fieldName + ";\n"
                + "}";
    }

    @Nullable
    private static PsiMethod findMethodByName(PsiClass cls, String name) {
        PsiMethod[] methods = cls.findMethodsByName(name, false);
        return methods.length > 0 ? methods[0] : null;
    }

    private static void debug(String message) {
        LOG.info(message);
    }
}
