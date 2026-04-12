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
     * The local is referenced in a second statement that is not a setter call on it —
     * moving it to the getter would break the second use site.
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

        List<IntentionAction> fixes = myFixture.getAllQuickFixes();
        boolean hasLazyFix = fixes.stream().anyMatch(f -> f.getText().contains("lazy initialization")
                || f.getText().contains("double-checked locking"));
        assertFalse("Fix must NOT be offered when the local is used in another statement", hasLazyFix);
    }
}
