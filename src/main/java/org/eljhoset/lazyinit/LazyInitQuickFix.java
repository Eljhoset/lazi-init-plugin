package org.eljhoset.lazyinit;

import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
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

        // When a guard if-statement is present (with or without else), copy it verbatim into the
        // null-check so that all surrounding statements (side effect calls, multi-statement
        // else-blocks, etc.) are preserved exactly as written.  For the no-guard case use the
        // traditional preamble + assignment reconstruction.
        String nullCheckText = ctx.guardIfStatement() != null && ctx.callSiteToRemove() == null
                ? "if (" + ctx.fieldName() + " == null) {\n" + ctx.guardIfStatement().getText() + "\n}"
                : buildNullCheckText(ctx.fieldName(), ctx.preambleStatements(), ctx.effectiveRhsText(),
                        ctx.guardConditionText(), ctx.guardElseRhsText());

        PsiStatement ifStmt = ctx.factory().createStatementFromText(nullCheckText, null);

        PsiCodeBlock getterBody = ctx.getter().getBody();
        if (getterBody == null) {
            debug("Aborting lazy-init quick fix because getter body is missing for '" + ctx.getter().getName() + "'");
            return;
        }
        getterBody.addBefore(ifStmt, getterBody.getStatements()[0]);
        debug("Inserted lazy-init null-check into getter '" + ctx.getter().getName() + "'");

        deletePreamble(ctx.preambleToRemove());
        removeAssignmentAndCleanup(ctx.assignment(), ctx.hostMethod());
        if (ctx.hostMethod().isValid()) cleanupUnusedLocalDeclarations(ctx.hostMethod());
        deleteCallSiteIfPresent(ctx);
        deleteGuardIfStatementIfStillPresent(ctx);
    }

    static String buildNullCheckText(String fieldName, List<String> preamble, String rhsText,
                                     @Nullable String guardCondition, @Nullable String guardElseRhsText) {
        StringBuilder sb = new StringBuilder("if (").append(fieldName).append(" == null) {\n");
        String indent = guardCondition != null ? "        " : "    ";
        if (guardCondition != null) sb.append("    if (").append(guardCondition).append(") {\n");
        for (String stmt : preamble) sb.append(indent).append(stmt).append("\n");
        sb.append(indent).append(fieldName).append(" = ").append(rhsText).append(";\n");
        if (guardCondition != null) {
            sb.append("    }");
            if (guardElseRhsText != null) {
                sb.append(" else {\n");
                sb.append("        ").append(fieldName).append(" = ").append(guardElseRhsText).append(";\n");
                sb.append("    }");
            }
            sb.append("\n");
        }
        return sb.append("}").toString();
    }

    /**
     * Deletes the guard if-statement from the host method when it is still present after the
     * normal assignment-cleanup pass.  This is needed when the then-block has extra statements
     * (e.g., side effect calls) that prevent the empty-block detection from firing, but the
     * full if-statement was already copied verbatim to the getter.
     */
    static void deleteGuardIfStatementIfStillPresent(LazyInitInspection.FixContext ctx) {
        PsiIfStatement guardIf = ctx.guardIfStatement();
        if (guardIf == null || !guardIf.isValid()) return;
        PsiElement prev = guardIf.getPrevSibling();
        if (prev instanceof PsiWhiteSpace) prev.delete();
        guardIf.delete();
        debug("Deleted remaining guard if-statement from host method '" + ctx.hostMethod().getName() + "'");
        if (!ctx.hostMethod().isValid()) return;
        PsiCodeBlock hostBody = ctx.hostMethod().getBody();
        if (hostBody != null && hostBody.getStatements().length == 0) {
            PsiElement prevMethod = ctx.hostMethod().getPrevSibling();
            if (prevMethod instanceof PsiWhiteSpace) prevMethod.delete();
            ctx.hostMethod().delete();
        }
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
        // Capture the containing block and any enclosing if-guard before deleting the statement.
        PsiElement containingBlock = assignStmt.getParent();
        PsiIfStatement guardIf = (containingBlock instanceof PsiCodeBlock cb)
                ? LazyInitInspection.resolveEnclosingIfStatement(cb.getParent()) : null;
        boolean hasElse = guardIf != null && guardIf.getElseBranch() != null;
        assignStmt.delete();
        debug("Removed original assignment from host method '" + hostMethod.getName() + "'");

        // Guard if-statements become orphaned after the assignment is removed; clean them up.
        // For if/else: delete the entire statement immediately (else-branch has already been
        // moved to the getter). For if-only: delete only when the then-block is now empty.
        if (containingBlock instanceof PsiCodeBlock cb && guardIf != null && (hasElse || cb.getStatements().length == 0)) {
            PsiElement prev = guardIf.getPrevSibling();
            if (prev instanceof PsiWhiteSpace) prev.delete();
            guardIf.delete();
            debug("Deleted if-guard from host method '" + hostMethod.getName() + "'");
        }

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

    /**
     * Removes declaration statements in {@code hostMethod} for locals that are no longer
     * referenced anywhere in the body, then deletes the method itself if it becomes empty.
     * Called after applying a fix in the multi-assignment shared-local pattern, where the
     * declaration is kept in init until all assignments have been converted.
     */
    static void cleanupUnusedLocalDeclarations(PsiMethod hostMethod) {
        PsiCodeBlock hostBody = hostMethod.getBody();
        if (hostBody == null) return;

        // Iterate until stable — one deletion may expose another unused declaration.
        boolean deletedAny = true;
        while (deletedAny) {
            deletedAny = false;
            for (PsiStatement stmt : hostBody.getStatements()) {
                if (stmt instanceof PsiDeclarationStatement decl
                        && Arrays.stream(decl.getDeclaredElements())
                                .filter(PsiLocalVariable.class::isInstance)
                                .map(PsiLocalVariable.class::cast)
                                .allMatch(lv -> isUnreferencedInBody(lv, hostBody))) {
                    stmt.delete();
                    deletedAny = true;
                    break; // hostBody.getStatements() is now stale; restart
                }
            }
        }

        if (hostBody.getStatements().length == 0) {
            PsiElement prev = hostMethod.getPrevSibling();
            if (prev instanceof PsiWhiteSpace) prev.delete();
            hostMethod.delete();
        }
    }

    private static boolean isUnreferencedInBody(PsiLocalVariable lv, PsiCodeBlock body) {
        return Arrays.stream(body.getStatements())
                .noneMatch(stmt -> LazyInitInspection.containsReferenceToLocal(stmt, lv));
    }

    private static void debug(String message) {
        LOG.info(message);
    }
}
