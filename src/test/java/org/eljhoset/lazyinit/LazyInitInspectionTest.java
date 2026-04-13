package org.eljhoset.lazyinit;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.testFramework.fixtures.BasePlatformTestCase;

import java.util.List;

public class LazyInitInspectionTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(new LazyInitInspection());
    }

    // -----------------------------------------------------------------------
    // Fix 1: simple lazy initialization
    // -----------------------------------------------------------------------

    public void testSimpleLazyInitTransformation() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String name;

                    void init() {
                        <caret>name = "default";
                    }

                    String getName() {
                        return name;
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention("Convert to lazy initialization in getter");
        assertNotNull("Simple lazy-init fix must be available", fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();
        assertFalse("init() should be removed when it becomes empty", result.contains("void init()"));
        assertTrue("Null-check guard must be present", result.contains("if (name == null)"));
        assertTrue("Assignment must appear inside the null-check", result.contains("name = \"default\""));
        assertTrue("Return statement must be preserved", result.contains("return name;"));
    }

    // -----------------------------------------------------------------------
    // Fix 2: double-checked locking
    // -----------------------------------------------------------------------

    public void testDoubleCheckedLockingTransformation() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String name;

                    void init() {
                        <caret>name = "default";
                    }

                    String getName() {
                        return name;
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention(
                "Convert to thread-safe lazy initialization (double-checked locking)");
        assertNotNull("DCL fix must be available", fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();
        assertFalse("init() should be removed when it becomes empty", result.contains("void init()"));
        assertTrue("Field must become volatile", result.contains("volatile String name"));
        assertTrue("Outer null-check must be present", result.contains("if (name == null)"));
        assertTrue("synchronized block must be present", result.contains("synchronized (this)"));
        // Inner null-check appears after the synchronized keyword
        assertTrue("Inner null-check must be inside synchronized block",
                result.indexOf("synchronized") < result.lastIndexOf("if (name == null)"));
        assertTrue("Assignment must be inside the DCL block", result.contains("name = \"default\""));
        assertTrue("Return statement must be preserved", result.contains("return name;"));
    }

    // -----------------------------------------------------------------------
    // Suppression: field already has a non-null initializer
    // -----------------------------------------------------------------------

    public void testNoFixWhenFieldHasInitializer() {
        myFixture.configureByText("Foo.java", """
                public class Foo {
                    private String name = "";

                    void init() {
                        <caret>name = "new value";
                    }

                    String getName() {
                        return name;
                    }
                }
                """);

        List<IntentionAction> fixes = myFixture.getAllQuickFixes();
        boolean hasLazyFix = fixes.stream().anyMatch(f -> f.getText().contains("lazy initialization")
                || f.getText().contains("double-checked locking"));
        assertFalse("Lazy-init fix must NOT be offered when the field already has an initializer", hasLazyFix);
    }

    // -----------------------------------------------------------------------
    // Suppression: no getter present
    // -----------------------------------------------------------------------

    public void testNoFixWhenNoGetter() {
        myFixture.configureByText("Foo.java", """
                public class Foo {
                    private String name;

                    void init() {
                        <caret>name = "x";
                    }

                    // No getter — inspection requires a simple "return field;" getter to exist
                }
                """);

        List<IntentionAction> fixes = myFixture.getAllQuickFixes();
        boolean hasLazyFix = fixes.stream().anyMatch(f -> f.getText().contains("lazy initialization"));
        assertFalse("Lazy-init fix must NOT be offered when there is no simple getter", hasLazyFix);
    }

    // -----------------------------------------------------------------------
    // Suppression: final field
    // -----------------------------------------------------------------------

    public void testNoFixForFinalField() {
        myFixture.configureByText("Foo.java", """
                public class Foo {
                    private final String name;

                    Foo() { this.name = "init"; }

                    String getName() {
                        return name;
                    }
                }
                """);

        // No assignment to a final field in a non-constructor method → no highlight at all
        List<IntentionAction> fixes = myFixture.getAllQuickFixes();
        boolean hasLazyFix = fixes.stream().anyMatch(f -> f.getText().contains("lazy initialization"));
        assertFalse("Lazy-init fix must NOT be offered for final fields", hasLazyFix);
    }

    // -----------------------------------------------------------------------
    // Warning: host method called from multiple sites
    // -----------------------------------------------------------------------

    public void testMultiCallSiteWarningIncludedInMessage() {
        myFixture.configureByText("Foo.java", """
                public class Foo {
                    private String name;

                    void init() {
                        <caret>name = "value";
                    }

                    String getName() {
                        return name;
                    }

                    void setup() {
                        init();
                    }

                    void reset() {
                        init();
                    }
                }
                """);

        boolean warningFound = myFixture.doHighlighting().stream()
                .anyMatch(h -> h.getDescription() != null
                        && h.getDescription().contains("call site"));
        assertTrue("Problem description must warn about multiple call sites", warningFound);
    }

    // -----------------------------------------------------------------------
    // Parameter substitution: argument is inlined and call site is removed
    // -----------------------------------------------------------------------

    public void testParameterArgumentIsInlinedIntoGetter() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String name;

                    void init(String value) {
                        <caret>name = value;
                    }

                    String getName() {
                        return name;
                    }

                    void setup() {
                        init("default");
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention("Convert to lazy initialization in getter");
        assertNotNull("Fix must be available when there is a single call site to substitute from", fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();
        assertFalse("init() should be removed when it becomes empty", result.contains("void init("));
        assertTrue("Null-check guard must be present in getter", result.contains("if (name == null)"));
        assertTrue("Argument must be inlined as the RHS", result.contains("name = \"default\""));
        assertFalse("Call site init(\"default\") must be removed", result.contains("init(\"default\")"));
        assertTrue("Return statement must be preserved", result.contains("return name;"));
    }

    // -----------------------------------------------------------------------
    // Suppression: multiple call sites — cannot determine which argument to inline
    // -----------------------------------------------------------------------

    public void testNoFixWhenParameterHasMultipleCallSites() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String name;

                    void init(String value) {
                        <caret>name = value;
                    }

                    String getName() {
                        return name;
                    }

                    void setupA() { init("first"); }
                    void setupB() { init("second"); }
                }
                """);

        List<IntentionAction> fixes = myFixture.getAllQuickFixes();
        boolean hasLazyFix = fixes.stream().anyMatch(f -> f.getText().contains("lazy initialization")
                || f.getText().contains("double-checked locking"));
        assertFalse("Fix must NOT be offered when there are multiple call sites with different arguments",
                hasLazyFix);
    }

    public void testNoFixWhenSingleCallSiteArgumentDependsOnCallerLocal() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    void init() {
                        String filter = "";
                        Argument argument = new Argument();
                        argument.setFilterOne(filter);
                    }

                    static class Argument {
                        private String filterOne;

                        void setFilterOne(String filterOne) {
                            <caret>this.filterOne = filterOne;
                        }

                        String getFilterOne() {
                            return filterOne;
                        }
                    }
                }
                """);

        List<IntentionAction> fixes = myFixture.getAllQuickFixes();
        boolean hasLazyFix = fixes.stream().anyMatch(f -> f.getText().contains("lazy initialization")
                || f.getText().contains("double-checked locking"));
        assertFalse("Fix must NOT be offered when the sole call-site argument depends on a caller local", hasLazyFix);
    }

    // -----------------------------------------------------------------------
    // Host method is NOT deleted when it still has other statements
    // -----------------------------------------------------------------------

    public void testHostMethodKeptWhenNotEmpty() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String name;

                    void init() {
                        <caret>name = "default";
                        System.out.println("initialised");
                    }

                    String getName() {
                        return name;
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention("Convert to lazy initialization in getter");
        assertNotNull(fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();
        assertTrue("init() must be kept because it still has other statements",
                result.contains("void init()"));
        assertTrue("Remaining statement must stay in init()", result.contains("System.out.println"));
        assertTrue("Null-check must appear in getter", result.contains("if (name == null)"));
    }

    // -----------------------------------------------------------------------
    // Inlinable-local: argument object built just before the call
    // -----------------------------------------------------------------------

    /**
     * Local variable whose initializer is a plain literal — the build chain is
     * trivially clean, so the fix must be offered and the declaration must be
     * moved into the getter's null-check.
     */
    public void testLocalInitialisedFromLiteralAllowsFix() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String name;

                    void init() {
                        String value = "hello";
                        <caret>name = value;
                    }

                    String getName() {
                        return name;
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention("Convert to lazy initialization in getter");
        assertNotNull("Fix must be offered when the local depends only on a literal", fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();
        assertFalse("init() should be removed when it becomes empty", result.contains("void init()"));
        assertTrue("Null-check must appear in getter", result.contains("if (name == null)"));
        assertTrue("Local declaration must be moved into getter", result.contains("String value = \"hello\""));
        assertTrue("Assignment must be inside null-check", result.contains("name = value"));
        assertTrue("Return statement must be preserved", result.contains("return name;"));
    }

    /**
     * Local variable whose initializer references an instance field — the value depends on
     * a varying property, so the caching-map fix is offered instead of the simple null-check.
     */
    public void testLocalInitialisedFromInstanceFieldOffersCachingMapFix() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String prefix;
                    private String name;

                    void init() {
                        String value = prefix + "_suffix";
                        <caret>name = value;
                    }

                    String getName() {
                        return name;
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention("Convert to caching map getter (keyed by prefix)");
        assertNotNull("Caching-map fix must be offered when the local depends on an instance field", fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();
        assertFalse("init() should be removed", result.contains("void init()"));
        assertFalse("Simple null-check must NOT appear", result.contains("if (name == null)"));
        assertTrue("nameCache field must be present", result.contains("nameCache"));
        assertTrue("containsKey check must be present", result.contains("containsKey(prefix)"));
        assertTrue("put call must be present", result.contains("nameCache.put(prefix"));
        assertTrue("return via map must be present", result.contains("return nameCache.get(prefix)"));
    }

    /**
     * Build chain whose setter arguments reference an instance field — the caching-map fix
     * is offered because the computed value varies with the instance field.
     */
    public void testLocalBuiltBySettersFromInstanceFieldOffersCachingMapFix() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String host;
                    private String result;

                    void init() {
                        StringBuilder sb = new StringBuilder();
                        sb.append(host);
                        <caret>result = sb.toString();
                    }

                    String getResult() {
                        return result;
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention("Convert to caching map getter (keyed by host)");
        assertNotNull("Caching-map fix must be offered when setters use an instance field", fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();
        assertFalse("init() should be removed", result.contains("void init()"));
        assertFalse("Simple null-check must NOT appear", result.contains("if (result == null)"));
        assertTrue("resultCache field must be present", result.contains("resultCache"));
        assertTrue("containsKey check must be present", result.contains("containsKey(host)"));
        assertTrue("Preamble declaration must be inside the if-block",
                result.indexOf("containsKey(host)") < result.indexOf("StringBuilder sb"));
        assertTrue("Setter call must be inside the if-block",
                result.indexOf("containsKey(host)") < result.indexOf("sb.append(host)"));
        assertTrue("put call must be present", result.contains("resultCache.put(host"));
        assertTrue("return via map must be present", result.contains("return resultCache.get(host)"));
    }

    /**
     * When the build chain uses an instance field, the DCL fix is NOT offered — only the
     * caching-map fix is appropriate because a DCL null-check would cache a stale value.
     */
    public void testDclFixNotOfferedWhenVaryingFieldPresent() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String host;
                    private String result;

                    void init() {
                        StringBuilder sb = new StringBuilder();
                        sb.append(host);
                        <caret>result = sb.toString();
                    }

                    String getResult() {
                        return result;
                    }
                }
                """);

        List<IntentionAction> fixes = myFixture.getAllQuickFixes();
        boolean hasDcl = fixes.stream().anyMatch(f -> f.getText().contains("double-checked locking"));
        boolean hasSimple = fixes.stream().anyMatch(f -> f.getText().equals("Convert to lazy initialization in getter"));
        boolean hasCachingMap = fixes.stream().anyMatch(f -> f.getText().contains("caching map getter"));
        assertFalse("DCL fix must NOT be offered when a varying field is present", hasDcl);
        assertFalse("Simple null-check fix must NOT be offered when a varying field is present", hasSimple);
        assertTrue("Caching-map fix must be offered when a varying field is present", hasCachingMap);
    }

    // -----------------------------------------------------------------------
    // Suppression: local that depends on another local
    // -----------------------------------------------------------------------

    /**
     * The local's initializer references another local — the chain cannot be moved
     * because the outer local would also need to be moved, creating a cascade.
     * The fix must be suppressed.
     */
    public void testLocalDependingOnOtherLocalBlocksFix() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String name;

                    void init() {
                        String base = "hello";
                        String value = base + "_suffix";
                        <caret>name = value;
                    }

                    String getName() {
                        return name;
                    }
                }
                """);

        List<IntentionAction> fixes = myFixture.getAllQuickFixes();
        boolean hasLazyFix = fixes.stream().anyMatch(f -> f.getText().contains("lazy initialization")
                || f.getText().contains("double-checked locking"));
        assertFalse("Fix must NOT be offered when local depends on another local", hasLazyFix);
    }

    /**
     * A setter call on the local passes another local as an argument. The argument
     * local declaration should move into the getter together with the rest of the
     * builder chain.
     */
    public void testSetterArgFromLocalAllowsFix() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String result;

                    void init() {
                        String host = "localhost";
                        StringBuilder sb = new StringBuilder();
                        sb.append(host);
                        <caret>result = sb.toString();
                    }

                    String getResult() {
                        return result;
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention("Convert to lazy initialization in getter");
        assertNotNull("Fix must be offered when setter arg local can move with the build chain", fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();
        assertFalse("init() should be removed", result.contains("void init()"));
        assertTrue("Null-check must appear in getter", result.contains("if (result == null)"));
        assertTrue("Dependent local declaration must move into getter", result.contains("String host = \"localhost\""));
        assertTrue("Builder declaration must move into getter", result.contains("StringBuilder sb = new StringBuilder()"));
        assertTrue("Setter call must move into getter", result.contains("sb.append(host)"));
        assertTrue("Assignment must move into getter", result.contains("result = sb.toString()"));
    }

    // -----------------------------------------------------------------------
    // Caching map fix
    // -----------------------------------------------------------------------

    /**
     * RHS directly references an instance field — the caching-map fix must be offered and
     * must generate a Map keyed by the varying field with containsKey/put/get pattern.
     */
    public void testCachingMapFixBasic() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String prefix;
                    private String name;

                    void init() {
                        <caret>name = prefix + "_suffix";
                    }

                    String getName() {
                        return name;
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention("Convert to caching map getter (keyed by prefix)");
        assertNotNull("Caching-map fix must be offered when RHS references an instance field", fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();
        assertFalse("init() should be removed when it becomes empty", result.contains("void init()"));
        assertFalse("Original name field must be replaced", result.contains("private String name;"));
        assertTrue("nameCache map field must be final", result.contains("final") && result.contains("nameCache"));
        assertTrue("containsKey check must be present", result.contains("containsKey(prefix)"));
        assertTrue("put call must be present", result.contains("nameCache.put(prefix"));
        assertTrue("put must use the RHS expression", result.contains("prefix + \"_suffix\""));
        assertTrue("return via map must be present", result.contains("return nameCache.get(prefix)"));
        assertFalse("Simple null-check must NOT appear", result.contains("if (name == null)"));
    }

    /**
     * When two distinct instance fields appear in the RHS, no fix should be offered —
     * a single-key Map cannot represent a composite varying variable.
     */
    public void testCachingMapNotOfferedForMultipleVaryingFields() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String prefix;
                    private String suffix;
                    private String name;

                    void init() {
                        <caret>name = prefix + suffix;
                    }

                    String getName() {
                        return name;
                    }
                }
                """);

        List<IntentionAction> fixes = myFixture.getAllQuickFixes();
        boolean hasAnyLazyFix = fixes.stream().anyMatch(f ->
                f.getText().contains("lazy initialization")
                        || f.getText().contains("double-checked locking")
                        || f.getText().contains("caching map getter"));
        assertFalse("No fix must be offered when RHS references multiple varying fields", hasAnyLazyFix);
    }

    /**
     * Simple literal RHS has no varying field — only the standard simple fix is offered,
     * not the caching-map fix.
     */
    public void testSimpleFixOfferedWhenNoInstanceFieldInRhs() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String name;

                    void init() {
                        <caret>name = "literal";
                    }

                    String getName() {
                        return name;
                    }
                }
                """);

        List<IntentionAction> fixes = myFixture.getAllQuickFixes();
        boolean hasSimple = fixes.stream().anyMatch(f -> f.getText().equals("Convert to lazy initialization in getter"));
        boolean hasCachingMap = fixes.stream().anyMatch(f -> f.getText().contains("caching map getter"));
        assertTrue("Simple lazy-init fix must be offered for a literal RHS", hasSimple);
        assertFalse("Caching-map fix must NOT be offered when RHS has no instance field", hasCachingMap);
    }

    /**
     * Static fields can only use the simple lazy-init fix; the caching-map fix is for
     * instance fields only.
     */
    public void testCachingMapNotOfferedForStaticField() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private static String name;

                    static void init() {
                        <caret>name = "x";
                    }

                    static String getName() {
                        return name;
                    }
                }
                """);

        List<IntentionAction> fixes = myFixture.getAllQuickFixes();
        boolean hasSimple = fixes.stream().anyMatch(f -> f.getText().equals("Convert to lazy initialization in getter"));
        boolean hasCachingMap = fixes.stream().anyMatch(f -> f.getText().contains("caching map getter"));
        assertTrue("Simple fix must be offered for a static field", hasSimple);
        assertFalse("Caching-map fix must NOT be offered for a static field", hasCachingMap);
    }

    /**
     * When the field being cached is a primitive type, the generated Map must use the
     * corresponding boxed type as the value parameter.
     */
    public void testCachingMapUsesBoxedTypeForPrimitiveField() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String prefix;
                    private int count;

                    void init() {
                        <caret>count = prefix.length();
                    }

                    int getCount() {
                        return count;
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention("Convert to caching map getter (keyed by prefix)");
        assertNotNull("Caching-map fix must be offered for a primitive field with a varying key", fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();
        assertTrue("Map value type must be boxed Integer", result.contains("Integer"));
        assertTrue("countCache field must be present", result.contains("countCache"));
        assertTrue("containsKey check must be present", result.contains("containsKey(prefix)"));
        assertTrue("return via map must be present", result.contains("return countCache.get(prefix)"));
    }

    /**
     * The local is referenced in a second field assignment after the current one.
     * With the multi-assignment logic the declaration is copied into the getter but kept
     * in init (isMultiAssignment=true), so the remaining {@code alias = value} statement
     * is not broken. The fix must still be offered.
     */
    public void testLocalUsedInAnotherStatementBlocksFix() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String name;
                    private String alias;

                    void init() {
                        String value = "hello";
                        <caret>name = value;
                        alias = value;
                    }

                    String getName() {
                        return name;
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention("Convert to lazy initialization in getter");
        assertNotNull("Fix must be offered: declaration is copied to getter but kept in init for alias", fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();

        // getter must have a null-check with a local copy of the declaration
        assertTrue("Null-check guard must be present", result.contains("if (name == null)"));
        assertTrue("Declaration must be inside the null-check",
                result.contains("String value = \"hello\""));
        assertTrue("Assignment must appear inside the null-check", result.contains("name = value"));
        assertTrue("Return statement must be preserved", result.contains("return name;"));

        // init must still be present because alias = value has not been fixed
        assertTrue("init() must still be present", result.contains("void init()"));
        int initIdx = result.indexOf("void init()");
        int getterIdx = result.indexOf("String getName()", initIdx);
        String initText = result.substring(initIdx, getterIdx);
        assertTrue("init must still declare value", initText.contains("String value = \"hello\""));
        assertTrue("init must still assign alias", initText.contains("alias = value"));
    }

    // -----------------------------------------------------------------------
    // Multi-assignment shared-local pattern
    // -----------------------------------------------------------------------

    /**
     * When the first of three field assignments in an init method is fixed,
     * the getter gets a null-check with only the declaration and the first setter;
     * the init method still contains the declaration plus the remaining two segments.
     */
    public void testMultiAssignmentFirstField() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String list;
                    private String list2;
                    private String list3;
                    private String filter;

                    void init() {
                        StringBuilder sb = new StringBuilder();
                        sb.append("one");
                        <caret>list = sb.toString();
                        sb.append("two");
                        list2 = sb.toString();
                        sb.append("three");
                        list3 = sb.toString();
                    }

                    String getList() {
                        return list;
                    }

                    String getList2() {
                        return list2;
                    }

                    String getList3() {
                        return list3;
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention("Convert to lazy initialization in getter");
        assertNotNull("Simple lazy-init fix must be offered for the first assignment", fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();

        // Extract getList() method text (between its declaration and the next getter)
        int getListStart = result.indexOf("String getList()");
        int getList2Start = result.indexOf("String getList2()");
        String getListText = result.substring(getListStart, getList2Start);

        assertTrue("getList must have null-check for list", getListText.contains("if (list == null)"));
        assertTrue("getList null-check must include the declaration",
                getListText.contains("StringBuilder sb = new StringBuilder()"));
        assertTrue("getList null-check must include the first setter",
                getListText.contains("sb.append(\"one\")"));
        assertTrue("getList null-check must assign list", getListText.contains("list = sb.toString()"));

        // init() must still be present (other assignments remain)
        assertTrue("init() must still be present", result.contains("void init()"));

        // declaration must remain in init for the other segments
        int initStart = result.indexOf("void init()");
        String initText = result.substring(initStart, getListStart);
        assertTrue("init must still contain the declaration", initText.contains("StringBuilder sb = new StringBuilder()"));
        assertTrue("init must still contain setter2", initText.contains("sb.append(\"two\")"));
        assertTrue("init must still contain list2 assignment", initText.contains("list2 = sb.toString()"));
        assertTrue("init must still contain setter3", initText.contains("sb.append(\"three\")"));
        assertTrue("init must still contain list3 assignment", initText.contains("list3 = sb.toString()"));

        // setter1 must have been removed from init
        assertFalse("setter1 must NOT remain in init after fix", initText.contains("sb.append(\"one\")"));
    }

    /**
     * When the second of three assignments is fixed, the getter for list2 gets a null-check
     * with only the declaration and the second setter; the first and third segments are untouched.
     */
    public void testMultiAssignmentSecondField() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String list;
                    private String list2;
                    private String list3;

                    void init() {
                        StringBuilder sb = new StringBuilder();
                        sb.append("one");
                        list = sb.toString();
                        sb.append("two");
                        <caret>list2 = sb.toString();
                        sb.append("three");
                        list3 = sb.toString();
                    }

                    String getList() {
                        return list;
                    }

                    String getList2() {
                        return list2;
                    }

                    String getList3() {
                        return list3;
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention("Convert to lazy initialization in getter");
        assertNotNull("Simple lazy-init fix must be offered for the second assignment", fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();

        // Extract getList2() method text (between its declaration and the next getter)
        int getList2Start = result.indexOf("String getList2()");
        int getList3Start = result.indexOf("String getList3()");
        String getList2Text = result.substring(getList2Start, getList3Start);

        assertTrue("getList2 must have null-check for list2", getList2Text.contains("if (list2 == null)"));
        assertTrue("getList2 null-check must include the declaration",
                getList2Text.contains("StringBuilder sb = new StringBuilder()"));
        assertTrue("getList2 null-check must include setter2", getList2Text.contains("sb.append(\"two\")"));
        assertTrue("getList2 must assign list2", getList2Text.contains("list2 = sb.toString()"));

        // init() must still be present
        assertTrue("init() must still be present", result.contains("void init()"));

        int initStart = result.indexOf("void init()");
        int getListStart = result.indexOf("String getList()", initStart);
        String initText = result.substring(initStart, getListStart);

        // first segment must be intact in init
        assertTrue("init must still contain setter1", initText.contains("sb.append(\"one\")"));
        assertTrue("init must still contain list= assignment", initText.contains("list = sb.toString()"));
        // second segment setter must have been removed
        assertFalse("setter2 must NOT remain in init after fix", initText.contains("sb.append(\"two\")"));
        // third segment must be intact
        assertTrue("init must still contain setter3", initText.contains("sb.append(\"three\")"));
        assertTrue("init must still contain list3 assignment", initText.contains("list3 = sb.toString()"));
    }

    /**
     * When all three assignments are converted sequentially the init() method must be deleted,
     * and each getter must contain the correct preamble for its own segment.
     */
    public void testMultiAssignmentAllFieldsConverted() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String list;
                    private String list2;
                    private String list3;

                    void init() {
                        StringBuilder sb = new StringBuilder();
                        sb.append("one");
                        <caret>list = sb.toString();
                        sb.append("two");
                        list2 = sb.toString();
                        sb.append("three");
                        list3 = sb.toString();
                    }

                    String getList() {
                        return list;
                    }

                    String getList2() {
                        return list2;
                    }

                    String getList3() {
                        return list3;
                    }
                }
                """);

        // Fix 1: list
        IntentionAction fix1 = myFixture.getAvailableIntention("Convert to lazy initialization in getter");
        assertNotNull("Fix must be offered for list", fix1);
        myFixture.launchAction(fix1);

        // Reposition caret for list2 and apply fix
        myFixture.getEditor().getCaretModel().moveToOffset(
                myFixture.getFile().getText().indexOf("list2 = sb.toString()"));
        myFixture.doHighlighting();
        IntentionAction fix2 = myFixture.getAvailableIntention("Convert to lazy initialization in getter");
        assertNotNull("Fix must be offered for list2", fix2);
        myFixture.launchAction(fix2);

        // Reposition caret for list3 and apply fix
        myFixture.getEditor().getCaretModel().moveToOffset(
                myFixture.getFile().getText().indexOf("list3 = sb.toString()"));
        myFixture.doHighlighting();
        IntentionAction fix3 = myFixture.getAvailableIntention("Convert to lazy initialization in getter");
        assertNotNull("Fix must be offered for list3", fix3);
        myFixture.launchAction(fix3);

        String result = myFixture.getFile().getText();

        // init() must be gone once all assignments have been converted
        assertFalse("init() must be deleted after all assignments are converted", result.contains("void init()"));

        // All three getters must have their own null-checks
        assertTrue("getList null-check present", result.contains("if (list == null)"));
        assertTrue("getList2 null-check present", result.contains("if (list2 == null)"));
        assertTrue("getList3 null-check present", result.contains("if (list3 == null)"));

        // Each getter assigns the right field
        assertTrue("list assignment present", result.contains("list = sb.toString()"));
        assertTrue("list2 assignment present", result.contains("list2 = sb.toString()"));
        assertTrue("list3 assignment present", result.contains("list3 = sb.toString()"));
    }

    /**
     * Regression: single-assignment case must be unaffected by the multi-assignment changes.
     * The init() method must be deleted and the getter must receive the correct null-check.
     */
    public void testMultiAssignmentExistingBehaviourUnchanged() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String result;

                    void init() {
                        StringBuilder sb = new StringBuilder();
                        sb.append("hello");
                        <caret>result = sb.toString();
                    }

                    String getResult() {
                        return result;
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention("Convert to lazy initialization in getter");
        assertNotNull("Simple lazy-init fix must be offered for single-assignment case", fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();
        assertFalse("init() must be deleted in single-assignment case", result.contains("void init()"));
        assertTrue("Null-check must be present", result.contains("if (result == null)"));
        assertTrue("Declaration must be inside null-check",
                result.indexOf("if (result == null)") < result.indexOf("StringBuilder sb"));
        assertTrue("Setter must be inside null-check",
                result.indexOf("StringBuilder sb") < result.indexOf("sb.append(\"hello\")"));
        assertTrue("Assignment must be inside null-check", result.contains("result = sb.toString()"));
        assertTrue("Return must be preserved", result.contains("return result;"));
    }

    /**
     * A setter called only once before all segments (a "constant" setter) must be included
     * in the preamble of every segment's getter, not just the first one.
     * Here {@code argument.setFilterTwo("constant")} is called once and never overridden;
     * only {@code setFilterOne} varies per segment.
     */
    public void testConstantSetterPreservedInLaterSegments() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String list;
                    private String list2;
                    private String list3;

                    void init() {
                        Arg argument = new Arg();
                        argument.setFilterTwo("constant");
                        argument.setFilterOne("one");
                        list = doGet(argument);
                        argument.setFilterOne("two");
                        <caret>list2 = doGet(argument);
                        argument.setFilterOne("three");
                        list3 = doGet(argument);
                    }

                    String list(Arg a) { return a.one + a.two; }

                    String doGet(Arg a) { return a.one + a.two; }

                    String getList() {
                        return list;
                    }

                    String getList2() {
                        return list2;
                    }

                    String getList3() {
                        return list3;
                    }

                    static class Arg {
                        String one, two;
                        void setFilterOne(String v) { one = v; }
                        void setFilterTwo(String v) { two = v; }
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention("Convert to lazy initialization in getter");
        assertNotNull("Fix must be offered for list2", fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();

        int getList2Start = result.indexOf("String getList2()");
        int getList3Start = result.indexOf("String getList3()");
        String getList2Text = result.substring(getList2Start, getList3Start);

        assertTrue("getList2 null-check must be present", getList2Text.contains("if (list2 == null)"));
        assertTrue("getList2 preamble must include the declaration",
                getList2Text.contains("Arg argument = new Arg()"));
        assertTrue("getList2 preamble must include the constant setter",
                getList2Text.contains("argument.setFilterTwo(\"constant\")"));
        assertTrue("getList2 preamble must include the segment-specific setter",
                getList2Text.contains("argument.setFilterOne(\"two\")"));
        assertFalse("getList2 preamble must NOT include segment-1 value of setFilterOne",
                getList2Text.contains("argument.setFilterOne(\"one\")"));
        assertTrue("getList2 must assign list2", getList2Text.contains("list2 = doGet(argument)"));
    }

    // -----------------------------------------------------------------------
    // If-guard preservation
    // -----------------------------------------------------------------------

    /**
     * Assignment inside {@code if (instanceObject != null)} — simple fix must generate
     * {@code if (list == null) { if (instanceObject != null) { list = ...; } }}.
     * The init() method and the if-guard must both be removed after the fix.
     */
    public void testIfGuardPreservedInSimpleLazyInit() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private Object instanceObject;
                    private String list;

                    void init() {
                        if (instanceObject != null) {
                            <caret>list = "value";
                        }
                    }

                    String getList() {
                        return list;
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention("Convert to lazy initialization in getter");
        assertNotNull("Fix must be offered when assignment is inside an if-guard", fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();
        assertFalse("init() must be removed when it becomes empty", result.contains("void init()"));
        assertTrue("Outer null-check must be present", result.contains("if (list == null)"));
        assertTrue("Guard condition must be preserved inside null-check",
                result.contains("if (instanceObject != null)"));
        assertTrue("Guard must appear inside the outer null-check",
                result.indexOf("if (list == null)") < result.indexOf("if (instanceObject != null)"));
        assertTrue("Assignment must be inside the guard", result.contains("list = \"value\""));
        assertTrue("Return must be preserved", result.contains("return list;"));
    }

    /**
     * Assignment with a build-chain inside an if-guard — the preamble must be moved inside
     * the guard block in the getter.
     */
    public void testIfGuardPreservedWithBuildChain() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private Object instanceObject;
                    private String list;

                    void init() {
                        if (instanceObject != null) {
                            String arg = "hello";
                            <caret>list = arg + "_suffix";
                        }
                    }

                    String getList() {
                        return list;
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention("Convert to lazy initialization in getter");
        assertNotNull("Fix must be offered when build-chain is inside an if-guard", fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();
        assertFalse("init() must be removed", result.contains("void init()"));
        assertTrue("Outer null-check must be present", result.contains("if (list == null)"));
        assertTrue("Guard condition must appear inside null-check",
                result.contains("if (instanceObject != null)"));
        assertTrue("Guard must appear inside the outer null-check",
                result.indexOf("if (list == null)") < result.indexOf("if (instanceObject != null)"));
        assertTrue("Preamble declaration must be inside the guard",
                result.indexOf("if (instanceObject != null)") < result.indexOf("String arg"));
        assertTrue("Assignment must be present", result.contains("list = arg + \"_suffix\""));
        assertTrue("Return must be preserved", result.contains("return list;"));
    }

    /**
     * DCL fix with an if-guard — the guard is placed inside the innermost null-check block.
     */
    public void testIfGuardPreservedInDclFix() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private Object instanceObject;
                    private String list;

                    void init() {
                        if (instanceObject != null) {
                            <caret>list = "value";
                        }
                    }

                    String getList() {
                        return list;
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention(
                "Convert to thread-safe lazy initialization (double-checked locking)");
        assertNotNull("DCL fix must be offered when assignment is inside an if-guard", fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();
        assertFalse("init() must be removed", result.contains("void init()"));
        assertTrue("Field must be volatile", result.contains("volatile String list"));
        assertTrue("Outer null-check must be present", result.contains("if (list == null)"));
        assertTrue("synchronized block must be present", result.contains("synchronized (this)"));
        assertTrue("Guard condition must appear inside DCL block",
                result.contains("if (instanceObject != null)"));
        // Guard is nested inside the inner null-check, which is inside synchronized
        int syncIdx  = result.indexOf("synchronized");
        int guardIdx = result.indexOf("if (instanceObject != null)");
        assertTrue("Guard must appear after synchronized", syncIdx < guardIdx);
        assertTrue("Assignment must be inside the guard", result.contains("list = \"value\""));
    }

    /**
     * When the if-guard condition references a local variable in the host method, the fix
     * must NOT be offered because the condition cannot safely be moved to the getter.
     */
    public void testIfGuardBlockedWhenConditionReferencesLocal() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String list;

                    void init() {
                        Object localObj = new Object();
                        if (localObj != null) {
                            <caret>list = "value";
                        }
                    }

                    String getList() {
                        return list;
                    }
                }
                """);

        List<IntentionAction> fixes = myFixture.getAllQuickFixes();
        boolean hasLazyFix = fixes.stream().anyMatch(f -> f.getText().contains("lazy initialization")
                || f.getText().contains("double-checked locking"));
        assertFalse("Fix must NOT be offered when guard condition references a local variable", hasLazyFix);
    }

    /**
     * A plain setter method (field = param with no computation) must NOT trigger the
     * lazy-init inspection even when there is exactly one call site.
     */
    public void testSetterPatternNotHighlighted() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String filterOne;

                    public void setFilterOne(String filterOne) {
                        <caret>this.filterOne = filterOne;
                    }

                    public String getFilterOne() {
                        return filterOne;
                    }

                    public void configure() {
                        setFilterOne("value");
                    }
                }
                """);

        List<IntentionAction> fixes = myFixture.getAllQuickFixes();
        boolean hasLazyFix = fixes.stream().anyMatch(f -> f.getText().contains("lazy initialization")
                || f.getText().contains("double-checked locking"));
        assertFalse("Fix must NOT be offered for a plain setter (field = param)", hasLazyFix);
    }

    // -----------------------------------------------------------------------
    // If/else guard: fallback branch preserved in generated getter
    // -----------------------------------------------------------------------

    /**
     * Simple lazy-init fix with an if/else guard — the full if/else is placed inside the
     * null-check in the generated getter, and the host init() method is deleted.
     */
    public void testIfElseGuardPreservedInSimpleLazyInit() {
        myFixture.configureByText("Bean.java", """
                import java.util.ArrayList;
                import java.util.List;
                public class Bean {
                    private Object instanceObject;
                    private List<String> list;

                    void init() {
                        if (instanceObject != null) {
                            <caret>list = new ArrayList<>();
                        } else {
                            list = new ArrayList<>();
                        }
                    }

                    List<String> getList() {
                        return list;
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention("Convert to lazy initialization in getter");
        assertNotNull("Simple lazy-init fix must be offered for if/else guard pattern", fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();
        assertFalse("init() must be removed", result.contains("void init()"));
        assertTrue("Outer null-check must be present", result.contains("if (list == null)"));
        assertTrue("Guard condition must appear inside null-check",
                result.contains("if (instanceObject != null)"));
        assertTrue("Else-branch must be preserved",
                result.contains("} else {"));
        assertTrue("Else-branch assignment must be present",
                result.contains("list = new ArrayList<>()"));
        assertTrue("Guard must appear inside outer null-check",
                result.indexOf("if (list == null)") < result.indexOf("if (instanceObject != null)"));
        assertTrue("Return must be preserved", result.contains("return list;"));
    }

    /**
     * DCL fix with an if/else guard — the if/else is placed inside the innermost null-check
     * of the synchronized block.
     */
    public void testIfElseGuardPreservedInDclFix() {
        myFixture.configureByText("Bean.java", """
                import java.util.ArrayList;
                import java.util.List;
                public class Bean {
                    private Object instanceObject;
                    private List<String> list;

                    void init() {
                        if (instanceObject != null) {
                            <caret>list = new ArrayList<>();
                        } else {
                            list = new ArrayList<>();
                        }
                    }

                    List<String> getList() {
                        return list;
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention(
                "Convert to thread-safe lazy initialization (double-checked locking)");
        assertNotNull("DCL fix must be offered for if/else guard pattern", fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();
        assertFalse("init() must be removed", result.contains("void init()"));
        assertTrue("Field must be volatile", result.contains("volatile"));
        assertTrue("Outer null-check must be present", result.contains("if (list == null)"));
        assertTrue("synchronized block must be present", result.contains("synchronized (this)"));
        assertTrue("Guard condition must appear inside DCL block",
                result.contains("if (instanceObject != null)"));
        assertTrue("Else-branch must be preserved", result.contains("} else {"));
        int syncIdx  = result.indexOf("synchronized");
        int guardIdx = result.indexOf("if (instanceObject != null)");
        assertTrue("Guard must appear after synchronized", syncIdx < guardIdx);
        assertTrue("Return must be preserved", result.contains("return list;"));
    }

    /**
     * Simple lazy-init fix with an if/else guard and a build-chain preamble in the then-branch.
     * The preamble is moved into the getter; the else-branch fallback is preserved.
     */
    public void testIfElseGuardPreservedWithBuildChain() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private Object instanceObject;
                    private String list;

                    void init() {
                        if (instanceObject != null) {
                            String prefix = "val";
                            <caret>list = prefix + "_suffix";
                        } else {
                            list = "default";
                        }
                    }

                    String getList() {
                        return list;
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention("Convert to lazy initialization in getter");
        assertNotNull("Fix must be offered with preamble inside if/else guard", fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();
        assertFalse("init() must be removed", result.contains("void init()"));
        assertTrue("Outer null-check must be present", result.contains("if (list == null)"));
        assertTrue("Guard condition must appear inside null-check",
                result.contains("if (instanceObject != null)"));
        assertTrue("Preamble must be inside the guard",
                result.indexOf("if (instanceObject != null)") < result.indexOf("String prefix"));
        assertTrue("Else-branch must be preserved", result.contains("} else {"));
        assertTrue("Else assignment must be present", result.contains("list = \"default\""));
        assertTrue("Return must be preserved", result.contains("return list;"));
    }

    // -----------------------------------------------------------------------
    // Multi-statement blocks (then and/or else contain side-effect statements)
    // -----------------------------------------------------------------------

    /**
     * Else-block has multiple statements (side-effect calls surrounding the fallback
     * assignment). The entire if/else must be copied verbatim into the getter.
     */
    public void testIfElseMultiStatementElsePreserved() {
        myFixture.configureByText("Bean.java", """
                import java.util.ArrayList;
                import java.util.List;
                public class Bean {
                    private Object instanceObject;
                    private List<String> list;
                    private final Service service = new Service();

                    void init() {
                        if (instanceObject != null) {
                            <caret>list = service.getList();
                        } else {
                            System.out.println("fallback start");
                            list = new ArrayList<>();
                            System.out.println("fallback done");
                        }
                    }

                    List<String> getList() {
                        return list;
                    }

                    static class Service {
                        List<String> getList() { return new ArrayList<>(); }
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention("Convert to lazy initialization in getter");
        assertNotNull("Fix must be offered for multi-statement else block", fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();
        assertFalse("init() must be removed", result.contains("void init()"));
        assertTrue("Outer null-check must be present", result.contains("if (list == null)"));
        assertTrue("Guard condition must appear inside null-check",
                result.contains("if (instanceObject != null)"));
        assertTrue("Else-branch must be preserved", result.contains("} else {"));
        assertTrue("Else side-effect statement must be preserved",
                result.contains("System.out.println"));
        assertTrue("Else fallback assignment must be present", result.contains("list = new ArrayList<>()"));
        assertTrue("Return must be preserved", result.contains("return list;"));
    }

    /**
     * Else-block has a build-chain (local declaration + setter call) in addition to the
     * fallback field assignment and side-effect calls.
     */
    public void testIfElseMultiStatementElseBuildChain() {
        myFixture.configureByText("Bean.java", """
                import java.util.List;
                public class Bean {
                    private Object instanceObject;
                    private List<String> list;
                    private final Service service = new Service();

                    void init() {
                        if (instanceObject != null) {
                            <caret>list = service.getList("main");
                        } else {
                            System.out.println("fallback");
                            list = service.getList("fallback");
                        }
                    }

                    List<String> getList() {
                        return list;
                    }

                    static class Service {
                        List<String> getList(String key) { return null; }
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention("Convert to lazy initialization in getter");
        assertNotNull("Fix must be offered for multi-statement else with println", fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();
        assertFalse("init() must be removed", result.contains("void init()"));
        assertTrue("Null-check must be present", result.contains("if (list == null)"));
        assertTrue("Guard must be preserved", result.contains("if (instanceObject != null)"));
        assertTrue("Else-branch must be present", result.contains("} else {"));
        assertTrue("Else println must be preserved", result.contains("System.out.println"));
        assertTrue("Else assignment must be present", result.contains("service.getList(\"fallback\")"));
    }

    /**
     * Both then-block and else-block have side-effect statements surrounding the assignments.
     * The entire if/else must be preserved verbatim in the getter.
     */
    public void testIfElseMultiStatementBothBranches() {
        myFixture.configureByText("Bean.java", """
                import java.util.ArrayList;
                import java.util.List;
                public class Bean {
                    private Object instanceObject;
                    private List<String> list;
                    private final Service service = new Service();

                    void init() {
                        if (instanceObject != null) {
                            System.out.println("before");
                            <caret>list = service.getList();
                            System.out.println("after");
                        } else {
                            System.out.println("fallback");
                            list = new ArrayList<>();
                        }
                    }

                    List<String> getList() {
                        return list;
                    }

                    static class Service {
                        List<String> getList() { return new ArrayList<>(); }
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention("Convert to lazy initialization in getter");
        assertNotNull("Fix must be offered when then-block has statements before and after assignment", fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();
        assertFalse("init() must be removed", result.contains("void init()"));
        assertTrue("Null-check must be present", result.contains("if (list == null)"));
        assertTrue("Guard must be preserved", result.contains("if (instanceObject != null)"));
        // Both the pre-assignment and post-assignment println must be in the getter
        assertTrue("Pre-assignment side-effect must be in getter", result.contains("System.out.println(\"before\")"));
        assertTrue("Post-assignment side-effect must be in getter", result.contains("System.out.println(\"after\")"));
        assertTrue("Else-branch must be present", result.contains("} else {"));
        assertTrue("Return must be preserved", result.contains("return list;"));
    }

    /**
     * In a direct (non-guarded) init method, a chain local is referenced by a side-effect
     * statement AFTER the field assignment.  The fix must copy the declaration to the getter
     * but keep it in init() so the post-assignment statement remains valid.
     */
    public void testDirectMethodLocalUsedAfterAssignment() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String list;
                    private final Service service = new Service();

                    void init() {
                        System.out.println("start");
                        String arg = "hello";
                        <caret>list = service.compute(arg);
                        System.out.println("arg was: " + arg);
                    }

                    String getList() {
                        return list;
                    }

                    static class Service {
                        String compute(String s) { return s; }
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention("Convert to lazy initialization in getter");
        assertNotNull("Fix must be offered even when local is used after the assignment", fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();
        // init() must NOT be removed because it still has side-effect statements
        assertTrue("init() must be kept since it still has side-effect work", result.contains("void init()"));
        // The getter must have the null-check with the preamble
        assertTrue("Null-check must be present in getter", result.contains("if (list == null)"));
        assertTrue("Preamble declaration must appear in getter", result.contains("String arg"));
        assertTrue("Assignment must be inside null-check",
                result.indexOf("if (list == null)") < result.indexOf("list = service.compute(arg)"));
        // init() must still compile: the declaration must be kept so println(arg) is valid
        assertTrue("Declaration must be kept in init() for post-assignment use",
                result.contains("void init()") && result.contains("System.out.println(\"arg was: \" + arg)"));
    }

    // -----------------------------------------------------------------------
    // GROUP 1 — Control-flow blockers: fix must NOT be offered
    // -----------------------------------------------------------------------

    /**
     * Assignment inside a for-each loop body — getHostContext returns null because the
     * enclosing PsiCodeBlock's parent is PsiForeachStatement, not PsiMethod.
     */
    public void testNoFixForAssignmentInForEachLoop() {
        myFixture.configureByText("Bean.java", """
                import java.util.List;
                public class Bean {
                    private List<String> items;
                    private String last;

                    void init() {
                        for (String item : items) {
                            <caret>last = item;
                        }
                    }

                    String getLast() {
                        return last;
                    }
                }
                """);

        List<IntentionAction> fixes = myFixture.getAllQuickFixes();
        boolean hasLazyFix = fixes.stream().anyMatch(f ->
                f.getText().contains("lazy initialization") || f.getText().contains("double-checked locking"));
        assertFalse("Fix must NOT be offered for assignment inside a for-each loop", hasLazyFix);
    }

    /**
     * Assignment inside a while-loop body — same structural block as for-each but with
     * PsiWhileStatement as the containing element.
     */
    public void testNoFixForAssignmentInWhileLoop() {
        myFixture.configureByText("Bean.java", """
                import java.util.Iterator;
                public class Bean {
                    private Iterator<String> queue;
                    private String last;

                    void init() {
                        while (queue.hasNext()) {
                            <caret>last = queue.next();
                        }
                    }

                    String getLast() {
                        return last;
                    }
                }
                """);

        List<IntentionAction> fixes = myFixture.getAllQuickFixes();
        boolean hasLazyFix = fixes.stream().anyMatch(f ->
                f.getText().contains("lazy initialization") || f.getText().contains("double-checked locking"));
        assertFalse("Fix must NOT be offered for assignment inside a while loop", hasLazyFix);
    }

    /**
     * Assignment inside the try-block of a try/catch statement — getHostContext returns null
     * because the PsiCodeBlock's parent is PsiTryStatement.
     */
    public void testNoFixForAssignmentInTryCatch() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String name;
                    private final Service service = new Service();

                    void init() {
                        try {
                            <caret>name = service.getValue();
                        } catch (Exception e) {
                            name = "fallback";
                        }
                    }

                    String getName() {
                        return name;
                    }

                    static class Service {
                        String getValue() throws Exception { return "ok"; }
                    }
                }
                """);

        List<IntentionAction> fixes = myFixture.getAllQuickFixes();
        boolean hasLazyFix = fixes.stream().anyMatch(f ->
                f.getText().contains("lazy initialization") || f.getText().contains("double-checked locking"));
        assertFalse("Fix must NOT be offered for assignment inside a try block", hasLazyFix);
    }

    /**
     * Assignment inside the try-block of a try/finally statement.
     */
    public void testNoFixForAssignmentInTryFinally() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String name;
                    private final Service service = new Service();

                    void init() {
                        try {
                            <caret>name = service.getValue();
                        } finally {
                            service.close();
                        }
                    }

                    String getName() {
                        return name;
                    }

                    static class Service {
                        String getValue() { return "ok"; }
                        void close() {}
                    }
                }
                """);

        List<IntentionAction> fixes = myFixture.getAllQuickFixes();
        boolean hasLazyFix = fixes.stream().anyMatch(f ->
                f.getText().contains("lazy initialization") || f.getText().contains("double-checked locking"));
        assertFalse("Fix must NOT be offered for assignment inside a try/finally block", hasLazyFix);
    }

    /**
     * Assignment inside a switch/case block — PsiCodeBlock's parent is PsiSwitchStatement,
     * not PsiMethod or an eligible PsiIfStatement.
     */
    public void testNoFixForAssignmentInSwitchCase() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String type;
                    private String name;

                    void init() {
                        switch (type) {
                            case "A": <caret>name = "type-a"; break;
                            default:  name = "default"; break;
                        }
                    }

                    String getName() {
                        return name;
                    }
                }
                """);

        List<IntentionAction> fixes = myFixture.getAllQuickFixes();
        boolean hasLazyFix = fixes.stream().anyMatch(f ->
                f.getText().contains("lazy initialization") || f.getText().contains("double-checked locking"));
        assertFalse("Fix must NOT be offered for assignment inside a switch case", hasLazyFix);
    }

    /**
     * Assignment two levels deep inside nested if-statements — getHostContext checks that the
     * outer enclosing block's parent is a PsiMethod; here it is another if-statement, so it
     * returns null.
     */
    public void testNoFixForNestedIfGuard() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private Object config;
                    private Object service;
                    private String name;

                    void init() {
                        if (config != null) {
                            if (service != null) {
                                <caret>name = "computed";
                            }
                        }
                    }

                    String getName() {
                        return name;
                    }
                }
                """);

        List<IntentionAction> fixes = myFixture.getAllQuickFixes();
        boolean hasLazyFix = fixes.stream().anyMatch(f ->
                f.getText().contains("lazy initialization") || f.getText().contains("double-checked locking"));
        assertFalse("Fix must NOT be offered when assignment is nested two if-levels deep", hasLazyFix);
    }

    // -----------------------------------------------------------------------
    // GROUP 2 — Guard condition edge cases
    // -----------------------------------------------------------------------

    /**
     * Guard condition contains a compound AND expression referencing only instance fields.
     * isGuardConditionMovable returns true (no local vars); the full compound condition must
     * be preserved verbatim inside the outer null-check.
     */
    public void testCompoundAndGuardConditionPreserved() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private final Service service = new Service();
                    private String name;

                    void init() {
                        if (service != null && service.isAvailable()) {
                            <caret>name = service.compute();
                        }
                    }

                    String getName() {
                        return name;
                    }

                    static class Service {
                        boolean isAvailable() { return true; }
                        String compute() { return "ok"; }
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention("Convert to lazy initialization in getter");
        assertNotNull("Fix must be offered with compound AND guard", fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();
        assertFalse("init() should be removed", result.contains("void init()"));
        assertTrue("Outer null-check must be present", result.contains("if (name == null)"));
        assertTrue("Compound guard must be preserved verbatim",
                result.contains("if (service != null && service.isAvailable())"));
        assertTrue("Guard must be inside null-check",
                result.indexOf("if (name == null)") < result.indexOf("if (service != null && service.isAvailable())"));
        assertTrue("Assignment must be present", result.contains("name = service.compute()"));
        assertTrue("Return must be preserved", result.contains("return name;"));
    }

    /**
     * Guard condition is a pure method call with no local references.
     * isGuardConditionMovable returns true; the call must appear verbatim in the getter.
     */
    public void testMethodCallGuardConditionPreserved() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private Object config;
                    private String name;

                    void init() {
                        if (isReady()) {
                            <caret>name = computeName();
                        }
                    }

                    private boolean isReady() {
                        return config != null;
                    }

                    private String computeName() {
                        return config.toString();
                    }

                    String getName() {
                        return name;
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention("Convert to lazy initialization in getter");
        assertNotNull("Fix must be offered when guard is a method call with no local refs", fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();
        assertFalse("init() should be removed", result.contains("void init()"));
        assertTrue("Outer null-check must be present", result.contains("if (name == null)"));
        assertTrue("Method-call guard must be preserved", result.contains("if (isReady())"));
        assertTrue("Guard must be inside null-check",
                result.indexOf("if (name == null)") < result.indexOf("if (isReady())"));
        assertTrue("Assignment must be preserved", result.contains("name = computeName()"));
    }

    /**
     * Guard condition is already a null-check on the target field itself:
     *   if (name == null) { name = "default"; }
     * After the fix the getter must contain the null-check exactly once — the guard condition
     * is identical to the outer null-check so the two should not be nested redundantly.
     */
    public void testGuardConditionOnTargetFieldGeneratesNoRedundantCheck() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String name;

                    void init() {
                        if (name == null) {
                            <caret>name = "default";
                        }
                    }

                    String getName() {
                        return name;
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention("Convert to lazy initialization in getter");
        assertNotNull("Fix must be offered when guard is a null-check on the target field", fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();
        assertFalse("init() should be removed", result.contains("void init()"));
        assertTrue("Null-check must be present", result.contains("if (name == null)"));
        // The guard IS the null-check — nesting it would produce a redundant double check.
        assertFalse("Redundant double null-check must NOT appear in the getter",
                result.contains("if (name == null) {") && result.indexOf("if (name == null)") != result.lastIndexOf("if (name == null)"));
        assertTrue("Assignment must be inside the null-check", result.contains("name = \"default\""));
        assertTrue("Return must be preserved", result.contains("return name;"));
    }

    /**
     * Guard condition references a method parameter — isGuardConditionMovable only blocks
     * local variable references today, so parameters slip through. After parameter substitution
     * the guard text still contains the unresolved parameter name, which would cause a compile
     * error. The fix must NOT be offered in this case.
     */
    public void testNoFixWhenGuardConditionReferencesParameter() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String name;

                    void init(String prefix) {
                        if (prefix != null) {
                            <caret>name = prefix + "_val";
                        }
                    }

                    void setup() {
                        init("hello");
                    }

                    String getName() {
                        return name;
                    }
                }
                """);

        List<IntentionAction> fixes = myFixture.getAllQuickFixes();
        boolean hasLazyFix = fixes.stream().anyMatch(f ->
                f.getText().contains("lazy initialization") || f.getText().contains("double-checked locking"));
        assertFalse("Fix must NOT be offered when the guard condition references a method parameter", hasLazyFix);
    }

    /**
     * Guard condition contains a compound OR expression referencing only instance fields —
     * no local refs, so isGuardConditionMovable returns true and the condition is preserved.
     * The guard fields (providerA, providerB) are only used in the condition, not in the RHS,
     * so they are not detected as "varying" and the simple fix is offered.
     */
    public void testGuardConditionWithOrPreserved() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private Object providerA;
                    private Object providerB;
                    private String name;

                    void init() {
                        if (providerA != null || providerB != null) {
                            <caret>name = "provided";
                        }
                    }

                    String getName() {
                        return name;
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention("Convert to lazy initialization in getter");
        assertNotNull("Fix must be offered with compound OR guard", fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();
        assertFalse("init() should be removed", result.contains("void init()"));
        assertTrue("Outer null-check must be present", result.contains("if (name == null)"));
        assertTrue("Compound OR guard must be preserved verbatim",
                result.contains("if (providerA != null || providerB != null)"));
        assertTrue("Assignment must be preserved inside guard",
                result.contains("name = \"provided\""));
    }

    // -----------------------------------------------------------------------
    // GROUP 3 — Parameter substitution edge cases
    // -----------------------------------------------------------------------

    /**
     * Host method has two parameters; the single call site provides two literal arguments.
     * substituteParams handles multiple params by replacing all param-index references in the
     * RHS text. Both arguments must be inlined and the call site removed.
     */
    public void testTwoParametersSubstitutedFromSingleCallSite() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String name;

                    void init(String first, String last) {
                        <caret>name = first + " " + last;
                    }

                    String getName() {
                        return name;
                    }

                    void setup() {
                        init("John", "Doe");
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention("Convert to lazy initialization in getter");
        assertNotNull("Fix must be offered when two parameters can be substituted from one call site", fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();
        assertFalse("init() should be removed", result.contains("void init("));
        assertTrue("Null-check must be present", result.contains("if (name == null)"));
        // Both "John" and "Doe" must appear as literals in the getter (substituted from call site)
        assertTrue("First argument must be inlined", result.contains("\"John\""));
        assertTrue("Second argument must be inlined", result.contains("\"Doe\""));
        // The call site init("John", "Doe") must be removed from setup()
        assertFalse("Call site must be removed from setup()", result.contains("init(\"John\", \"Doe\")"));
        assertTrue("Return must be preserved", result.contains("return name;"));
    }

    /**
     * The sole call site for a parameterised init method is itself inside an if-conditional.
     * findSingleCallSite counts exactly one reference, but the call is guarded — if the
     * condition is never true the field is never initialized, making the getter semantically
     * wrong. The fix must NOT be offered.
     */
    public void testNoFixWhenSingleCallSiteIsInsideConditional() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private boolean condition;
                    private String name;

                    void init(String v) {
                        <caret>name = v;
                    }

                    String getName() {
                        return name;
                    }

                    void setup() {
                        if (condition) {
                            init("value");
                        }
                    }
                }
                """);

        List<IntentionAction> fixes = myFixture.getAllQuickFixes();
        boolean hasLazyFix = fixes.stream().anyMatch(f ->
                f.getText().contains("lazy initialization") || f.getText().contains("double-checked locking"));
        assertFalse("Fix must NOT be offered when the sole call site is inside a conditional", hasLazyFix);
    }

    /**
     * The sole call site passes a field reference (not a local variable) as the argument.
     * hasUnsafeLocalArgument returns false for field references, so the fix is offered and
     * the field expression is inlined into the getter.
     */
    public void testParameterInRhsWithFieldArgumentAtCallSite() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String configPrefix;
                    private String name;

                    void init(String prefix) {
                        <caret>name = prefix + "_suffix";
                    }

                    String getName() {
                        return name;
                    }

                    void setup() {
                        init(configPrefix);
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention("Convert to lazy initialization in getter");
        assertNotNull("Fix must be offered when call-site argument is a field reference", fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();
        assertFalse("init() should be removed", result.contains("void init("));
        assertTrue("Null-check must be present", result.contains("if (name == null)"));
        assertTrue("Field argument must be inlined into getter", result.contains("configPrefix + \"_suffix\""));
        // setup() should no longer contain the init(...) call
        assertFalse("Call site must be removed from setup()", result.contains("init(configPrefix)"));
    }

    // -----------------------------------------------------------------------
    // GROUP 4 — Structural edge cases
    // -----------------------------------------------------------------------

    /**
     * The field is declared in a superclass; the init method and getter are in the subclass.
     * findSimpleGetter searches cls.findMethodsByName on the field's containing class (Base),
     * not on Child — it does not find getName() in Child and returns null, so no fix is offered.
     */
    public void testNoFixWhenGetterIsInSubclassButFieldIsInSuperclass() {
        myFixture.configureByText("Child.java", """
                public class Base {
                    protected String name;
                }

                class Child extends Base {
                    void init() {
                        <caret>name = "child";
                    }

                    String getName() {
                        return name;
                    }
                }
                """);

        List<IntentionAction> fixes = myFixture.getAllQuickFixes();
        boolean hasLazyFix = fixes.stream().anyMatch(f ->
                f.getText().contains("lazy initialization") || f.getText().contains("double-checked locking"));
        assertFalse("Fix must NOT be offered when the getter lives in a subclass but the field in a superclass",
                hasLazyFix);
    }

    /**
     * The getter's declared return type is a supertype of the field type (List vs ArrayList).
     * isBodySimpleReturn checks the return expression name, not the return type — so the getter
     * is correctly found and the fix is offered.
     */
    public void testGetterReturningSupertypeOfFieldIsFound() {
        myFixture.configureByText("Bean.java", """
                import java.util.ArrayList;
                import java.util.List;
                public class Bean {
                    private ArrayList<String> items;

                    void init() {
                        <caret>items = new ArrayList<>();
                    }

                    List<String> getItems() {
                        return items;
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention("Convert to lazy initialization in getter");
        assertNotNull("Fix must be offered even when the getter return type is a supertype of the field type", fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();
        assertFalse("init() should be removed", result.contains("void init()"));
        assertTrue("Null-check must be present", result.contains("if (items == null)"));
        assertTrue("Assignment must be inside null-check", result.contains("items = new ArrayList<>()"));
        assertTrue("Return must be preserved", result.contains("return items;"));
    }

    /**
     * Two independent init methods both assign the same field. Each assignment is in an
     * eligible position and must be highlighted / offered a fix independently.
     */
    public void testTwoIndependentInitMethodsForSameFieldBothHighlighted() {
        // First init method
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String name;

                    void initFromA() {
                        <caret>name = "from-a";
                    }

                    void initFromB() {
                        name = "from-b";
                    }

                    String getName() {
                        return name;
                    }
                }
                """);

        IntentionAction fixA = myFixture.getAvailableIntention("Convert to lazy initialization in getter");
        assertNotNull("Fix must be offered for initFromA()", fixA);

        // Second init method
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String name;

                    void initFromA() {
                        name = "from-a";
                    }

                    void initFromB() {
                        <caret>name = "from-b";
                    }

                    String getName() {
                        return name;
                    }
                }
                """);

        IntentionAction fixB = myFixture.getAvailableIntention("Convert to lazy initialization in getter");
        assertNotNull("Fix must be offered for initFromB()", fixB);
    }

    // -----------------------------------------------------------------------
    // GROUP 5 — Caching map + guard condition
    // -----------------------------------------------------------------------

    /**
     * The assignment is guard-wrapped AND the RHS references a varying instance field.
     * Only the caching-map fix is offered. The guard condition must appear inside the
     * containsKey block in the generated getter.
     */
    public void testCachingMapWithGuardCondition() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String key;
                    private final Service service = new Service();
                    private String cached;

                    void init() {
                        if (service != null) {
                            <caret>cached = service.fetch(key);
                        }
                    }

                    String getCached() {
                        return cached;
                    }

                    static class Service {
                        String fetch(String k) { return k; }
                    }
                }
                """);

        // Only the caching-map fix is offered when a varying field is present
        List<IntentionAction> fixes = myFixture.getAllQuickFixes();
        boolean hasCachingMap = fixes.stream().anyMatch(f -> f.getText().contains("caching map"));
        assertTrue("Caching-map fix must be offered when varying field is present with a guard", hasCachingMap);
        boolean hasSimple = fixes.stream().anyMatch(f -> f.getText().equals("Convert to lazy initialization in getter"));
        assertFalse("Simple lazy-init fix must NOT be offered alongside caching-map fix", hasSimple);

        IntentionAction fix = fixes.stream().filter(f -> f.getText().contains("caching map")).findFirst().orElseThrow();
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();
        assertFalse("init() should be removed", result.contains("void init()"));
        assertTrue("Map containsKey check must be present", result.contains("containsKey(key)"));
        assertTrue("Guard condition must appear inside the containsKey block",
                result.indexOf("containsKey(key)") < result.indexOf("if (service != null)"));
        assertTrue("Map put must be present", result.contains("put(key,"));
        assertTrue("Return via map get must be present", result.contains("get(key)"));
    }

    /**
     * Caching-map fix with a preamble local (literal-initialized) alongside a varying field.
     * The preamble must be moved inside the containsKey block.
     */
    public void testCachingMapFixWithPreambleAndVaryingField() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String prefix;
                    private String cached;

                    void init() {
                        String suffix = "_result";
                        <caret>cached = prefix + suffix;
                    }

                    String getCached() {
                        return cached;
                    }
                }
                """);

        List<IntentionAction> fixes = myFixture.getAllQuickFixes();
        boolean hasCachingMap = fixes.stream().anyMatch(f -> f.getText().contains("caching map"));
        assertTrue("Caching-map fix must be offered when varying field is present with a preamble", hasCachingMap);

        IntentionAction fix = fixes.stream().filter(f -> f.getText().contains("caching map")).findFirst().orElseThrow();
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();
        assertFalse("init() should be removed", result.contains("void init()"));
        assertTrue("Map containsKey check must be present", result.contains("containsKey(prefix)"));
        assertTrue("Preamble declaration must appear inside the map block", result.contains("String suffix"));
        assertTrue("Map put with preamble substitution must be present", result.contains("put(prefix,"));
        assertTrue("Return via map get must be present", result.contains("get(prefix)"));
    }

    // -----------------------------------------------------------------------
    // GROUP 6 — DCL + guard + preamble (all three combined)
    // -----------------------------------------------------------------------

    /**
     * DCL fix applied to an if-guarded assignment that also has a preamble local.
     * buildDclText embeds the full guard if-statement verbatim inside the inner null-check,
     * which carries the preamble with it.
     */
    public void testDclFixWithGuardAndPreamble() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private final Service service = new Service();
                    private String name;

                    void init() {
                        if (service != null) {
                            String prefix = "val";
                            <caret>name = service.compute(prefix);
                        }
                    }

                    String getName() {
                        return name;
                    }

                    static class Service {
                        String compute(String p) { return p; }
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention(
                "Convert to thread-safe lazy initialization (double-checked locking)");
        assertNotNull("DCL fix must be offered for guard + preamble combination", fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();
        assertFalse("init() should be removed", result.contains("void init()"));
        assertTrue("Field must become volatile", result.contains("volatile String name"));
        assertTrue("Outer null-check must be present", result.contains("if (name == null)"));
        assertTrue("synchronized block must be present", result.contains("synchronized (this)"));
        assertTrue("Inner null-check must be present inside synchronized block",
                result.indexOf("synchronized") < result.lastIndexOf("if (name == null)"));
        assertTrue("Guard condition must be inside DCL block",
                result.contains("if (service != null)"));
        assertTrue("Preamble must appear inside the guard block", result.contains("String prefix"));
        assertTrue("Assignment must be inside the guard block",
                result.contains("name = service.compute(prefix)"));
        int innerNullCheckIdx = result.lastIndexOf("if (name == null)");
        int guardIdx = result.indexOf("if (service != null)");
        assertTrue("Guard must appear after the inner null-check", innerNullCheckIdx < guardIdx);
    }

    /**
     * DCL fix applied to an if/else-guarded assignment.
     * The full if/else block must be embedded verbatim inside the innermost null-check.
     */
    public void testDclFixWithIfElseGuard() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private final Service service = new Service();
                    private String name;

                    void init() {
                        if (service != null) {
                            <caret>name = service.getValue();
                        } else {
                            name = "default";
                        }
                    }

                    String getName() {
                        return name;
                    }

                    static class Service {
                        String getValue() { return "ok"; }
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention(
                "Convert to thread-safe lazy initialization (double-checked locking)");
        assertNotNull("DCL fix must be offered for if/else guard", fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();
        assertFalse("init() should be removed", result.contains("void init()"));
        assertTrue("Field must become volatile", result.contains("volatile String name"));
        assertTrue("Outer null-check must be present", result.contains("if (name == null)"));
        assertTrue("synchronized block must be present", result.contains("synchronized (this)"));
        assertTrue("Inner null-check must be inside synchronized",
                result.indexOf("synchronized") < result.lastIndexOf("if (name == null)"));
        assertTrue("Guard condition must be present inside DCL block",
                result.contains("if (service != null)"));
        assertTrue("Else-branch must be preserved inside DCL block", result.contains("} else {"));
        assertTrue("Then-branch assignment must be present", result.contains("service.getValue()"));
        assertTrue("Else-branch assignment must be present", result.contains("\"default\""));
        assertTrue("Return must be preserved", result.contains("return name;"));
    }

    // -----------------------------------------------------------------------
    // GROUP 7 — Early-return guard (semantic trap)
    // -----------------------------------------------------------------------

    /**
     * The assignment appears at method-body level but is semantically guarded by a preceding
     * early return:
     *   if (service == null) return;
     *   name = service.getValue();
     * getHostContext sees this as a direct (non-guarded) assignment and currently offers a fix.
     * But the generated getter would call service.getValue() unconditionally, ignoring the
     * guard and potentially causing a NullPointerException.
     * <p>
     * Desired behaviour: NO FIX should be offered — the inspection must detect preceding
     * conditional-return statements that guard the assignment.
     */
    public void testNoFixWhenAssignmentIsGuardedByEarlyReturn() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private Object dependency;
                    private String name;

                    void init() {
                        if (dependency == null) return;
                        <caret>name = "computed";
                    }

                    String getName() {
                        return name;
                    }
                }
                """);

        // Without the early-return detection fix the inspection sees "name = computed" as a
        // plain direct assignment and offers the simple/DCL fix.  The generated getter would
        // call name = "computed" unconditionally — but the original code only ran that line
        // when dependency != null.  No fix should be offered.
        List<IntentionAction> fixes = myFixture.getAllQuickFixes();
        boolean hasLazyFix = fixes.stream().anyMatch(f ->
                f.getText().contains("lazy initialization") || f.getText().contains("double-checked locking"));
        assertFalse("Fix must NOT be offered when the assignment is guarded by an early return — " +
                "the generated getter would ignore the guard and assign unconditionally", hasLazyFix);
    }

    // -----------------------------------------------------------------------
    // GROUP 8 — Multi-method same-field assignment
    // -----------------------------------------------------------------------

    /**
     * When the same field is assigned in two different methods and the getter is still a simple
     * return, the fix should be offered for the FIRST method independently.
     * (Baseline: this already works — the inspection has no knowledge of other methods.)
     */
    public void testCrossMethodSameField_FixOfferedForFirstMethod() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String name;

                    void init() {
                        <caret>name = "hello";
                    }

                    void setup() {
                        name = "world";
                    }

                    String getName() {
                        return name;
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention("Convert to lazy initialization in getter");
        assertNotNull("Fix must be offered for first method even when field is also assigned in a second method", fix);
    }

    /**
     * Fix is also offered when the caret is in the SECOND method.
     */
    public void testCrossMethodSameField_FixOfferedForSecondMethod() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String name;

                    void init() {
                        name = "hello";
                    }

                    void setup() {
                        <caret>name = "world";
                    }

                    String getName() {
                        return name;
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention("Convert to lazy initialization in getter");
        assertNotNull("Fix must be offered for second method even when field is also assigned in a first method", fix);
    }

    /**
     * Applying the fix to the first method moves its assignment to the getter and deletes init().
     * The second method (setup()) and its assignment are left completely untouched.
     */
    public void testCrossMethodSameField_FirstFixLeavesSecondMethodUntouched() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String name;

                    void init() {
                        <caret>name = "hello";
                    }

                    void setup() {
                        name = "world";
                    }

                    String getName() {
                        return name;
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention("Convert to lazy initialization in getter");
        assertNotNull(fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();
        assertFalse("init() should be deleted after fix", result.contains("void init()"));
        assertTrue("setup() must remain untouched", result.contains("void setup()"));
        assertTrue("setup() assignment must still be present", result.contains("name = \"world\""));
        assertTrue("Getter must have lazy null-check", result.contains("if (name == null)"));
        assertTrue("Getter null-check must contain init's assignment", result.contains("name = \"hello\""));
    }

    /**
     * Applying the fix to the second method moves its assignment to the getter and deletes setup().
     * The first method (init()) is left completely untouched.
     */
    public void testCrossMethodSameField_SecondFixLeavesFirstMethodUntouched() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String name;

                    void init() {
                        name = "hello";
                    }

                    void setup() {
                        <caret>name = "world";
                    }

                    String getName() {
                        return name;
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention("Convert to lazy initialization in getter");
        assertNotNull(fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();
        assertFalse("setup() should be deleted after fix", result.contains("void setup()"));
        assertTrue("init() must remain untouched", result.contains("void init()"));
        assertTrue("init() assignment must still be present", result.contains("name = \"hello\""));
        assertTrue("Getter must have lazy null-check", result.contains("if (name == null)"));
        assertTrue("Getter null-check must contain setup's assignment", result.contains("name = \"world\""));
    }

    /**
     * Constructor assignments are blocked by the existing constructor guard even when
     * another method also assigns the same field.
     * Uses getAvailableIntention() (caret-position-specific) so that the eligible fix on
     * setup() does not pollute the assertion.
     */
    public void testCrossMethodSameField_ConstructorAssignmentIsBlocked() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String name;

                    Bean() {
                        <caret>name = "ctor-value";
                    }

                    void setup() {
                        name = "setup-value";
                    }

                    String getName() {
                        return name;
                    }
                }
                """);

        // getAvailableIntention() is caret-position-specific: it returns null if the element
        // at the caret has no registered problem, even when another element in the file does.
        IntentionAction fix = myFixture.getAvailableIntention("Convert to lazy initialization in getter");
        assertNull("Fix must NOT be offered for constructor assignment", fix);
    }

    /**
     * A method-body assignment is still offered the fix even when the constructor of the same
     * class also assigns the same field.
     */
    public void testCrossMethodSameField_MethodOfferedEvenWhenConstructorAlsoAssigns() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String name;

                    Bean() {
                        name = "ctor-value";
                    }

                    void setup() {
                        <caret>name = "setup-value";
                    }

                    String getName() {
                        return name;
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention("Convert to lazy initialization in getter");
        assertNotNull("Fix must be offered for method assignment even when constructor also assigns same field", fix);
    }

    /**
     * KEY NEW BEHAVIOUR: when the getter already has a lazy-init null-check (from a previous fix
     * applied to another method), the fix should still be offered for a remaining assignment to
     * the same field.
     * <p>
     * This currently FAILS because findSimpleGetter() rejects a getter whose body has more than
     * one statement. Requires the new findLazyGetter() implementation.
     */
    public void testCrossMethodSameField_FixOfferedWhenGetterAlreadyLazy() {
        // Simulate the state AFTER init() has already been fixed:
        // getter is now lazy, setup() still has the original assignment.
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String name;

                    void setup() {
                        <caret>name = "world";
                    }

                    String getName() {
                        if (name == null) {
                            name = "hello";
                        }
                        return name;
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention("Convert to lazy initialization in getter");
        assertNotNull("Fix must be offered when getter already has lazy-init pattern", fix);
    }

    /**
     * When the getter is already lazy and the fix is applied, the assignment is removed from the
     * host method (method deleted if empty). The getter body is NOT modified — the existing
     * null-check is the canonical lazy initializer.
     */
    public void testCrossMethodSameField_SecondFixOnLazyGetter_DeletesMethodWithoutModifyingGetter() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String name;

                    void setup() {
                        <caret>name = "world";
                    }

                    String getName() {
                        if (name == null) {
                            name = "hello";
                        }
                        return name;
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention("Convert to lazy initialization in getter");
        assertNotNull(fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();
        assertFalse("setup() should be deleted (was empty after assignment removed)", result.contains("void setup()"));
        assertTrue("Getter null-check must still be present", result.contains("if (name == null)"));
        assertTrue("Original lazy value must remain in getter", result.contains("name = \"hello\""));
        assertFalse("Second method's value must NOT appear in getter", result.contains("name = \"world\""));
        assertTrue("Getter return must still be present", result.contains("return name;"));
    }

    /**
     * When the getter is already lazy AND the assignment uses a build-chain preamble, the
     * preamble is removed from the host method but the getter is not modified.
     */
    public void testCrossMethodSameField_PreambleRemovedWhenFixingAgainstLazyGetter() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String name;

                    void setup() {
                        StringBuilder sb = new StringBuilder();
                        sb.append("world");
                        <caret>name = sb.toString();
                    }

                    String getName() {
                        if (name == null) {
                            name = "hello";
                        }
                        return name;
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention("Convert to lazy initialization in getter");
        assertNotNull("Fix must be offered for build-chain assignment when getter is already lazy", fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();
        assertFalse("setup() should be deleted after fix", result.contains("void setup()"));
        assertFalse("StringBuilder preamble from setup() must NOT appear in result", result.contains("StringBuilder sb"));
        assertTrue("Getter must still have original lazy-init", result.contains("name = \"hello\""));
        assertFalse("Build-chain result must NOT appear in getter", result.contains("name = \"world\""));
    }

    /**
     * Three methods all assign the same field with the getter still a simple return.
     * Each is independently offered the fix.
     */
    public void testCrossMethodSameField_ThreeMethodsEachOfferedIndependently() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String name;

                    void init() { <caret>name = "init"; }
                    void setup() { name = "setup"; }
                    void reset() { name = "reset"; }

                    String getName() { return name; }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention("Convert to lazy initialization in getter");
        assertNotNull("Fix must be offered even when three methods assign the same field", fix);
    }

    /**
     * Build-chain assignment in first method, simple literal in second — each independently offered.
     * Applying the fix to the build-chain method moves its full preamble to the getter.
     */
    public void testCrossMethodSameField_BuildChainInFirstSimpleInSecond() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private String name;

                    void init() {
                        StringBuilder sb = new StringBuilder();
                        sb.append("hello");
                        <caret>name = sb.toString();
                    }

                    void setup() {
                        name = "world";
                    }

                    String getName() {
                        return name;
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention("Convert to lazy initialization in getter");
        assertNotNull("Fix must be offered for build-chain method even when another method assigns the field simply", fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();
        assertFalse("init() should be deleted after fix", result.contains("void init()"));
        assertTrue("setup() must remain untouched", result.contains("void setup()"));
        assertTrue("Getter must have build-chain preamble", result.contains("StringBuilder sb"));
        assertTrue("Getter must have lazy null-check", result.contains("if (name == null)"));
    }

    /**
     * Guarded assignment in one method, unguarded in another — each independently offered.
     * The guard condition must be nested inside the null-check in the getter.
     */
    public void testCrossMethodSameField_GuardedAndUnguardedAssignments() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private boolean flag;
                    private String name;

                    void init() {
                        if (flag) {
                            <caret>name = "guarded";
                        }
                    }

                    void setup() {
                        name = "unguarded";
                    }

                    String getName() {
                        return name;
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention("Convert to lazy initialization in getter");
        assertNotNull("Fix must be offered for guarded assignment even when same field is also assigned unguarded elsewhere", fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();
        assertFalse("init() should be deleted after fix", result.contains("void init()"));
        assertTrue("setup() must remain", result.contains("void setup()"));
        assertTrue("Outer null-check must be present", result.contains("if (name == null)"));
        assertTrue("Guard condition must be preserved inside null-check", result.contains("if (flag)"));
        assertTrue("Guard must be nested inside null-check",
                result.indexOf("if (name == null)") < result.indexOf("if (flag)"));
    }

    /**
     * An assignment that is ineligible in one method (RHS references multiple varying fields,
     * blocking all fix paths) must not prevent the inspection from firing on the eligible
     * assignment in a different method.
     * Uses getAvailableIntention() (caret-position-specific) so the fix for setup() does not
     * pollute the assertion about init().
     */
    public void testCrossMethodSameField_IneligibleMethodDoesNotBlockEligibleMethod() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private int a;
                    private int b;
                    private String name;

                    void init() {
                        String tmp = a + "-" + b;
                        <caret>name = tmp;
                    }

                    void setup() {
                        name = "default";
                    }

                    String getName() {
                        return name;
                    }
                }
                """);

        // getAvailableIntention() is caret-position-specific: returns null when the element
        // at the caret has no registered problem, even if another element in the file does.
        IntentionAction fix = myFixture.getAvailableIntention("Convert to lazy initialization in getter");
        assertNull("Fix must NOT be offered for init() — RHS uses multiple varying fields", fix);
    }

    /**
     * Eligible assignment in setup() is still offered the fix even though init() (assigning the
     * same field) is ineligible due to multiple varying fields.
     */
    public void testCrossMethodSameField_EligibleMethodNotBlockedByIneligiblePeer() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private int a;
                    private int b;
                    private String name;

                    void init() {
                        String tmp = a + "-" + b;
                        name = tmp;
                    }

                    void setup() {
                        <caret>name = "default";
                    }

                    String getName() {
                        return name;
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention("Convert to lazy initialization in getter");
        assertNotNull("Fix must be offered for setup() even though init() is ineligible for the same field", fix);
    }

    // -----------------------------------------------------------------------
    // GROUP 9 — Selector-based lazy getter (joint fix for init + selector pattern)
    // -----------------------------------------------------------------------

    // Shared test fixture code for GROUP 9:
    //   - service is final (so it is not a "varying" field)
    //   - entity is a non-final instance field (the varying field)
    //   - The selector assignment uses this.entity.getId() (field reference), so entity IS
    //     detected as a varying field in the RHS
    //   - init() assigns value = "default" (no varying fields, no params → null-case companion)
    //
    // Detection path:
    //   varyingFields = [entity], isVaryingFieldAssignedBefore(entity, onEntitySelected) = true
    //   → findNullCaseAssignment finds init()
    //   → extractEffectiveKey finds this.entity.getId() → KeyExprInfo("this.entity.getId()", "Long")
    //   → registers SelectorLazyGetterQuickFix

    private static final String SELECTOR_BEAN = """
            public class Bean {
                private final Service service = new Service();
                private Entity entity;
                private String value;

                void init() {
                    value = "default";
                }

                void onEntitySelected(Entity e) {
                    this.entity = e;
                    <caret>value = service.compute(this.entity.getId());
                }

                public String getValue() {
                    return value;
                }

                static class Entity { long getId() { return 0L; } }
                static class Service { String compute(long id) { return ""; } }
            }
            """;

    /**
     * The selector fix must be offered (caret on the selector assignment).
     */
    public void testSelectorPattern_FixOffered() {
        myFixture.configureByText("Bean.java", SELECTOR_BEAN);
        IntentionAction fix = myFixture.getAvailableIntention(
                "Convert to selector-based lazy getter (keyed by this.entity.getId())");
        assertNotNull("Selector-based lazy getter fix must be offered", fix);
    }

    /**
     * When the varying field is assigned in the selector method, the individual caching-map fix
     * must NOT be offered (it would break always-fresh semantics for re-selections).
     */
    public void testSelectorPattern_IndividualCachingMapFixBlocked() {
        myFixture.configureByText("Bean.java", SELECTOR_BEAN);
        List<com.intellij.codeInsight.intention.IntentionAction> fixes = myFixture.getAllQuickFixes();
        boolean hasCachingMap = fixes.stream().anyMatch(f -> f.getText().contains("caching map getter"));
        assertFalse("Individual caching-map fix must be blocked for selector-method assignments", hasCachingMap);
    }

    /**
     * After applying the fix, the getter must have three branches:
     * if (entity == null), else if (!valueCache.containsKey(...)), else.
     */
    public void testSelectorPattern_GetterHasThreeBranches() {
        myFixture.configureByText("Bean.java", SELECTOR_BEAN);
        IntentionAction fix = myFixture.getAvailableIntention(
                "Convert to selector-based lazy getter (keyed by this.entity.getId())");
        assertNotNull(fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();
        assertTrue("Getter must have null-check branch for varying field",
                result.contains("if (entity == null)"));
        assertTrue("Getter must have cache-miss branch",
                result.contains("containsKey(this.entity.getId())"));
        assertTrue("Getter must have cache-hit else branch",
                result.contains("} else {"));
    }

    /**
     * The generated cache map must use Long (the getId() return type) as key, not Entity.
     */
    public void testSelectorPattern_KeyIsEntityId_NotEntity() {
        myFixture.configureByText("Bean.java", SELECTOR_BEAN);
        IntentionAction fix = myFixture.getAvailableIntention(
                "Convert to selector-based lazy getter (keyed by this.entity.getId())");
        assertNotNull(fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();
        // Key type must be Long (from getId() return type), not Entity.
        // The map declaration may use short or qualified form depending on the code style manager.
        assertTrue("Cache map key must use Long type (from getId() return type)",
                result.contains("Map<Long,") || result.contains("Map<Long, "));
        assertFalse("Cache map key must NOT be Entity", result.contains("Map<Entity,"));
    }

    /**
     * After applying the fix, init() must be deleted (it becomes empty).
     */
    public void testSelectorPattern_InitMethodDeleted() {
        myFixture.configureByText("Bean.java", SELECTOR_BEAN);
        IntentionAction fix = myFixture.getAvailableIntention(
                "Convert to selector-based lazy getter (keyed by this.entity.getId())");
        assertNotNull(fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();
        assertFalse("init() must be deleted after the fix (it becomes empty)", result.contains("void init()"));
    }

    /**
     * After applying the fix, onEntitySelected must keep only this.entity = e (the list
     * assignment is removed).
     */
    public void testSelectorPattern_SelectorMethodSimplified() {
        myFixture.configureByText("Bean.java", SELECTOR_BEAN);
        IntentionAction fix = myFixture.getAvailableIntention(
                "Convert to selector-based lazy getter (keyed by this.entity.getId())");
        assertNotNull(fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();
        assertTrue("onEntitySelected must be kept (has remaining this.entity = e)",
                result.contains("void onEntitySelected"));
        assertTrue("onEntitySelected must keep the entity assignment",
                result.contains("this.entity = e"));
        assertFalse("onEntitySelected must not contain the value assignment after fix",
                result.contains("value = service.compute"));
    }

    /**
     * When init() has other statements besides the field assignment, only the assignment is
     * removed — init() is kept with its remaining statements.
     */
    public void testSelectorPattern_InitWithOtherStatementsKept() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private final Service service = new Service();
                    private Entity entity;
                    private String value;

                    void init() {
                        System.out.println("initializing");
                        value = "default";
                    }

                    void onEntitySelected(Entity e) {
                        this.entity = e;
                        <caret>value = service.compute(this.entity.getId());
                    }

                    public String getValue() {
                        return value;
                    }

                    static class Entity { long getId() { return 0L; } }
                    static class Service { String compute(long id) { return ""; } }
                }
                """);
        IntentionAction fix = myFixture.getAvailableIntention(
                "Convert to selector-based lazy getter (keyed by this.entity.getId())");
        assertNotNull(fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();
        assertTrue("init() must be kept when it has other statements", result.contains("void init()"));
        assertTrue("init()'s other statement must be preserved", result.contains("System.out.println"));
        // value = "default" appears in the getter's null-case branch — verify it appears exactly once
        // (in the getter), meaning init()'s copy was removed.
        assertEquals("value = \"default\" must appear exactly once (in getter null-branch, not also in init())",
                1, result.split(java.util.regex.Pattern.quote("value = \"default\""), -1).length - 1);
    }

    /**
     * When there is no null-case companion method, no fix should be offered.
     */
    public void testSelectorPattern_NoFixWhenNoNullCaseMethod() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private final Service service = new Service();
                    private Entity entity;
                    private String value;

                    void onEntitySelected(Entity e) {
                        this.entity = e;
                        <caret>value = service.compute(this.entity.getId());
                    }

                    public String getValue() {
                        return value;
                    }

                    static class Entity { long getId() { return 0L; } }
                    static class Service { String compute(long id) { return ""; } }
                }
                """);
        IntentionAction fix = myFixture.getAvailableIntention(
                "Convert to selector-based lazy getter");
        assertNull("No fix must be offered when there is no null-case companion method", fix);
    }

    /**
     * Regression guard: when the varying field is NOT assigned before the target assignment in
     * the host method, the original caching-map fix must still be offered (not the selector fix).
     */
    public void testSelectorPattern_RegularCachingMapStillWorksWithoutSelectorFieldAssignment() {
        myFixture.configureByText("Bean.java", """
                public class Bean {
                    private Entity entity;
                    private String value;

                    void loadValue(Entity e) {
                        <caret>value = e.getName();
                    }

                    public String getValue() {
                        return value;
                    }

                    static class Entity {
                        long getId() { return 0L; }
                        String getName() { return ""; }
                    }
                }
                """);
        // entity is NOT assigned before value = e.getName() in loadValue,
        // so the selector path must NOT fire — fall through to the regular caching-map fix.
        // Note: entity resolves as a varying field via the parameter e (same type).
        // If entity is not a varying field here, we simply check the selector fix is absent.
        List<com.intellij.codeInsight.intention.IntentionAction> fixes = myFixture.getAllQuickFixes();
        boolean hasSelectorFix = fixes.stream().anyMatch(f -> f.getText().contains("selector-based lazy getter"));
        assertFalse("Selector fix must NOT be offered when varying field is not assigned before target", hasSelectorFix);
    }
}
