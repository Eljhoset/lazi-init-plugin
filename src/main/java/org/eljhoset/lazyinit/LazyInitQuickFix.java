package org.eljhoset.lazyinit;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

import java.util.List;

public class LazyInitQuickFix implements LocalQuickFix {
    private static final Logger LOG = Logger.getInstance(LazyInitQuickFix.class);

    @Override
    public @NotNull String getFamilyName() {
        return "Convert to lazy initialization in getter";
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        LazyInitInspection.FixContext ctx = LazyInitInspection.FixContext.from(descriptor);
        if (ctx == null) {
            debug("Skipping lazy-init quick fix because the fix context could not be resolved");
            return;
        }

        debug("Applying lazy-init quick fix for field '" + ctx.fieldName() + "' at "
                + LazyInitInspection.describeElement(ctx.assignment()));

        PsiStatement ifStmt = ctx.factory().createStatementFromText(
                buildNullCheckText(ctx.fieldName(), ctx.preambleStatements(), ctx.effectiveRhsText()),
                null);

        PsiCodeBlock getterBody = ctx.getter().getBody();
        if (getterBody == null) {
            debug("Aborting lazy-init quick fix because getter body is missing for '" + ctx.getter().getName() + "'");
            return;
        }
        getterBody.addBefore(ifStmt, getterBody.getStatements()[0]);
        debug("Inserted lazy-init null-check into getter '" + ctx.getter().getName() + "'");

        deletePreamble(ctx.preambleToRemove());
        removeAssignmentAndCleanup(ctx.assignment(), ctx.hostMethod());
        deleteCallSiteIfPresent(ctx);
    }

    static String buildNullCheckText(String fieldName, List<String> preamble, String rhsText) {
        StringBuilder sb = new StringBuilder("if (").append(fieldName).append(" == null) {\n");
        for (String stmt : preamble) {
            sb.append("    ").append(stmt).append("\n");
        }
        sb.append("    ").append(fieldName).append(" = ").append(rhsText).append(";\n}");
        return sb.toString();
    }

    static void deleteCallSiteIfPresent(LazyInitInspection.FixContext ctx) {
        if (ctx.callSiteToRemove() != null) {
            ctx.callSiteToRemove().delete();
            LazyInitInspection.debug("Removed substituted call site for host method '" + ctx.hostMethod().getName() + "'");
        }
    }

    static void deletePreamble(List<PsiStatement> preambleToRemove) {
        for (PsiStatement stmt : preambleToRemove) {
            stmt.delete();
        }
    }

    static void removeAssignmentAndCleanup(PsiAssignmentExpression assignment, PsiMethod hostMethod) {
        PsiElement assignStmt = assignment.getParent(); // PsiExpressionStatement
        assignStmt.delete();
        debug("Removed original assignment from host method '" + hostMethod.getName() + "'");

        PsiCodeBlock hostBody = hostMethod.getBody();
        if (hostBody != null && hostBody.getStatements().length == 0) {
            // Delete preceding whitespace before the method, then the method itself.
            // Reversing the order invalidates the whitespace node's PSI context.
            PsiElement prev = hostMethod.getPrevSibling();
            if (prev instanceof PsiWhiteSpace) {
                prev.delete();
            }
            hostMethod.delete();
            debug("Deleted now-empty host method '" + hostMethod.getName() + "'");
        }
    }

    private static void debug(String message) {
        LOG.info(message);
    }
}
