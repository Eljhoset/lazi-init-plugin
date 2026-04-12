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

        PsiMethod hostMethod = getDirectHostMethod(expression);
        if (hostMethod == null) { debugSkip(expression, "assignment is not a direct statement in a method body"); return; }
        if (hostMethod.isConstructor()) { debugSkip(expression, "host method '" + hostMethod.getName() + "' is a constructor"); return; }

        PsiMethod getter = findSimpleGetter(field);
        if (getter == null) { debugSkip(expression, "no simple getter found for field '" + field.getName() + "'"); return; }

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

        debug("Registering lazy-init inspection for field '" + field.getName() + "' in method '"
                + hostMethod.getName() + "' at " + describeElement(expression)
                + " with getter '" + getter.getName() + "' and callSites=" + hostRefs.size()
                + " varyingField=" + (varyingField != null ? varyingField.getName() : "none"));

        holder.registerProblem(expression,
                buildMessage(field, hostMethod.getName(), hostRefs.size()),
                buildFixes(field, varyingField));
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
        return true;
    }

    /**
     * Classifies all reference expressions in an RHS expression in a single PSI walk,
     * avoiding separate repeated traversals for params, locals, and local collection.
     */
    private record RhsProfile(boolean hasParam, boolean hasLocal, List<PsiLocalVariable> locals) {
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
            return new RhsProfile(hasParam, hasLocal, locals);
        }
    }

    private static boolean allLocalsAreInlinable(List<PsiLocalVariable> locals, PsiMethod hostMethod,
                                                  PsiAssignmentExpression expression) {
        PsiStatement assignmentStmt = PsiTreeUtil.getParentOfType(expression, PsiStatement.class);
        if (assignmentStmt == null) return false;
        return isMovableBuildChain(collectBuildChainLocals(locals, hostMethod), hostMethod, assignmentStmt);
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

    // -----------------------------------------------------------------------
    // PSI resolution helpers (package-visible for use by the quick-fix classes)
    // -----------------------------------------------------------------------

    static @Nullable PsiField resolveToField(PsiExpression expr) {
        if (!(expr instanceof PsiReferenceExpression ref)) return null;
        PsiElement resolved = ref.resolve();
        return resolved instanceof PsiField f ? f : null;
    }

    /**
     * Returns the enclosing method only when the assignment is a direct statement
     * in that method's body — not nested inside a lambda, anonymous class, etc.
     */
    static @Nullable PsiMethod getDirectHostMethod(PsiAssignmentExpression assignment) {
        PsiElement stmt = assignment.getParent();
        if (!(stmt instanceof PsiExpressionStatement)) return null;
        PsiElement block = stmt.getParent();
        if (!(block instanceof PsiCodeBlock)) return null;
        PsiElement methodCandidate = block.getParent();
        return methodCandidate instanceof PsiMethod m ? m : null;
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
    static List<PsiField> findVaryingFields(PsiExpression rhs, PsiMethod hostMethod, PsiField assignedField) {
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

        List<PsiLocalVariable> chainLocals = collectBuildChainLocals(rhsLocals, hostMethod);
        if (chainLocals.isEmpty()) return;

        Set<PsiLocalVariable> chainSet = new HashSet<>(chainLocals);
        PsiCodeBlock body = hostMethod.getBody();
        PsiStatement assignStmt = PsiTreeUtil.getParentOfType(rhs, PsiStatement.class);
        if (body == null || assignStmt == null) return;

        // Scan the declaration (constructor args may reference instance fields).
        for (PsiStatement stmt : body.getStatements()) {
            if (isDeclarationOfAny(stmt, chainSet)) {
                for (PsiField f : collectInstanceFields(stmt, cls, assignedField)) {
                    if (!found.contains(f)) found.add(f);
                }
                break;
            }
        }

        // Scan the effective setters — last call per (local, method) across ALL preceding
        // segments — so constant setters from earlier segments are included.
        for (PsiStatement stmt : effectiveSettersBefore(body, chainLocals, assignStmt)) {
            for (PsiField f : collectInstanceFields(stmt, cls, assignedField)) {
                if (!found.contains(f)) found.add(f);
            }
        }
    }

    private static boolean isChainPreambleStatement(PsiStatement stmt, Set<PsiLocalVariable> chainSet) {
        return isDeclarationOfAny(stmt, chainSet) || isSetterCallOnAny(stmt, chainSet);
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

    /**
     * Returns {@code true} when {@code stmt} is a method-call statement with {@code local}
     * as the direct qualifier (a setter/builder call) and every argument references no local
     * variable declared in {@code scope}.
     */
    private static List<PsiLocalVariable> collectBuildChainLocals(List<PsiLocalVariable> roots, PsiMethod hostMethod) {
        PsiCodeBlock body = hostMethod.getBody();
        if (body == null) return List.of();

        LinkedHashSet<PsiLocalVariable> closure = new LinkedHashSet<>();
        ArrayDeque<PsiLocalVariable> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            PsiLocalVariable local = queue.removeFirst();
            expandBuildChainLocal(local, body, hostMethod, closure, queue);
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
        PsiCodeBlock body = scope.getBody();
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
                if (call == null) continue;
                String key = local.getName() + "." + call.getMethodExpression().getReferenceName();
                lastByKey.remove(key); // re-insert at end to track last-occurrence order
                lastByKey.put(key, stmt);
                break;
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
            @Nullable PsiField varyingField
    ) {
        /** Derived — never stored separately. */
        String fieldName() { return field.getName(); }

        /** Derived — never stored separately. */
        PsiElementFactory factory() { return JavaPsiFacade.getElementFactory(field.getProject()); }

        static @Nullable FixContext from(@NotNull ProblemDescriptor descriptor) {
            PsiAssignmentExpression assignment = (PsiAssignmentExpression) descriptor.getPsiElement();
            PsiField field = resolveToField(assignment.getLExpression());
            if (field == null) {
                debug("Cannot build fix context: left-hand side no longer resolves to a field at "
                        + describeElement(assignment));
                return null;
            }
            PsiMethod hostMethod = getDirectHostMethod(assignment);
            if (hostMethod == null) {
                debug("Cannot build fix context: host method is no longer available at " + describeElement(assignment));
                return null;
            }
            PsiMethod getter = findSimpleGetter(field);
            if (getter == null) {
                debug("Cannot build fix context: getter for field '" + field.getName() + "' is no longer simple");
                return null;
            }
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
                            + "' in method '" + hostMethod.getName() + "'");
                }
            } else if (referencesLocalVar(rhs, hostMethod)) {
                PsiStatement assignmentStmt = PsiTreeUtil.getParentOfType(assignment, PsiStatement.class);
                preamble = collectPreamble(rhs, hostMethod, assignmentStmt);
                debug("Collected " + preamble.texts().size() + " preamble statements for field '"
                        + field.getName() + "'");
            }

            PsiField varyingField = findVaryingField(rhs, hostMethod, field);

            return new FixContext(
                    assignment, field, hostMethod, getter,
                    effectiveRhsText, callSiteToRemove,
                    preamble.texts(), preamble.nodes(), varyingField);
        }

        // -- helpers kept private to FixContext --------------------------------

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
            PsiCodeBlock body = hostMethod.getBody();
            if (body == null) return Preamble.EMPTY;
            List<PsiLocalVariable> locals = collectBuildChainLocals(findLocalVarsInExpr(rhs, hostMethod), hostMethod);
            if (locals.isEmpty()) return Preamble.EMPTY;

            Set<PsiLocalVariable> localSet = new HashSet<>(locals);
            PsiStatement segmentStart = findSegmentStart(body, assignmentStmt, localSet);

            // In the multi-assignment pattern, the local's declaration is shared across segments.
            // It must be copied into each getter's null-check text but kept in init until all
            // assignments are converted (at which point cleanupUnusedLocalDeclarations removes it).
            // In the single-assignment case, the declaration belongs to preambleToRemove as before.
            boolean isMultiAssignment = findPeerFieldAssignmentsBefore(body, localSet, null)
                    .stream().anyMatch(s -> s != assignmentStmt);

            List<String> texts = new ArrayList<>();
            List<PsiStatement> nodes = new ArrayList<>();

            // Step A: declaration — always copied into getter; deleted from init only for single-assignment.
            for (PsiStatement stmt : body.getStatements()) {
                if (isDeclarationOfAny(stmt, localSet)) {
                    texts.add(stmt.getText());
                    if (!isMultiAssignment) nodes.add(stmt);
                }
            }

            // Step B: effective setters — last call per (receiver local, method-name) across ALL
            // segments before this assignment, in last-occurrence order.
            // Constant setters (called once before all segments, e.g. setFilterTwo) are included
            // in the getter text but NOT added to preambleToRemove here — they will be removed
            // from init when the segment that physically owns them is fixed, or by
            // cleanupUnusedLocalDeclarations once no references remain.
            Set<PsiStatement> segmentSetterSet = new HashSet<>(
                    statementsInSegment(body, segmentStart, assignmentStmt).stream()
                            .filter(s -> !isDeclarationOfAny(s, localSet) && isSetterCallOnAny(s, localSet))
                            .toList());
            for (PsiStatement stmt : effectiveSettersBefore(body, locals, assignmentStmt)) {
                texts.add(stmt.getText());
                if (segmentSetterSet.contains(stmt)) {
                    nodes.add(stmt);
                }
            }

            return new Preamble(texts, nodes);
        }

        private static boolean isPartOfBuildChain(PsiStatement stmt, List<PsiLocalVariable> locals) {
            if (stmt instanceof PsiDeclarationStatement decl && Arrays.stream(decl.getDeclaredElements())
                        .anyMatch(elem -> elem instanceof PsiLocalVariable lv && locals.contains(lv))) {
                    return true;
                }

            return locals.stream().anyMatch(lv -> containsReferenceToLocal(stmt, lv));
        }
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
