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

        PsiStatement dclStmt = ctx.factory().createStatementFromText(buildDclText(ctx), null);

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
        LazyInitQuickFix.deleteGuardIfStatementIfStillPresent(ctx);
    }

    /**
     * Builds the DCL null-check text for the given fix context.
     * When a guard if-statement is present and no parameter substitution is needed, the full
     * if-statement is embedded verbatim inside the innermost null-check, so multi-statement
     * then/else blocks are preserved exactly. Otherwise, the preamble and assignment are
     * reconstructed with an optional guard condition and else-branch.
     */
    private static String buildDclText(LazyInitInspection.FixContext ctx) {
        String fieldName = ctx.fieldName();
        String outerCheck = "if (" + fieldName + " == null) {\n"
                + "    synchronized (this) {\n"
                + "        if (" + fieldName + " == null) {\n";
        String outerClose = "        }\n    }\n}";

        if (ctx.guardIfStatement() != null && ctx.callSiteToRemove() == null) {
            return outerCheck + ctx.guardIfStatement().getText() + "\n" + outerClose;
        }

        String guard = ctx.guardConditionText();
        String elseRhs = ctx.guardElseRhsText();
        String innerIndent = guard != null ? "                " : "            ";
        StringBuilder preambleInner = new StringBuilder();
        for (String stmt : ctx.preambleStatements()) {
            preambleInner.append(innerIndent).append(stmt).append("\n");
        }
        preambleInner.append(innerIndent).append(fieldName).append(" = ").append(ctx.effectiveRhsText()).append(";\n");

        String innerBlock;
        if (guard != null) {
            StringBuilder ib = new StringBuilder("            if (").append(guard).append(") {\n")
                    .append(preambleInner).append("            }");
            if (elseRhs != null) {
                ib.append(" else {\n")
                  .append("                ").append(fieldName).append(" = ").append(elseRhs).append(";\n")
                  .append("            }");
            }
            innerBlock = ib.append("\n").toString();
        } else {
            innerBlock = preambleInner.toString();
        }
        return outerCheck + innerBlock + outerClose;
    }

    private static void debug(String message) {
        LOG.info(message);
    }
}
