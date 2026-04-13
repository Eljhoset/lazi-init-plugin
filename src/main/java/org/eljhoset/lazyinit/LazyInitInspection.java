package org.eljhoset.lazyinit;

import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.psi.*;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

public class LazyInitInspection extends AbstractBaseJavaLocalInspectionTool {
    private static final Logger LOG = Logger.getInstance(LazyInitInspection.class);
    private static final String IN_METHOD = "' in method '";

    /** Key expression and its type name for selector-based caching getters. */
    record KeyExprInfo(String keyExprText, String keyTypeName) {}

    /** Resolved getter method and whether it already contains the lazy-init null-check. */
    private record GetterInfo(PsiMethod getter, boolean alreadyLazy) {}

    /** Identifies the host method and any enclosing if-guard for an assignment. */
    record HostContext(@NotNull PsiMethod method, @Nullable PsiIfStatement guardIfStatement) {
        @Nullable String guardConditionText() {
            PsiExpression cond = guardIfStatement == null ? null : guardIfStatement.getCondition();
            return cond == null ? null : cond.getText();
        }
    }

    @Override
    public @NotNull PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitAssignmentExpression(@NotNull PsiAssignmentExpression expression) {
                super.visitAssignmentExpression(expression);
                checkAssignment(expression, holder);
            }
        };
    }

    private static void checkAssignment(PsiAssignmentExpression expression, ProblemsHolder holder) {
        PsiField field = resolveToField(expression.getLExpression());
        if (field == null) { debugSkip(expression, "left-hand side does not resolve to a field"); return; }
        if (!isEligibleField(field, expression)) return;

        HostContext hostCtx = getHostContext(expression);
        if (hostCtx == null) { debugSkip(expression, "assignment is not in an eligible position"); return; }
        PsiMethod hostMethod = hostCtx.method();
        if (hostMethod.isConstructor()) { debugSkip(expression, "host method '" + hostMethod.getName() + "' is a constructor"); return; }
        // Try simple getter first; fall back to lazy getter (getter already has null-check).
        GetterInfo getterInfo = resolveGetterInfo(field);
        if (getterInfo == null) {
            debugSkip(expression, "no simple or lazy getter found for field '" + field.getName() + "'");
            return;
        }
        PsiMethod getter = getterInfo.getter();
        boolean getterAlreadyLazy = getterInfo.alreadyLazy();

        // When the getter already owns the lazy initialisation, the fix only removes the
        // assignment (and its preamble) from the host method — nothing is inserted into the
        // getter.  Guard/param/varying-field validation is not needed in this path.
        if (getterAlreadyLazy) {
            Collection<PsiReference> lazyHostRefs = ReferencesSearch.search(hostMethod).findAll();
            debug("Registering lazy-init removal for field '" + field.getName() + IN_METHOD
                    + hostMethod.getName() + "' at " + describeElement(expression)
                    + " (getter already has lazy init), callSites=" + lazyHostRefs.size());
            holder.registerProblem(expression,
                    buildMessage(field, hostMethod.getName(), lazyHostRefs.size()),
                    new LazyInitQuickFix());
            return;
        }

        if (!isGuardEligibleForMoving(hostCtx, field, hostMethod, expression)) return;

        PsiExpression rhs = expression.getRExpression();
        if (rhs == null) { debugSkip(expression, "assignment has no right-hand side"); return; }

        RhsProfile profile = RhsProfile.of(rhs, hostMethod);
        if (!isEligibleRhsProfile(profile, hostMethod, expression)) return;

        Collection<PsiReference> hostRefs = ReferencesSearch.search(hostMethod).findAll();
        if (!isParamCallSiteEligible(profile, hostRefs, expression)) return;

        List<PsiField> varyingFields = findVaryingFields(rhs, hostMethod, field);
        if (varyingFields.size() > 1) {
            debugSkip(expression, "RHS references multiple varying instance fields: "
                    + varyingFields.stream().map(PsiField::getName).toList());
            return;
        }
        PsiField varyingField = varyingFields.size() == 1 ? varyingFields.getFirst() : null;

        if (varyingField != null && checkSelectorPattern(field, varyingField, hostMethod, rhs, hostRefs, expression, holder)) {
            return;
        }

        debug("Registering lazy-init inspection for field '" + field.getName() + IN_METHOD
                + hostMethod.getName() + "' at " + describeElement(expression)
                + " with getter '" + getter.getName() + "' and callSites=" + hostRefs.size()
                + " varyingField=" + (varyingField != null ? varyingField.getName() : "none"));

        holder.registerProblem(expression,
                buildMessage(field, hostMethod.getName(), hostRefs.size()),
                buildFixes(field, varyingField));
    }

    private static boolean isGuardEligibleForMoving(HostContext hostCtx, PsiField field,
                                                      PsiMethod hostMethod,
                                                      PsiAssignmentExpression expression) {
        PsiIfStatement guard = hostCtx.guardIfStatement();
        if (guard == null) return true;
        if (!isGuardConditionMovable(guard, hostMethod)) {
            debugSkip(expression, "guard condition references local variables — cannot move to getter");
            return false;
        }
        if (guard.getElseBranch() != null && !isValidElseBranch(guard, field, hostMethod)) {
            debugSkip(expression, "else-branch is not a simple field assignment eligible for lazy init");
            return false;
        }
        return true;
    }

    private static boolean isEligibleField(PsiField field, PsiAssignmentExpression expression) {
        if (field.hasModifierProperty(PsiModifier.FINAL)) {
            debugSkip(expression, "field '" + field.getName() + "' is final");
            return false;
        }
        if (field.getInitializer() != null) {
            debugSkip(expression, "field '" + field.getName() + "' already has an initializer");
            return false;
        }
        return true;
    }

    private static boolean isEligibleRhsProfile(RhsProfile profile, PsiMethod hostMethod,
                                                 PsiAssignmentExpression expression) {
        if (profile.hasParam() && profile.hasLocal()) {
            debugSkip(expression, "right-hand side mixes parameters and locals");
            return false;
        }
        // A bare parameter assignment (field = param with no computation) inside a setter-named
        // method (setXxx) is a setter pattern, not a lazy-init candidate — skip it.
        if (profile.isBareParam() && isSetterMethod(hostMethod)) {
            debugSkip(expression, "right-hand side is a bare parameter reference in a setter method");
            return false;
        }
        if (profile.hasLocal() && !allLocalsAreInlinable(profile.locals(), hostMethod, expression)) {
            debugSkip(expression, "right-hand side depends on non-inlinable locals " + namesOf(profile.locals()));
            return false;
        }
        return true;
    }

    private static boolean isParamCallSiteEligible(RhsProfile profile, Collection<PsiReference> hostRefs,
                                                    PsiAssignmentExpression expression) {
        if (!profile.hasParam()) return true;
        if (hostRefs.size() != 1) {
            debugSkip(expression, "parameter-based rewrite requires exactly one call site, found " + hostRefs.size());
            return false;
        }
        PsiMethodCallExpression callSite = PsiTreeUtil.getParentOfType(
                hostRefs.iterator().next().getElement(), PsiMethodCallExpression.class);
        if (hasUnsafeLocalArgument(callSite)) {
            debugSkip(expression, "single call site passes an argument that depends on caller-local state");
            return false;
        }
        if (isCallSiteInConditional(callSite)) {
            debugSkip(expression, "single call site is inside a conditional — the field may never be initialized");
            return false;
        }
        return true;
    }

    /**
     * Classifies all reference expressions in an RHS expression in a single PSI walk,
     * avoiding separate repeated traversals for params, locals, and local collection.
     */
    private record RhsProfile(boolean hasParam, boolean hasLocal, List<PsiLocalVariable> locals,
                               boolean isBareParam) {
        static RhsProfile of(PsiExpression rhs, PsiMethod scope) {
            boolean hasParam = false;
            boolean hasLocal = false;
            List<PsiLocalVariable> locals = new ArrayList<>();
            Set<PsiLocalVariable> seen = new HashSet<>();
            for (PsiReferenceExpression ref : allReferenceExpressions(rhs)) {
                PsiElement resolved = ref.resolve();
                if (resolved instanceof PsiParameter
                        && PsiTreeUtil.isAncestor(scope, resolved, false)) {
                    hasParam = true;
                } else if (resolved instanceof PsiLocalVariable lv
                        && PsiTreeUtil.isAncestor(scope, resolved, false)
                        && seen.add(lv)) {
                    hasLocal = true;
                    locals.add(lv);
                }
            }
            // A bare param reference means the RHS IS the parameter with no wrapping computation.
            boolean isBareParam = hasParam && !hasLocal
                    && rhs instanceof PsiReferenceExpression ref
                    && ref.resolve() instanceof PsiParameter;
            return new RhsProfile(hasParam, hasLocal, locals, isBareParam);
        }
    }

    private static boolean allLocalsAreInlinable(List<PsiLocalVariable> locals, PsiMethod hostMethod,
                                                  PsiAssignmentExpression expression) {
        PsiStatement assignmentStmt = PsiTreeUtil.getParentOfType(expression, PsiStatement.class);
        if (assignmentStmt == null) return false;
        PsiCodeBlock effectiveBody = PsiTreeUtil.getParentOfType(assignmentStmt, PsiCodeBlock.class, true);
        if (effectiveBody == null) return false;
        List<PsiLocalVariable> chainLocals = collectBuildChainLocals(locals, effectiveBody, hostMethod);
        // All chain locals must be declared inside the effective body.
        // If a local is declared in the outer method body (and the assignment is if-wrapped), the
        // declaration cannot be copied into the getter preamble, so we block the fix.
        for (PsiLocalVariable lv : chainLocals) {
            if (!PsiTreeUtil.isAncestor(effectiveBody, lv, false)) {
                debug("Chain local '" + lv.getName() + "' not declared inside effective body — blocking fix");
                return false;
            }
        }
        return isMovableBuildChain(chainLocals, hostMethod, assignmentStmt);
    }

    /** Resolves a simple or lazy getter for {@code field}, or returns {@code null} if none exists. */
    private static @Nullable GetterInfo resolveGetterInfo(PsiField field) {
        PsiMethod getter = findSimpleGetter(field);
        if (getter != null) return new GetterInfo(getter, false);
        getter = findLazyGetter(field);
        return getter != null ? new GetterInfo(getter, true) : null;
    }

    private static LocalQuickFix[] buildFixes(PsiField field, @Nullable PsiField varyingField) {
        if (varyingField != null) {
            return new LocalQuickFix[]{new CachingMapQuickFix(varyingField)};
        }
        if (field.hasModifierProperty(PsiModifier.STATIC)) {
            return new LocalQuickFix[]{new LazyInitQuickFix()};
        }
        return new LocalQuickFix[]{new LazyInitQuickFix(), new DoubleCheckedLockingQuickFix()};
    }

    /**
     * Handles the selector-method pattern: when the varying field is assigned in the host
     * method before the target assignment, we look for a null-case companion and register a
     * {@link SelectorLazyGetterQuickFix}.
     *
     * @return {@code true} when the pattern was detected (regardless of whether a fix was
     *         offered), so the caller can skip the fallthrough to {@link #buildFixes}.
     */
    private static boolean checkSelectorPattern(PsiField field, PsiField varyingField, PsiMethod hostMethod,
                                                 PsiExpression rhs, Collection<PsiReference> hostRefs,
                                                 PsiAssignmentExpression expression, ProblemsHolder holder) {
        if (!isVaryingFieldAssignedBefore(varyingField, hostMethod, expression)) return false;
        PsiAssignmentExpression nullCase = findNullCaseAssignment(field, varyingField);
        if (nullCase == null) {
            debugSkip(expression, "selector-method pattern — no null-case companion found");
            return true;
        }
        KeyExprInfo key = extractEffectiveKey(rhs, varyingField);
        String keyExpr = key != null ? key.keyExprText() : varyingField.getName();
        String keyType = key != null ? key.keyTypeName() : boxedTypeName(varyingField.getType(), expression);
        PsiMethod nullCaseMethod = PsiTreeUtil.getParentOfType(nullCase, PsiMethod.class);
        if (nullCaseMethod == null) {
            debugSkip(expression, "selector-method pattern — could not resolve null-case method");
            return true;
        }
        debug("Registering selector lazy-getter fix for field '" + field.getName()
                + "' keyed by '" + keyExpr + "'");
        holder.registerProblem(expression,
                buildMessage(field, hostMethod.getName(), hostRefs.size()),
                new SelectorLazyGetterQuickFix(varyingField.getName(),
                        nullCaseMethod.getName(), keyExpr, keyType));
        return true;
    }

    // -----------------------------------------------------------------------
    // Selector-based lazy getter helpers
    // -----------------------------------------------------------------------

    /** Boxes a primitive type to its wrapper class name (simple name — no {@code java.lang.} prefix). */
    static String boxedTypeName(PsiType type, PsiElement context) {
        if (type instanceof PsiPrimitiveType pt) {
            PsiClassType boxed = pt.getBoxedType(context);
            if (boxed != null) {
                String canonical = boxed.getCanonicalText();
                // java.lang wrapper types don't need an import — use simple name directly
                return canonical.startsWith("java.lang.") ? canonical.substring("java.lang.".length()) : canonical;
            }
            return switch (pt.getCanonicalText()) {
                case "int"     -> "Integer";
                case "long"    -> "Long";
                case "double"  -> "Double";
                case "float"   -> "Float";
                case "boolean" -> "Boolean";
                case "byte"    -> "Byte";
                case "short"   -> "Short";
                case "char"    -> "Character";
                default        -> pt.getCanonicalText();
            };
        }
        return type.getCanonicalText();
    }

    /**
     * Returns {@code true} when {@code varyingField} is the LHS of a simple assignment
     * statement that appears before the statement containing {@code targetAssignment} in
     * {@code hostMethod}'s body.
     */
    static boolean isVaryingFieldAssignedBefore(PsiField varyingField, PsiMethod hostMethod,
                                                 PsiAssignmentExpression targetAssignment) {
        PsiCodeBlock body = hostMethod.getBody();
        if (body == null) return false;
        PsiStatement assignmentStmt = PsiTreeUtil.getParentOfType(targetAssignment, PsiStatement.class);
        if (assignmentStmt == null) return false;
        // Walk up to the direct child of body (handles if-guarded assignments)
        PsiElement topLevel = assignmentStmt;
        while (topLevel.getParent() != body) {
            PsiElement next = topLevel.getParent();
            if (next == null || next instanceof PsiMethod) return false;
            topLevel = next;
        }
        for (PsiStatement stmt : body.getStatements()) {
            if (stmt == topLevel) break;
            if (isDirectAssignmentTo(stmt, varyingField)) return true;
        }
        return false;
    }

    private static boolean isDirectAssignmentTo(PsiStatement stmt, PsiField field) {
        if (!(stmt instanceof PsiExpressionStatement exprStmt)) return false;
        PsiExpression expr = exprStmt.getExpression();
        if (!(expr instanceof PsiAssignmentExpression assign)) return false;
        return resolveToField(assign.getLExpression()) == field;
    }

    /**
     * Scans class methods for a "null-case" companion: a non-constructor, non-guarded
     * assignment to {@code target} whose RHS references neither {@code varyingField} nor
     * any method parameters nor any other varying instance field.
     */
    static @Nullable PsiAssignmentExpression findNullCaseAssignment(PsiField target, PsiField varyingField) {
        PsiClass cls = target.getContainingClass();
        if (cls == null) return null;
        for (PsiMethod method : cls.getMethods()) {
            if (method.isConstructor()) continue;
            PsiAssignmentExpression found = findNullCaseInMethod(method, target, varyingField);
            if (found != null) return found;
        }
        return null;
    }

    private static @Nullable PsiAssignmentExpression findNullCaseInMethod(PsiMethod method,
                                                                           PsiField target,
                                                                           PsiField varyingField) {
        PsiCodeBlock body = method.getBody();
        if (body == null) return null;
        for (PsiStatement stmt : body.getStatements()) {
            PsiAssignmentExpression candidate = extractNullCaseCandidate(stmt, method, target, varyingField);
            if (candidate != null) return candidate;
        }
        return null;
    }

    private static @Nullable PsiAssignmentExpression extractNullCaseCandidate(PsiStatement stmt,
                                                                                @NotNull PsiMethod method,
                                                                                @NotNull PsiField target,
                                                                                @NotNull PsiField varyingField) {
        if (!(stmt instanceof PsiExpressionStatement exprStmt)) return null;
        if (!(exprStmt.getExpression() instanceof PsiAssignmentExpression assign)) return null;
        if (resolveToField(assign.getLExpression()) != target) return null;
        PsiExpression rhs = assign.getRExpression();
        if (rhs == null) return null;
        boolean refsVarying = allReferenceExpressions(rhs).stream().anyMatch(ref -> ref.resolve() == varyingField);
        if (refsVarying || referencesParam(rhs, method)) return null;
        if (!findVaryingFields(rhs, method, target).isEmpty()) return null;
        return assign;
    }

    /**
     * Extracts the effective cache key when {@code varyingField} appears exactly once in
     * {@code rhs} as the receiver of a no-arg, non-void method call.
     * E.g. for {@code service.compute(this.entity.getId())} with {@code varyingField=entity},
     * returns {@code KeyExprInfo("this.entity.getId()", "Long")}.
     */
    static @Nullable KeyExprInfo extractEffectiveKey(PsiExpression rhs, PsiField varyingField) {
        List<PsiReferenceExpression> refs = allReferenceExpressions(rhs).stream()
                .filter(ref -> ref.resolve() == varyingField)
                .toList();
        if (refs.size() != 1) return null;
        PsiReferenceExpression varyingRef = refs.getFirst();
        PsiElement refParent = varyingRef.getParent();
        if (!(refParent instanceof PsiReferenceExpression)) return null;
        PsiElement callParent = refParent.getParent();
        if (!(callParent instanceof PsiMethodCallExpression call)) return null;
        if (call.getArgumentList().getExpressions().length != 0) return null;
        PsiElement resolved = call.getMethodExpression().resolve();
        if (!(resolved instanceof PsiMethod calledMethod)) return null;
        PsiType returnType = calledMethod.getReturnType();
        if (returnType == null || "void".equals(returnType.getCanonicalText())) return null;
        return new KeyExprInfo(call.getText(), boxedTypeName(returnType, rhs));
    }

    // -----------------------------------------------------------------------
    // PSI resolution helpers (package-visible for use by the quick-fix classes)
    // -----------------------------------------------------------------------

    static @Nullable PsiField resolveToField(PsiExpression expr) {
        if (!(expr instanceof PsiReferenceExpression ref)) return null;
        PsiElement resolved = ref.resolve();
        return resolved instanceof PsiField f ? f : null;
    }

    /**
     * Returns the host method and any enclosing if-guard for an assignment, or {@code null}
     * when the assignment is not in an eligible position.
     *
     * <p>Eligible structures:
     * <ul>
     *   <li>Direct: {@code assignment → PsiExpressionStatement → PsiCodeBlock → PsiMethod}</li>
     *   <li>If-wrapped: assignment is inside a braced if-block (no else) that is a direct
     *       statement in a method body.</li>
     * </ul>
     */
    static @Nullable HostContext getHostContext(PsiAssignmentExpression assignment) {
        PsiElement exprStmt = assignment.getParent();
        if (!(exprStmt instanceof PsiExpressionStatement)) return null;
        PsiElement block = exprStmt.getParent();
        if (!(block instanceof PsiCodeBlock)) return null;
        PsiElement blockParent = block.getParent();

        // Direct case: code block is the method body
        if (blockParent instanceof PsiMethod method) {
            // If any preceding if-statement has a return branch the assignment is conditionally
            // guarded by an early return — moving it to the getter would make it unconditional.
            if (hasEarlyReturnGuardBefore((PsiCodeBlock) block, (PsiStatement) exprStmt)) {
                return null;
            }
            return new HostContext(method, null);
        }

        // If-wrapped case: PsiCodeBlock → PsiBlockStatement → PsiIfStatement → PsiCodeBlock → PsiMethod
        PsiIfStatement ifStmt = resolveEnclosingIfStatement(blockParent);
        if (ifStmt == null) return null;
        if (!isInThenBranch((PsiCodeBlock) block, ifStmt)) return null;
        PsiElement outerBlock = ifStmt.getParent();
        if (!(outerBlock instanceof PsiCodeBlock)) return null;
        if (!(outerBlock.getParent() instanceof PsiMethod method)) return null;
        return new HostContext(method, ifStmt);
    }

    private static boolean isSetterMethod(PsiMethod method) {
        String name = method.getName();
        return name.startsWith("set") && name.length() > 3 && Character.isUpperCase(name.charAt(3));
    }

    private static boolean isInThenBranch(PsiCodeBlock block, PsiIfStatement ifStmt) {
        PsiStatement then = ifStmt.getThenBranch();
        if (then == null) return false;
        // Layout 1 (common): PsiCodeBlock wrapped in PsiBlockStatement
        if (then instanceof PsiBlockStatement bs && bs.getCodeBlock() == block) return true;
        // Layout 2 (rare): PsiCodeBlock directly as then-branch
        return then == block;
    }

    /**
     * Returns the {@link PsiIfStatement} whose then-branch directly contains {@code blockOrStatement},
     * handling both {@code PsiCodeBlock → PsiBlockStatement → PsiIfStatement} and the rarer
     * {@code PsiCodeBlock → PsiIfStatement} layouts that differ across PSI versions.
     */
    static @Nullable PsiIfStatement resolveEnclosingIfStatement(PsiElement blockOrStatement) {
        if (blockOrStatement instanceof PsiBlockStatement bs
                && bs.getParent() instanceof PsiIfStatement i) {
            return i;
        }
        if (blockOrStatement instanceof PsiIfStatement i) {
            return i;
        }
        return null;
    }

    private static boolean isValidElseBranch(PsiIfStatement guardIf, PsiField field, PsiMethod hostMethod) {
        PsiStatement elseBranch = guardIf.getElseBranch();
        if (!(elseBranch instanceof PsiBlockStatement bs)) return false;
        // Else-block must contain exactly one assignment to the target field (other side effect
        // statements are allowed and will be preserved verbatim in the generated getter).
        // The field assignment's RHS must not depend on host-method parameters.
        PsiAssignmentExpression fieldAssign = null;
        for (PsiStatement stmt : bs.getCodeBlock().getStatements()) {
            if (stmt instanceof PsiExpressionStatement es
                    && es.getExpression() instanceof PsiAssignmentExpression a
                    && resolveToField(a.getLExpression()) == field) {
                if (fieldAssign != null) return false; // more than one
                fieldAssign = a;
            }
        }
        if (fieldAssign == null) return false;
        PsiExpression elseRhs = fieldAssign.getRExpression();
        return elseRhs == null || !referencesParam(elseRhs, hostMethod);
    }

    /**
     * Returns {@code true} when the condition of {@code guardIfStatement} is safe to move to
     * a getter — i.e. it contains no references to local variables in {@code hostMethod}.
     */
    private static boolean isGuardConditionMovable(PsiIfStatement guardIfStatement, PsiMethod hostMethod) {
        PsiExpression condition = guardIfStatement.getCondition();
        if (condition == null) return false;
        for (PsiReferenceExpression ref : allReferenceExpressions(condition)) {
            PsiElement resolved = ref.resolve();
            if (resolved instanceof PsiLocalVariable lv
                    && PsiTreeUtil.isAncestor(hostMethod, lv, true)) {
                return false;
            }
            // A parameter reference in the guard condition cannot be moved to the getter —
            // after parameter substitution the guard text would still contain the parameter
            // name, which is undefined in the getter scope (compile error).
            if (resolved instanceof PsiParameter p
                    && PsiTreeUtil.isAncestor(hostMethod, p, true)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Finds a no-arg getter for the field whose body is exactly {@code return <fieldName>;}.
     * Uses the PSI name index via {@code findMethodsByName} rather than iterating all methods.
     */
    static @Nullable PsiMethod findSimpleGetter(PsiField field) {
        PsiClass cls = field.getContainingClass();
        if (cls == null) return null;
        String fieldName = field.getName();
        String capitalized = capitalize(fieldName);
        return Stream.of("get" + capitalized, "is" + capitalized)
                .map(name -> cls.findMethodsByName(name, false))
                .flatMap(Arrays::stream)
                .filter(method -> isBodySimpleReturn(method, fieldName))
                .findFirst()
                .orElse(null);
    }

    private static boolean isBodySimpleReturn(PsiMethod m, String fieldName) {
        if (!m.getParameterList().isEmpty()) return false;
        PsiCodeBlock body = m.getBody();
        if (body == null) return false;
        PsiStatement[] stmts = body.getStatements();
        if (stmts.length != 1) return false;
        if (!(stmts[0] instanceof PsiReturnStatement ret)) return false;
        PsiExpression retExpr = ret.getReturnValue();
        if (!(retExpr instanceof PsiReferenceExpression retRef)) return false;
        return fieldName.equals(retRef.getReferenceName());
    }

    /**
     * Finds a getter for {@code field} that already contains a lazy-init null-check:
     * <pre>
     *     ReturnType getField() {
     *         if (field == null) { … }
     *         return field;
     *     }
     * </pre>
     * Criteria: no parameters; body has ≥ 2 statements; first statement is
     * {@code if (field == null) {…}}; last statement is {@code return field;}.
     */
    static @Nullable PsiMethod findLazyGetter(PsiField field) {
        PsiClass cls = field.getContainingClass();
        if (cls == null) return null;
        String fieldName = field.getName();
        String capitalized = capitalize(fieldName);
        return Stream.of("get" + capitalized, "is" + capitalized)
                .map(name -> cls.findMethodsByName(name, false))
                .flatMap(Arrays::stream)
                .filter(method -> isBodyLazyReturn(method, fieldName))
                .findFirst()
                .orElse(null);
    }

    private static boolean isBodyLazyReturn(PsiMethod m, String fieldName) {
        if (!m.getParameterList().isEmpty()) return false;
        PsiCodeBlock body = m.getBody();
        if (body == null) return false;
        PsiStatement[] stmts = body.getStatements();
        if (stmts.length < 2) return false;
        PsiStatement lastStmt = stmts[stmts.length - 1];
        if (!(lastStmt instanceof PsiReturnStatement ret)) return false;
        PsiExpression retExpr = ret.getReturnValue();
        if (!(retExpr instanceof PsiReferenceExpression retRef)) return false;
        if (!fieldName.equals(retRef.getReferenceName())) return false;
        if (!(stmts[0] instanceof PsiIfStatement ifStmt)) return false;
        PsiExpression condition = ifStmt.getCondition();
        if (condition == null) return false;
        return condition.getText().trim().equals(fieldName + " == null");
    }

    /**
     * Collects every {@link PsiReferenceExpression} in the subtree rooted at {@code root},
     * including {@code root} itself if it is a reference expression.
     * <p>
     * {@code PsiTreeUtil.findChildrenOfType} starts from {@code root.getFirstChild()} and
     * therefore misses the root when the root itself is the sole reference (e.g., a bare
     * parameter name like {@code value}).  Using a recursive visitor avoids that gap.
     */
    static List<PsiReferenceExpression> allReferenceExpressions(PsiElement root) {
        List<PsiReferenceExpression> result = new ArrayList<>();
        root.accept(new JavaRecursiveElementWalkingVisitor() {
            @Override
            public void visitReferenceExpression(@NotNull PsiReferenceExpression expression) {
                result.add(expression);
                super.visitReferenceExpression(expression);
            }
        });
        return result;
    }

    /**
     * Collects every non-final, non-static instance field of {@code containingClass} referenced
     * inside {@code root}, excluding {@code excludeField} itself.  Returns a deduplicated list
     * in encounter order.
     */
    static List<PsiField> collectInstanceFields(PsiElement root, PsiClass containingClass, PsiField excludeField) {
        List<PsiField> result = new ArrayList<>();
        Set<PsiField> seen = new HashSet<>();
        for (PsiReferenceExpression ref : allReferenceExpressions(root)) {
            PsiElement resolved = ref.resolve();
            if (resolved instanceof PsiField f
                    && f != excludeField
                    && containingClass == f.getContainingClass()
                    && !f.hasModifierProperty(PsiModifier.FINAL)
                    && !f.hasModifierProperty(PsiModifier.STATIC)
                    && seen.add(f)) {
                result.add(f);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Collects all non-final, non-static instance fields referenced in the effective
     * computation of {@code rhs} (including its build-chain preamble when the RHS uses locals).
     *
     * <p>Returns a deduplicated list in encounter order.  Callers use the size to distinguish
     * the three cases: 0 → standard null-check fixes apply; 1 → caching-map fix applies;
     * 2+ → no fix should be offered (composite key not supported).
     */
    static @NotNull List<PsiField> findVaryingFields(PsiExpression rhs, PsiMethod hostMethod, PsiField assignedField) {
        PsiClass cls = assignedField.getContainingClass();
        if (cls == null) return List.of();

        List<PsiField> found = new ArrayList<>(collectInstanceFields(rhs, cls, assignedField));
        collectChainPreambleFields(rhs, hostMethod, cls, assignedField, found);
        return List.copyOf(found);
    }

    /** Scans the build-chain preamble of any locals in {@code rhs} and adds their referenced instance fields. */
    private static void collectChainPreambleFields(PsiExpression rhs, PsiMethod hostMethod,
                                                   PsiClass cls, PsiField assignedField,
                                                   List<PsiField> found) {
        List<PsiLocalVariable> rhsLocals = findLocalVarsInExpr(rhs, hostMethod);
        if (rhsLocals.isEmpty()) return;

        PsiStatement assignStmt = PsiTreeUtil.getParentOfType(rhs, PsiStatement.class);
        if (assignStmt == null) return;
        PsiCodeBlock body = PsiTreeUtil.getParentOfType(assignStmt, PsiCodeBlock.class, true);
        if (body == null) return;

        List<PsiLocalVariable> chainLocals = collectBuildChainLocals(rhsLocals, body, hostMethod);
        if (chainLocals.isEmpty()) return;

        Set<PsiLocalVariable> chainSet = new HashSet<>(chainLocals);

        // Scan the declaration (constructor args may reference instance fields).
        Arrays.stream(body.getStatements())
                .filter(s -> isDeclarationOfAny(s, chainSet))
                .findFirst()
                .ifPresent(s -> collectInstanceFields(s, cls, assignedField).stream()
                        .filter(f -> !found.contains(f))
                        .forEach(found::add));

        // Scan the effective setters — last call per (local, method) across ALL preceding
        // segments — so constant setters from earlier segments are included.
        for (PsiStatement stmt : effectiveSettersBefore(body, chainLocals, assignStmt)) {
            for (PsiField f : collectInstanceFields(stmt, cls, assignedField)) {
                if (!found.contains(f)) found.add(f);
            }
        }
    }

    /**
     * Returns the single varying instance field for {@code rhs}, or {@code null} when
     * there are zero or more than one.  Delegates to {@link #findVaryingFields}.
     */
    static @Nullable PsiField findVaryingField(PsiExpression rhs, PsiMethod hostMethod, PsiField assignedField) {
        List<PsiField> all = findVaryingFields(rhs, hostMethod, assignedField);
        return all.size() == 1 ? all.getFirst() : null;
    }

    /** Returns true if {@code expr} references a local variable declared inside {@code scope}. */
    static boolean referencesLocalVar(PsiExpression expr, PsiMethod scope) {
        return allReferenceExpressions(expr).stream()
                .map(PsiReferenceExpression::resolve)
                .anyMatch(resolved -> resolved instanceof PsiLocalVariable
                        && PsiTreeUtil.isAncestor(scope, resolved, false));
    }

    /** Returns true if {@code expr} references a parameter of {@code scope}. */
    static boolean referencesParam(PsiExpression expr, PsiMethod scope) {
        return allReferenceExpressions(expr).stream()
                .map(PsiReferenceExpression::resolve)
                .anyMatch(resolved -> resolved instanceof PsiParameter
                        && PsiTreeUtil.isAncestor(scope, resolved, false));
    }

    /**
     * Returns all distinct local variables in {@code scope} referenced by {@code expr},
     * in the order they first appear.
     */
    static List<PsiLocalVariable> findLocalVarsInExpr(PsiExpression expr, PsiMethod scope) {
        return allReferenceExpressions(expr).stream()
                .map(PsiReferenceExpression::resolve)
                .filter(PsiLocalVariable.class::isInstance)
                .map(PsiLocalVariable.class::cast)
                .filter(local -> PsiTreeUtil.isAncestor(scope, local, false))
                .distinct()
                .toList();
    }

    /**
     * Returns {@code true} if {@code stmt} contains at least one reference expression that
     * resolves to {@code local}.
     *
     * <p>Note: the declaration site of a variable is a {@link PsiDeclarationStatement} whose
     * variable name is a {@link PsiIdentifier} — not a {@link PsiReferenceExpression} — so
     * this visitor returns {@code false} for the declaration statement itself.  Callers that
     * need to identify the declaration must use.
     */
    static boolean containsReferenceToLocal(PsiStatement stmt, PsiLocalVariable local) {
        return allReferenceExpressions(stmt).stream().anyMatch(ref -> ref.resolve() == local);
    }

    private static @Nullable PsiMethodCallExpression getSetterCallOn(PsiStatement stmt, PsiLocalVariable local) {
        if (!(stmt instanceof PsiExpressionStatement exprStmt)) return null;
        PsiExpression expr = exprStmt.getExpression();
        if (!(expr instanceof PsiMethodCallExpression call)) return null;
        PsiExpression qualifier = call.getMethodExpression().getQualifierExpression();
        if (!(qualifier instanceof PsiReferenceExpression qualRef)
                || qualRef.resolve() != local) return null;
        return call;
    }

    private static List<PsiLocalVariable> localDependenciesInSetterArgs(PsiMethodCallExpression call, PsiMethod scope) {
        List<PsiLocalVariable> deps = new ArrayList<>();
        Set<PsiLocalVariable> seen = new HashSet<>();
        for (PsiExpression arg : call.getArgumentList().getExpressions()) {
            for (PsiLocalVariable dep : findLocalVarsInExpr(arg, scope)) {
                if (seen.add(dep)) {
                    deps.add(dep);
                }
            }
        }
        return deps;
    }

    /** Searches {@code body} (not necessarily the method body) for setter dependencies. */
    private static List<PsiLocalVariable> collectBuildChainLocals(List<PsiLocalVariable> roots,
                                                                   @Nullable PsiCodeBlock body,
                                                                   PsiMethod hostMethod) {
        if (body == null) return List.of();

        LinkedHashSet<PsiLocalVariable> closure = new LinkedHashSet<>();
        ArrayDeque<PsiLocalVariable> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            expandBuildChainLocal(queue.removeFirst(), body, hostMethod, closure, queue);
        }
        return List.copyOf(closure);
    }

    private static void expandBuildChainLocal(PsiLocalVariable local, PsiCodeBlock body, PsiMethod hostMethod,
                                              Set<PsiLocalVariable> closure, ArrayDeque<PsiLocalVariable> queue) {
        if (!closure.add(local)) {
            return;
        }
        for (PsiStatement stmt : body.getStatements()) {
            enqueueSetterDependencies(stmt, local, hostMethod, closure, queue);
        }
    }

    private static void enqueueSetterDependencies(PsiStatement stmt, PsiLocalVariable local, PsiMethod hostMethod,
                                                  Set<PsiLocalVariable> closure, ArrayDeque<PsiLocalVariable> queue) {
        PsiMethodCallExpression call = getSetterCallOn(stmt, local);
        if (call == null) {
            return;
        }
        for (PsiLocalVariable dep : localDependenciesInSetterArgs(call, hostMethod)) {
            if (dep != local && !closure.contains(dep)) {
                queue.addLast(dep);
            }
        }
    }

    private static boolean isMovableBuildChain(List<PsiLocalVariable> locals, PsiMethod scope,
                                               PsiStatement assignmentStmt) {
        PsiCodeBlock body = PsiTreeUtil.getParentOfType(assignmentStmt, PsiCodeBlock.class, true);
        if (body == null) return false;

        Set<PsiLocalVariable> localSet = new HashSet<>(locals);
        if (locals.stream().anyMatch(local -> hasLocalDependentInitializer(local, scope))) return false;

        // Only validate statements within the segment [segmentStart, assignmentStmt).
        // Statements belonging to earlier or later segments are not part of this build chain.
        PsiStatement segmentStart = findSegmentStart(body, assignmentStmt, localSet);
        for (PsiStatement stmt : statementsInSegment(body, segmentStart, assignmentStmt)) {
            boolean shouldValidate = !isDeclarationOfAny(stmt, localSet) && referencesAnyLocal(stmt, locals);
            if (shouldValidate && !isSetterCallOnAny(stmt, localSet)) {
                debug("Build chain is not movable because of statement " + describeElement(stmt));
                return false;
            }
        }
        return true;
    }

    private static boolean hasLocalDependentInitializer(PsiLocalVariable local, PsiMethod scope) {
        PsiExpression init = local.getInitializer();
        if (init == null || !referencesLocalVar(init, scope)) {
            return false;
        }
        debug("Local '" + local.getName() + "' is not inlinable: initializer references another local");
        return true;
    }

    private static boolean referencesAnyLocal(PsiStatement stmt, List<PsiLocalVariable> locals) {
        return locals.stream().anyMatch(local -> containsReferenceToLocal(stmt, local));
    }

    private static boolean isSetterCallOnAny(PsiStatement stmt, Set<PsiLocalVariable> locals) {
        return locals.stream().anyMatch(local -> getSetterCallOn(stmt, local) != null);
    }

    private static boolean isDeclarationOfAny(PsiStatement stmt, Set<PsiLocalVariable> locals) {
        if (!(stmt instanceof PsiDeclarationStatement decl)) return false;
        return Arrays.stream(decl.getDeclaredElements())
                .anyMatch(elem -> elem instanceof PsiLocalVariable local && locals.contains(local));
    }

    /**
     * Returns {@code true} when any local in {@code locals} is referenced by a statement
     * that appears AFTER {@code assignmentStmt} in {@code body}.
     * Used in {@link FixContext#collectPreamble} to decide whether the declaration should be
     * kept in the host method after the fix, so post-assignment side effect statements can
     * still use the local (e.g. {@code System.out.println(argument)} after the field assignment).
     */
    private static boolean isAnyLocalReferencedAfterAssignment(PsiCodeBlock body,
                                                                Set<PsiLocalVariable> locals,
                                                                PsiStatement assignmentStmt) {
        boolean pastAssignment = false;
        for (PsiStatement stmt : body.getStatements()) {
            if (stmt == assignmentStmt) { pastAssignment = true; continue; }
            if (pastAssignment && locals.stream().anyMatch(lv -> containsReferenceToLocal(stmt, lv))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns all statements in {@code body} that appear before {@code upToExclusive}
     * (pass {@code null} to scan the entire body), are assignments to a {@link PsiField},
     * and whose RHS references at least one local in {@code chainLocals}.
     * These act as segment boundaries in the multi-assignment shared-local pattern.
     */
    static List<PsiStatement> findPeerFieldAssignmentsBefore(
            PsiCodeBlock body, Set<PsiLocalVariable> chainLocals,
            @Nullable PsiStatement upToExclusive) {
        List<PsiStatement> result = new ArrayList<>();
        for (PsiStatement stmt : body.getStatements()) {
            if (stmt == upToExclusive) break;
            if (stmt instanceof PsiExpressionStatement exprStmt
                    && exprStmt.getExpression() instanceof PsiAssignmentExpression assign
                    && resolveToField(assign.getLExpression()) != null
                    && chainLocals.stream().anyMatch(lv -> containsReferenceToLocal(stmt, lv))) {
                result.add(stmt);
            }
        }
        return result;
    }

    /**
     * Returns the first statement of the segment containing {@code currentAssignmentStmt},
     * or {@code null} if the segment starts at the beginning of the body.
     * The segment starts immediately after the last peer field assignment before this one.
     */
    static @Nullable PsiStatement findSegmentStart(
            PsiCodeBlock body, PsiStatement currentAssignmentStmt,
            Set<PsiLocalVariable> chainLocals) {
        List<PsiStatement> peers = findPeerFieldAssignmentsBefore(body, chainLocals, currentAssignmentStmt);
        if (peers.isEmpty()) return null;
        PsiStatement lastPeer = peers.getLast();
        PsiStatement[] stmts = body.getStatements();
        for (int i = 0; i < stmts.length - 1; i++) {
            if (stmts[i] == lastPeer) return stmts[i + 1];
        }
        return null;
    }

    /**
     * Returns the ordered list of statements in {@code body} that fall within the segment
     * {@code [segmentStart, upToExclusive)}.  Pass {@code null} for {@code segmentStart}
     * to start from the beginning of the body.
     */
    private static List<PsiStatement> statementsInSegment(PsiCodeBlock body,
                                                           @Nullable PsiStatement segmentStart,
                                                           PsiStatement upToExclusive) {
        List<PsiStatement> result = new ArrayList<>();
        boolean active = (segmentStart == null);
        for (PsiStatement stmt : body.getStatements()) {
            if (stmt == upToExclusive) break;
            if (stmt == segmentStart) active = true;
            if (active) result.add(stmt);
        }
        return result;
    }

    /**
     * Returns the "effective" setter calls on any local in {@code locals} before
     * {@code assignmentStmt} — the last call per {@code (local-name, method-name)} pair —
     * ordered by last-occurrence position.
     *
     * <p>This captures constant setters (e.g. {@code argument.setFilterTwo("constant")})
     * that appear only in an earlier segment: every segment's getter preamble must include
     * them so the chain local is in the same state it was during the original init() call.
     */
    private static List<PsiStatement> effectiveSettersBefore(
            PsiCodeBlock body, List<PsiLocalVariable> locals, PsiStatement assignmentStmt) {
        LinkedHashMap<String, PsiStatement> lastByKey = new LinkedHashMap<>();
        for (PsiStatement stmt : body.getStatements()) {
            if (stmt == assignmentStmt) break;
            for (PsiLocalVariable local : locals) {
                PsiMethodCallExpression call = getSetterCallOn(stmt, local);
                if (call != null) {
                    String key = local.getName() + "." + call.getMethodExpression().getReferenceName();
                    lastByKey.remove(key); // re-insert at end to track last-occurrence order
                    lastByKey.put(key, stmt);
                    break;
                }
            }
        }
        return new ArrayList<>(lastByKey.values());
    }

    /**
     * Returns the single {@link PsiMethodCallExpression} that calls {@code method},
     * or {@code null} if there are zero or more than one call sites.
     */
    static @Nullable PsiMethodCallExpression findSingleCallSite(PsiMethod method) {
        Collection<PsiReference> refs = ReferencesSearch.search(method).findAll();
        if (refs.size() != 1) return null;
        PsiElement element = refs.iterator().next().getElement();
        return PsiTreeUtil.getParentOfType(element, PsiMethodCallExpression.class);
    }

    private static boolean hasUnsafeLocalArgument(@Nullable PsiMethodCallExpression call) {
        if (call == null) return true;
        PsiMethod caller = PsiTreeUtil.getParentOfType(call, PsiMethod.class);
        if (caller == null) return true;
        return Arrays.stream(call.getArgumentList().getExpressions())
                .anyMatch(arg -> referencesLocalVar(arg, caller));
    }

    /**
     * Produces the text of {@code rhs} with every reference to a parameter of
     * {@code hostMethod} replaced by the corresponding argument from {@code call}.
     * Returns {@code null} if the argument count does not match.
     */
    static @Nullable String substituteParams(PsiExpression rhs, PsiMethod hostMethod,
                                             PsiMethodCallExpression call) {
        // Local record pairs a reference expression with the index of the parameter it resolves to,
        // avoiding a redundant resolve() call and linear param scan during the replacement loop.
        record ParamRef(PsiReferenceExpression ref, int idx) {}

        PsiParameter[] params = hostMethod.getParameterList().getParameters();
        PsiExpression[] args = call.getArgumentList().getExpressions();
        if (params.length != args.length) return null;

        Map<PsiParameter, Integer> paramIndexes = new IdentityHashMap<>();
        for (int i = 0; i < params.length; i++) {
            paramIndexes.put(params[i], i);
        }
        List<ParamRef> paramRefs = new ArrayList<>(allReferenceExpressions(rhs).stream()
                .map(ref -> {
                    PsiElement resolved = ref.resolve();
                    Integer idx = resolved instanceof PsiParameter parameter
                            ? paramIndexes.get(parameter)
                            : null;
                    return idx == null ? null : new ParamRef(ref, idx);
                })
                .filter(Objects::nonNull)
                .toList());
        if (paramRefs.isEmpty()) return rhs.getText();

        int base = rhs.getTextRange().getStartOffset();
        paramRefs.sort((a, b) -> b.ref().getTextRange().getStartOffset()
                - a.ref().getTextRange().getStartOffset());

        StringBuilder sb = new StringBuilder(rhs.getText());
        for (ParamRef pr : paramRefs) {
            int start = pr.ref().getTextRange().getStartOffset() - base;
            int end   = pr.ref().getTextRange().getEndOffset()   - base;
            sb.replace(start, end, args[pr.idx()].getText());
        }
        return sb.toString();
    }

    private static String buildMessage(PsiField field, String methodName, int callSiteCount) {
        String base = "Assignment to '" + field.getName() + "' can be converted to lazy initialization in getter";
        if (callSiteCount > 1) {
            base += " (caution: '" + methodName + "' has " + callSiteCount
                    + " call sites — removing it may change observable behavior)";
        }
        return base;
    }

    static String capitalize(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // -----------------------------------------------------------------------
    // Shared resolution context — eliminates duplicate resolve blocks across
    // the two quick-fix classes.
    // -----------------------------------------------------------------------

    /**
     * Holds every piece both quick-fix implementations need, resolved once from
     * the problem descriptor.  Returns {@code null} if any required element has
     * become stale or invalid by the time the fix is invoked.
     *
     * <p>{@code effectiveRhsText} is the RHS text to embed in the generated
     * null-check — equal to {@code rhs.getText()} unless a method parameter was
     * substituted with its argument from the sole call site.
     *
     * <p>{@code callSiteToRemove} is non-null when parameter substitution was
     * performed; the fix must delete this statement after rewriting the getter so
     * that the now-deleted host method's call site is cleaned up too.
     *
     * <p>{@code preambleStatements} carries the source text of every statement in
     * the host method that belongs to an inlinable local's build chain (declaration
     * + setter calls).  The quick-fix inserts these, in order, before the
     * {@code field = rhs} assignment inside the null-check block.
     *
     * <p>{@code preambleToRemove} are the live PSI nodes corresponding to
     * {@code preambleStatements}; the quick-fix deletes them from the host method
     * after the getter has been rewritten.
     *
     * <p>{@code fieldName()} and {@code factory()} are derived from other components
     * rather than stored redundantly.
     */
    record FixContext(
            PsiAssignmentExpression assignment,
            PsiField field,
            PsiMethod hostMethod,
            PsiMethod getter,
            String effectiveRhsText,
            @Nullable PsiExpressionStatement callSiteToRemove,
            List<String> preambleStatements,
            List<PsiStatement> preambleToRemove,
            @Nullable PsiField varyingField,
            @Nullable String guardConditionText,
            @Nullable PsiIfStatement guardIfStatement,
            boolean getterAlreadyLazy
    ) {
        /** Derived — never stored separately. */
        String fieldName() { return field.getName(); }

        /** Derived — never stored separately. */
        PsiElementFactory factory() { return JavaPsiFacade.getElementFactory(field.getProject()); }

        /** Derived from {@link #guardIfStatement()} — returns the RHS text of a single-statement else-branch. */
        @Nullable String guardElseRhsText() { return extractElseRhsText(guardIfStatement()); }

        static @Nullable FixContext from(@NotNull ProblemDescriptor descriptor) {
            PsiAssignmentExpression assignment = (PsiAssignmentExpression) descriptor.getPsiElement();
            PsiField field = resolveToField(assignment.getLExpression());
            if (field == null) {
                debug("Cannot build fix context: left-hand side no longer resolves to a field at "
                        + describeElement(assignment));
                return null;
            }
            HostContext hostCtx = getHostContext(assignment);
            if (hostCtx == null) {
                debug("Cannot build fix context: host method is no longer available at " + describeElement(assignment));
                return null;
            }
            PsiMethod hostMethod = hostCtx.method();
            GetterInfo getterInfo = resolveGetterInfo(field);
            if (getterInfo == null) {
                debug("Cannot build fix context: no simple or lazy getter found for field '" + field.getName() + "'");
                return null;
            }
            PsiMethod getter = getterInfo.getter();
            boolean getterAlreadyLazy = getterInfo.alreadyLazy();
            PsiExpression rhs = assignment.getRExpression();
            if (rhs == null) {
                debug("Cannot build fix context: assignment has no right-hand side at " + describeElement(assignment));
                return null;
            }

            String effectiveRhsText = rhs.getText();
            PsiExpressionStatement callSiteToRemove = null;
            Preamble preamble = Preamble.EMPTY;

            if (referencesParam(rhs, hostMethod)) {
                ParamSubstitution sub = resolveParamSubstitution(rhs, hostMethod);
                if (sub != null) {
                    effectiveRhsText = sub.rhsText();
                    callSiteToRemove = sub.callSiteToRemove();
                    debug("Resolved parameter substitution for field '" + field.getName()
                            + "' to rhs '" + summarizeText(effectiveRhsText) + "'");
                } else {
                    debug("No parameter substitution available for field '" + field.getName()
                            + IN_METHOD + hostMethod.getName() + "'");
                }
            } else if (referencesLocalVar(rhs, hostMethod)) {
                PsiStatement assignmentStmt = PsiTreeUtil.getParentOfType(assignment, PsiStatement.class);
                preamble = collectPreamble(rhs, hostMethod, assignmentStmt);
                debug("Collected " + preamble.texts().size() + " preamble statements for field '"
                        + field.getName() + "'");
            }

            PsiField varyingField = findVaryingField(rhs, hostMethod, field);

            // When the guard condition is already a null-check on the target field
            // (e.g. "name == null"), the outer null-check in the getter is identical.
            // Pass null for the guard text so the fix generates a single null-check,
            // but keep the guardIfStatement reference so it is still cleaned up.
            String guardCondText = hostCtx.guardConditionText();
            if (isRedundantNullCheckGuard(guardCondText, field.getName())) {
                guardCondText = null;
            }

            return new FixContext(
                    assignment, field, hostMethod, getter,
                    effectiveRhsText, callSiteToRemove,
                    preamble.texts(), preamble.nodes(), varyingField,
                    guardCondText, hostCtx.guardIfStatement(), getterAlreadyLazy);
        }

        // -- helpers kept private to FixContext --------------------------------

        private static @Nullable String extractElseRhsText(@Nullable PsiIfStatement guardIf) {
            if (guardIf == null) return null;
            PsiStatement elseBranch = guardIf.getElseBranch();
            if (!(elseBranch instanceof PsiBlockStatement bs)) return null;
            PsiStatement[] stmts = bs.getCodeBlock().getStatements();
            if (stmts.length != 1) return null;
            if (!(stmts[0] instanceof PsiExpressionStatement exprStmt)) return null;
            if (!(exprStmt.getExpression() instanceof PsiAssignmentExpression assign)) return null;
            PsiExpression rhs = assign.getRExpression();
            return rhs == null ? null : rhs.getText();
        }

        private record ParamSubstitution(String rhsText,
                                         @Nullable PsiExpressionStatement callSiteToRemove) {}

        private static @Nullable ParamSubstitution resolveParamSubstitution(PsiExpression rhs,
                                                                             PsiMethod hostMethod) {
            PsiMethodCallExpression callExpr = findSingleCallSite(hostMethod);
            if (callExpr == null || hasUnsafeLocalArgument(callExpr)) return null;
            String substituted = substituteParams(rhs, hostMethod, callExpr);
            if (substituted == null) return null;
            PsiExpressionStatement callSite = null;
            if (callExpr.getParent() instanceof PsiExpressionStatement stmt) {
                callSite = stmt;
            }
            return new ParamSubstitution(substituted, callSite);
        }

        /** Encapsulates the pair of parallel lists that describe the local-build-chain preamble. */
        private record Preamble(List<String> texts, List<PsiStatement> nodes) {
            static final Preamble EMPTY = new Preamble(List.of(), List.of());
        }

        private static Preamble collectPreamble(PsiExpression rhs, PsiMethod hostMethod,
                                                 @Nullable PsiStatement assignmentStmt) {
            if (assignmentStmt == null) return Preamble.EMPTY;
            PsiCodeBlock body = PsiTreeUtil.getParentOfType(assignmentStmt, PsiCodeBlock.class, true);
            if (body == null) return Preamble.EMPTY;
            List<PsiLocalVariable> locals = collectBuildChainLocals(findLocalVarsInExpr(rhs, hostMethod), body, hostMethod);
            if (locals.isEmpty()) return Preamble.EMPTY;
            return buildPreamble(body, assignmentStmt, locals);
        }

        private static Preamble buildPreamble(PsiCodeBlock body, PsiStatement assignmentStmt,
                                              List<PsiLocalVariable> locals) {
            Set<PsiLocalVariable> localSet = new HashSet<>(locals);
            PsiStatement segmentStart = findSegmentStart(body, assignmentStmt, localSet);

            // The declaration must be kept in init (not deleted) in two cases:
            // 1. Multi-assignment: shared local used by several field assignments — kept until all are fixed.
            // 2. Local used after assignment: e.g. System.out.println(argument) after list = svc.get(argument).
            //    Deleting the declaration would leave a dangling reference in the host method.
            boolean isMultiAssignment = findPeerFieldAssignmentsBefore(body, localSet, null)
                    .stream().anyMatch(s -> s != assignmentStmt);
            boolean isLocalUsedAfterAssignment = isAnyLocalReferencedAfterAssignment(body, localSet, assignmentStmt);
            // Declaration stays in init if shared across multiple assignments OR used after the assignment.
            // Segment-specific setters stay in init only when the local is used after the assignment
            // AND it is not a multi-assignment — in the multi-assignment case the setters are
            // segment-specific and belong in the getter, not in init.
            boolean keepDeclarationInInit = isMultiAssignment || isLocalUsedAfterAssignment;
            boolean keepSettersInInit = isLocalUsedAfterAssignment && !isMultiAssignment;

            List<String> texts = new ArrayList<>();
            List<PsiStatement> nodes = new ArrayList<>();

            for (PsiStatement stmt : body.getStatements()) {
                if (isDeclarationOfAny(stmt, localSet)) {
                    texts.add(stmt.getText());
                    if (!keepDeclarationInInit) nodes.add(stmt);
                }
            }

            // Effective setters: last call per (receiver local, method-name) across ALL
            // segments before this assignment, in last-occurrence order.
            // Constant setters (called once before all segments, e.g. setFilterTwo) are included
            // in the getter text but NOT added to preambleToRemove here — they will be removed
            // from init when the segment that physically owns them is fixed, or by
            // cleanupUnusedLocalDeclarations once no references remain.
            Set<PsiStatement> segmentSetterSet = new HashSet<>(
                    statementsInSegment(body, segmentStart, assignmentStmt).stream()
                            .filter(s -> !isDeclarationOfAny(s, localSet) && isSetterCallOnAny(s, localSet))
                            .toList());
            // Setters that are segment-specific and safe to remove from init.
            // keepSettersInInit is true when the local is used after the assignment — removing
            // its setters would corrupt the state that post-assignment statements depend on.
            Set<PsiStatement> removableSetters = keepSettersInInit ? Set.of() : segmentSetterSet;
            for (PsiStatement stmt : effectiveSettersBefore(body, locals, assignmentStmt)) {
                texts.add(stmt.getText());
                if (removableSetters.contains(stmt)) nodes.add(stmt);
            }

            return new Preamble(texts, nodes);
        }

    }

    // -----------------------------------------------------------------------
    // Guard / call-site safety helpers
    // -----------------------------------------------------------------------

    /**
     * Returns {@code true} when the guard condition text is already a null-check on the target
     * field (e.g. {@code "name == null"} or {@code "null == name"}).  Embedding such a condition
     * inside the outer null-check would produce a redundant double check.
     */
    private static boolean isRedundantNullCheckGuard(@Nullable String conditionText, String fieldName) {
        if (conditionText == null) return false;
        String stripped = conditionText.strip();
        return stripped.equals(fieldName + " == null") || stripped.equals("null == " + fieldName);
    }

    /**
     * Returns {@code true} when any {@link PsiIfStatement} that appears <em>before</em>
     * {@code assignmentStmt} in {@code body} has an unconditional {@code return} statement in
     * one of its branches.  Such a statement acts as an early-return guard: the assignment only
     * runs when the early-return condition is false, so moving it to the getter would make it
     * unconditional.
     */
    private static boolean hasEarlyReturnGuardBefore(PsiCodeBlock body, PsiStatement assignmentStmt) {
        for (PsiStatement stmt : body.getStatements()) {
            if (stmt == assignmentStmt) break;
            if (stmt instanceof PsiIfStatement ifStmt && ifStmtHasReturnBranch(ifStmt)) {
                return true;
            }
        }
        return false;
    }

    private static boolean ifStmtHasReturnBranch(PsiIfStatement ifStmt) {
        return isSingleReturnStatement(ifStmt.getThenBranch())
                || isSingleReturnStatement(ifStmt.getElseBranch());
    }

    private static boolean isSingleReturnStatement(@Nullable PsiStatement stmt) {
        if (stmt instanceof PsiReturnStatement) return true;
        if (stmt instanceof PsiBlockStatement bs) {
            PsiStatement[] stmts = bs.getCodeBlock().getStatements();
            return stmts.length == 1 && stmts[0] instanceof PsiReturnStatement;
        }
        return false;
    }

    /**
     * Returns {@code true} when {@code call} is nested inside a conditional construct
     * (if-statement, ternary, loop, or switch) within its containing method.  A guarded call
     * site means the field might never be initialized if the condition is never true.
     */
    private static boolean isCallSiteInConditional(@Nullable PsiMethodCallExpression call) {
        if (call == null) return true;
        PsiMethod callerMethod = PsiTreeUtil.getParentOfType(call, PsiMethod.class);
        if (callerMethod == null) return true;
        PsiElement parent = call.getParent();
        while (parent != null && parent != callerMethod) {
            if (parent instanceof PsiIfStatement
                    || parent instanceof PsiConditionalExpression
                    || parent instanceof PsiWhileStatement
                    || parent instanceof PsiForStatement
                    || parent instanceof PsiForeachStatement
                    || parent instanceof PsiSwitchStatement
                    || parent instanceof PsiDoWhileStatement) {
                return true;
            }
            parent = parent.getParent();
        }
        return false;
    }

    static void debug(String message) {
        LOG.info(message);
    }

    private static void debugSkip(PsiAssignmentExpression expression, String reason) {
        debug("Skipping assignment at " + describeElement(expression) + ": " + reason);
    }

    static String describeElement(PsiElement element) {
        PsiFile file = element.getContainingFile();
        String fileName = file == null ? "<no-file>" : file.getName();
        return fileName + ":" + element.getTextOffset() + " [" + summarizeText(element.getText()) + "]";
    }

    private static String namesOf(List<PsiLocalVariable> locals) {
        return locals.stream().map(PsiLocalVariable::getName).toList().toString();
    }

    private static String summarizeText(String text) {
        String singleLine = text.replaceAll("\\s+", " ").trim();
        return singleLine.length() <= 120 ? singleLine : singleLine.substring(0, 117) + "...";
    }
}
