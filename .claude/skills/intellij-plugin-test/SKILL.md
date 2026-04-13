---
name: intellij-plugin-test
description: Write, debug, and fix tests for IntelliJ Platform plugins (Java/Kotlin). Use this skill whenever the user is working on an IntelliJ plugin and asks to add tests, write unit tests for inspections or quick-fixes, debug failing plugin tests, fix PsiInvalidElementAccessException errors, set up the test Gradle infrastructure, or asks about BasePlatformTestCase, myFixture, checkHighlighting, or TestFrameworkType. Trigger proactively when a plugin has no tests yet or when ./gradlew test fails.
---

# IntelliJ Plugin Test Skill

Tests for IntelliJ plugins are "model-level functional tests" — they run in a headless JVM using real production PSI/platform components, operate on source files as input, and compare output against expected results. This makes them extremely stable; they rarely need updating after refactors. Do not mock the IntelliJ platform — use real components.

## Step 1 — Detect the current state

Before writing anything, read:
- `build.gradle.kts` — check test dependencies
- `src/test/java/` — note what base class is already in use
- The inspection / fix source files to be tested

## Step 2 — Verify Gradle test dependencies

```kotlin
// build.gradle.kts
dependencies {
    testImplementation("junit:junit:4.13.2")   // must be explicit — NOT pulled in transitively
    intellijPlatform {
        testFramework(TestFrameworkType.Platform)  // provides BasePlatformTestCase
    }
}
tasks { test { useJUnit() } }
```

**Known traps:**
- `TestFrameworkType.Java` does **not** exist → compilation error
- `LightJavaCodeInsightFixtureTestCase` requires the Java test framework JAR which is not always available; use `BasePlatformTestCase` instead — it handles Java files fine for parsing/PSI traversal without needing a JDK
- JUnit assert methods (`assertTrue`, `assertNotNull`) come from `junit:junit`; if you see "cannot access TestCase" the JUnit dep is missing

## Step 3 — Choose the right base class

| Situation                                       | Base class                                                   |
|-------------------------------------------------|--------------------------------------------------------------|
| Default — inspections, quick-fixes, completions | `BasePlatformTestCase`                                       |
| Need a real JDK in the test project             | `LightJavaCodeInsightFixtureTestCase` (JUnit3) / `…4` / `…5` |
| Multi-module project                            | `HeavyPlatformTestCase`                                      |

Use `BasePlatformTestCase` by default. It reuses the same project across tests (fast), provides `myFixture`, and requires no JDK.

## Step 4 — Standard test pattern

```java
public class MyInspectionTest extends BasePlatformTestCase {

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        myFixture.enableInspections(new MyInspection());
    }

    // Method names MUST start with "test" — no @Test annotation (JUnit 3 style)
    public void testQuickFixTransformation() {
        myFixture.configureByText("Foo.java", """
                public class Foo {
                    private String name;

                    void init() {
                        <caret>name = "default";
                    }

                    String getName() {
                        return name;
                    }
                }
                """);

        IntentionAction fix = myFixture.getAvailableIntention("Convert to lazy initialization");
        assertNotNull("Fix should be available", fix);
        myFixture.launchAction(fix);

        String result = myFixture.getFile().getText();
        assertTrue(result.contains("if (name == null)"));
        assertFalse(result.contains("void init()"));
    }
}
```

## Step 5 — Key `myFixture` API

| Method                                     | What it does                                                                       |
|--------------------------------------------|------------------------------------------------------------------------------------|
| `configureByText(filename, text)`          | In-memory file; supports `<caret>`, `<selection>…</selection>`, `<block>…</block>` |
| `configureByFile(relativePath)`            | Load from `getTestDataPath()`                                                      |
| `enableInspections(inspection…)`           | Register inspections before running                                                |
| `doHighlighting()`                         | Run highlighting, return `List<HighlightInfo>`                                     |
| `checkHighlighting(warnings, infos, weak)` | Assert highlights match `<warning>`/`<error>` markup in source                     |
| `getAvailableIntention(prefix)`            | First intention at caret matching the prefix                                       |
| `findSingleIntention(prefix)`              | Like above but throws if not exactly one match                                     |
| `getAllQuickFixes()`                       | All quick-fixes across the whole file (ignores caret)                              |
| `launchAction(action)`                     | Apply an intention / quick-fix                                                     |
| `checkResult(text)`                        | Assert exact file text (brittle for whitespace)                                    |
| `checkResultByFile(path)`                  | Compare against a testdata file                                                    |
| `type(text)`                               | Simulate keystrokes                                                                |
| `complete()`                               | Code completion; returns lookup items                                              |
| `renameElementAtCaret(name)`               | Simulate rename refactoring                                                        |
| `findUsages()`                             | Simulate Find Usages                                                               |

## Step 6 — Inspection test patterns

### Pattern A — markup + `checkHighlighting` (verifies *where* highlights appear)

```java
myFixture.configureByText("Foo.java", """
        class Foo {
            private String x;
            void m() { <warning descr="Assignment to 'x'...">x = compute()</warning>; }
            String getX() { return x; }
        }
        """);
myFixture.checkHighlighting(true, false, false);  // warnings=true, infos=false, weak=false
```

### Pattern B — string-contains after fix (preferred for quick-fix tests; tolerates whitespace)

```java
myFixture.configureByText("Foo.java", BEFORE);
myFixture.launchAction(myFixture.getAvailableIntention("My fix name"));
String result = myFixture.getFile().getText();
assertTrue(result.contains("expected change"));
assertFalse(result.contains("removed fragment"));
```

### Verifying no fix is offered

```java
List<IntentionAction> fixes = myFixture.getAllQuickFixes();
assertFalse(fixes.stream().anyMatch(f -> f.getText().contains("lazy init")));
```

### Checking problem description content

```java
boolean found = myFixture.doHighlighting().stream()
        .anyMatch(h -> h.getDescription() != null && h.getDescription().contains("call site"));
assertTrue(found);
```

## Step 7 — PSI manipulation pitfalls (fix these when tests crash)

### 1. `PsiInvalidElementAccessException` on whitespace deletion

**Symptom:** stack trace through `LeafPsiElement.delete` → `CodeEditUtil.removeChild`  
**Cause:** `parent.delete()` was called before deleting a sibling whitespace node; the deletion invalidates the sibling's PSI context  
**Fix:** delete sibling **first**, then parent

```java
// ❌ WRONG
PsiElement prev = hostMethod.getPrevSibling();
hostMethod.delete();
if (prev instanceof PsiWhiteSpace) prev.delete(); // prev is now invalid!

// ✅ CORRECT
PsiElement prev = hostMethod.getPrevSibling();
if (prev instanceof PsiWhiteSpace) prev.delete(); // delete sibling first
hostMethod.delete();
```

### 2. DummyHolder whitespace after `createStatementFromText`

`factory.createStatementFromText(text, null)` creates elements in a temporary DummyHolder. After inserting them into the real tree with `addBefore`/`addAfter`, do not cache sibling references across the mutation — re-query the tree instead.

### 3. Don't cache `getStatements()` across mutations

```java
// ❌ stale after deletion
PsiStatement[] stmts = body.getStatements();
stmts[0].delete();
if (stmts.length == 0) { ... }        // wrong — re-query:

// ✅
stmts[0].delete();
if (body.getStatements().length == 0) { ... }
```

### 4. `ReferencesSearch` inside `buildVisitor`

Calling `ReferencesSearch.search()` inside the inspection visitor is valid (read action), but expensive. Guard expensive searches:

```java
if (!isOnTheFly) {
    Collection<PsiReference> refs = ReferencesSearch.search(method).findAll();
    // ...
}
```

## Step 8 — Testdata file structure

Override `getTestDataPath()`:

```java
@Override
protected String getTestDataPath() {
    return "src/test/testdata";
}
```

Then:

```java
myFixture.configureByFile("MyTest.java");
// apply fix...
myFixture.checkResultByFile("MyTest_after.java");
```

Use the same `<caret>` / `<selection>` markers inside testdata files.

## Step 9 — Avoiding flaky tests

- Call `super.tearDown()` inside `finally {}` to prevent test pollution
- If file-refresh errors appear, delete `build/idea-sandbox/system/caches`
- Mark production code used only in tests with `@TestOnly`
- Replace services: `ServiceContainerUtil.replaceService(project, MyService.class, mock, getTestRootDisposable())`
- Replace extension points: `ExtensionTestUtil.maskExtensions(EP_NAME, List.of(mock), getTestRootDisposable())`

## Step 10 — Run and parse failures

```bash
./gradlew test
```

Parse failures from `build/test-results/test/TEST-*.xml`:

```python
import xml.etree.ElementTree as ET, glob
for path in glob.glob("build/test-results/test/TEST-*.xml"):
    for tc in ET.parse(path).getroot().findall("testcase"):
        fail = tc.find("failure") or tc.find("error")
        if fail is not None:
            print(tc.get("name"))
            print(fail.get("message"))
            print((fail.text or "")[:600])
```

Most common root causes:
| Error | Cause |
|---|---|
| "cannot access TestCase" | `junit:junit` dep missing |
| `PsiInvalidElementAccessException` + "DummyHolderViewProvider" | Wrong deletion order (Step 7.1) |
| `PsiInvalidElementAccessException` + "different providers" | Cached sibling across tree mutation (Step 7.2) |
| `getAvailableIntention` returns null | No `<caret>` marker in source, or wrong display-name prefix |
| "Unresolved reference 'Java'" in Gradle | `TestFrameworkType.Java` doesn't exist — use `Platform` |
