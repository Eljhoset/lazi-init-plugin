package org.eljhoset.lazyinit;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

public class DoubleCheckedLockingQuickFix implements LocalQuickFix {
    private static final Logger LOG = Logger.getInstance(DoubleCheckedLockingQuickFix.class);

    @Override
    public @NotNull String getFamilyName() {
        return "Convert to thread-safe lazy initialization (double-checked locking)";
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        LazyInitInspection.FixContext ctx = LazyInitInspection.FixContext.from(descriptor);
        if (ctx == null) {
            debug("Skipping DCL quick fix because the fix context could not be resolved");
            return;
        }

        debug("Applying DCL quick fix for field '" + ctx.fieldName() + "' at "
                + LazyInitInspection.describeElement(ctx.assignment()));

        // Mark field volatile so the DCL pattern is safe under the Java Memory Model
        PsiModifierList modifiers = ctx.field().getModifierList();
        if (modifiers != null && !modifiers.hasModifierProperty(PsiModifier.VOLATILE)) {
            modifiers.setModifierProperty(PsiModifier.VOLATILE, true);
            debug("Marked field '" + ctx.fieldName() + "' as volatile");
        }

        StringBuilder preambleInner = new StringBuilder();
        for (String stmt : ctx.preambleStatements()) {
            preambleInner.append("            ").append(stmt).append("\n");
        }

        PsiStatement dclStmt = ctx.factory().createStatementFromText(
                "if (" + ctx.fieldName() + " == null) {\n"
                        + "    synchronized (this) {\n"
                        + "        if (" + ctx.fieldName() + " == null) {\n"
                        + preambleInner
                        + "            " + ctx.fieldName() + " = " + ctx.effectiveRhsText() + ";\n"
                        + "        }\n"
                        + "    }\n"
                        + "}",
                null);

        PsiCodeBlock getterBody = ctx.getter().getBody();
        if (getterBody == null) {
            debug("Aborting DCL quick fix because getter body is missing for '" + ctx.getter().getName() + "'");
            return;
        }
        getterBody.addBefore(dclStmt, getterBody.getStatements()[0]);
        debug("Inserted DCL null-check into getter '" + ctx.getter().getName() + "'");

        LazyInitQuickFix.deletePreamble(ctx.preambleToRemove());
        LazyInitQuickFix.removeAssignmentAndCleanup(ctx.assignment(), ctx.hostMethod());
        if (ctx.hostMethod().isValid()) LazyInitQuickFix.cleanupUnusedLocalDeclarations(ctx.hostMethod());
        LazyInitQuickFix.deleteCallSiteIfPresent(ctx);
    }

    private static void debug(String message) {
        LOG.info(message);
    }
}
