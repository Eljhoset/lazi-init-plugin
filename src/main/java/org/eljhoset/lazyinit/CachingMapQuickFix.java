package org.eljhoset.lazyinit;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import org.jetbrains.annotations.NotNull;

public class CachingMapQuickFix implements LocalQuickFix {
    private static final Logger LOG = Logger.getInstance(CachingMapQuickFix.class);

    // Store the name only — holding a live PsiField in a LocalQuickFix is unsafe
    // because the element may become stale if the file changes between inspection
    // and fix application.
    private final String varyingFieldName;

    public CachingMapQuickFix(@NotNull PsiField varyingField) {
        this.varyingFieldName = varyingField.getName();
    }

    @Override
    public @NotNull String getFamilyName() {
        return "Convert to caching map getter (keyed by " + varyingFieldName + ")";
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        LazyInitInspection.FixContext ctx = LazyInitInspection.FixContext.from(descriptor);
        if (ctx == null) {
            debug("Skipping caching-map fix because the fix context could not be resolved");
            return;
        }

        PsiField varyingField = ctx.varyingField();
        if (varyingField == null) {
            debug("Skipping caching-map fix: no varying field resolved for '" + ctx.fieldName() + "'");
            return;
        }

        PsiClass cls = ctx.field().getContainingClass();
        if (cls == null) {
            debug("Skipping caching-map fix: containing class not found for field '" + ctx.fieldName() + "'");
            return;
        }

        String keyTypeName   = boxedTypeName(varyingField.getType(), ctx.field());
        String valueTypeName = boxedTypeName(ctx.field().getType(), ctx.field());
        String cacheName     = ctx.fieldName() + "Cache";

        debug("Applying caching-map fix for field '" + ctx.fieldName()
                + "' keyed by '" + varyingFieldName
                + "' Map<" + keyTypeName + ", " + valueTypeName + ">"
                + " at " + LazyInitInspection.describeElement(ctx.assignment()));

        // 1. Replace the original field declaration with the Map cache field
        PsiField cacheField = ctx.factory().createFieldFromText(
                "private final java.util.Map<" + keyTypeName + ", " + valueTypeName + "> "
                        + cacheName + " = new java.util.HashMap<>();",
                cls);
        PsiElement insertedField = ctx.field().replace(cacheField);
        JavaCodeStyleManager.getInstance(project).shortenClassReferences(insertedField);

        // 2. Build and replace the getter body — the return statement changes, so a full
        //    body replacement is simpler and safer than inserting before the old return.
        String newBodyText = buildGetterBodyText(cacheName, varyingFieldName,
                ctx.preambleStatements(), ctx.effectiveRhsText());
        PsiCodeBlock newBody = ctx.factory().createCodeBlockFromText(newBodyText, null);

        PsiCodeBlock getterBody = ctx.getter().getBody();
        if (getterBody == null) {
            debug("Aborting caching-map fix: getter body is missing for '" + ctx.getter().getName() + "'");
            return;
        }
        getterBody.replace(newBody);
        debug("Replaced getter body for '" + ctx.getter().getName() + "' with caching-map pattern");

        // 3. Clean up preamble, original assignment, and (possibly empty) host method
        LazyInitQuickFix.deletePreamble(ctx.preambleToRemove());
        LazyInitQuickFix.removeAssignmentAndCleanup(ctx.assignment(), ctx.hostMethod());
        if (ctx.hostMethod().isValid()) LazyInitQuickFix.cleanupUnusedLocalDeclarations(ctx.hostMethod());
        LazyInitQuickFix.deleteCallSiteIfPresent(ctx);
    }

    static String buildGetterBodyText(String cacheName, String varyingFieldName,
                                      java.util.List<String> preamble, String rhsText) {
        StringBuilder sb = new StringBuilder("{\n");
        sb.append("    if (!").append(cacheName).append(".containsKey(").append(varyingFieldName).append(")) {\n");
        for (String stmt : preamble) {
            sb.append("        ").append(stmt).append("\n");
        }
        sb.append("        ").append(cacheName).append(".put(")
                .append(varyingFieldName).append(", ").append(rhsText).append(");\n");
        sb.append("    }\n");
        sb.append("    return ").append(cacheName).append(".get(").append(varyingFieldName).append(");\n");
        sb.append("}");
        return sb.toString();
    }

    private static String boxedTypeName(PsiType type, PsiElement context) {
        if (type instanceof PsiPrimitiveType pt) {
            // PsiPrimitiveType.getBoxedType may return null in some SDK configurations;
            // fall back to a hardcoded mapping so Map<K,V> type parameters are always valid.
            PsiClassType boxed = pt.getBoxedType(context);
            if (boxed != null) return boxed.getCanonicalText();
            return switch (pt.getCanonicalText()) {
                case "int"     -> "java.lang.Integer";
                case "long"    -> "java.lang.Long";
                case "double"  -> "java.lang.Double";
                case "float"   -> "java.lang.Float";
                case "boolean" -> "java.lang.Boolean";
                case "byte"    -> "java.lang.Byte";
                case "short"   -> "java.lang.Short";
                case "char"    -> "java.lang.Character";
                default        -> pt.getCanonicalText();
            };
        }
        return type.getCanonicalText();
    }

    private static void debug(String message) {
        LOG.info(message);
    }
}
