# Const Ref Candidate Index Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace const-ref reference resolution with a definition-independent candidate index so reference scanning no longer depends on full-scan directory order or dirty definition state.

**Status:** Implemented. The main analysis path now writes syntax-only reference candidates for Kotlin and Java, persists them in `const_reference_candidates`, and resolves impact by matching changed or removed const definition keys against latest candidate rows. Legacy exact references remain for compatibility.

**Architecture:** Reference scanning records syntax facts only: explicit const imports, explicit class imports, star imports, owner-qualified references, and bare const-name uses. Definition scanning remains independent. Impact lookup matches changed definitions against candidate facts conservatively, preferring extra recompiles over missed recompiles.

**Tech Stack:** Kotlin, JUnit4, SQLite, existing `ConstRefEngine`, `KotlinConstParser`, `JavaConstParser`, and `ConstRefCacheDatabase`.

---

## File Structure

- Modify `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefModels.kt`
  - Add candidate reference model fields.
  - Keep `EffectedConstRef` output unchanged for downstream compile logic.
- Modify `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/KotlinConstParser.kt`
  - Parse Kotlin reference candidates without consulting definitions.
  - Normalize companion owner syntax such as `BasePager.Companion.CONST`.
- Modify `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/JavaConstParser.kt`
  - Parse Java reference candidates without consulting definitions.
- Modify `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefAnalyzer.kt`
  - Expose candidate parsing APIs.
- Modify `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefEngine.kt`
  - Stop building reference indexes from DB definitions.
  - Store candidate references during analysis.
- Modify `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefCacheDatabase.kt`
  - Replace exact reference persistence and lookup with candidate reference persistence and matching.
  - Bump incompatible schema version.
- Modify tests under `main/src/test/java/com/sickworm/intellij/jugg/compiler/constref/`
  - Add red tests for scan-order independence, companion imports, and candidate matching.
- Modify `docs/ai_knowledge/03_deploy_const_ref.md`
  - Document the candidate-index model after behavior is implemented.

## Candidate Model

Use one persisted candidate reference shape instead of exact and candidate dual tables:

```kotlin
data class ConstReferenceCandidate(
    val refFilePath: String,
    val packageName: String,
    val constName: String,
    val ownerName: String?,
    val ownerKind: ConstReferenceOwnerKind,
    val importPackages: Set<String>,
)

enum class ConstReferenceOwnerKind {
    EXPLICIT_CONST_IMPORT,
    EXPLICIT_CLASS_IMPORT,
    PACKAGE_STAR_IMPORT,
    CLASS_STAR_IMPORT,
    OWNER_EXPRESSION,
    BARE_SAME_PACKAGE,
}
```

Matching changed `ConstDefinition` to a candidate:

```kotlin
private fun ConstReferenceCandidate.mayReference(definition: ConstDefinition): Boolean {
    if (constName != definition.constName) return false
    val normalizedDefinitionOwners = definition.normalizedOwners()
    return when (ownerKind) {
        ConstReferenceOwnerKind.EXPLICIT_CONST_IMPORT,
        ConstReferenceOwnerKind.EXPLICIT_CLASS_IMPORT,
        ConstReferenceOwnerKind.CLASS_STAR_IMPORT,
        ConstReferenceOwnerKind.OWNER_EXPRESSION -> ownerName in normalizedDefinitionOwners
        ConstReferenceOwnerKind.PACKAGE_STAR_IMPORT -> definition.packageName in importPackages
        ConstReferenceOwnerKind.BARE_SAME_PACKAGE -> packageName == definition.packageName
    }
}

private fun ConstDefinition.normalizedOwners(): Set<String> {
    val owners = linkedSetOf(fqClassName, fqClassName.substringAfterLast('.'))
    owners += "$fqClassName.Companion"
    owners += "${fqClassName.substringAfterLast('.')}.Companion"
    return owners
}
```

Keep the first implementation conservative. If a syntax form is ambiguous but can plausibly reference the changed const, return the source file as effected.

### Task 1: Red Test For Full-Scan Order Independence

**Files:**
- Modify: `main/src/test/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefEngineTest.kt`

- [ ] **Step 1: Write the failing test**

Add a test where the reference file is analyzed before the definition file. The changed const must still return the reference file.

```kotlin
@Test
fun `should find effected file when reference is scanned before definition`() {
    val rootDir = createTempDirectory("const_ref_scan_order_independent")
    File(rootDir, ".git").mkdirs()
    val userFile = File(rootDir, "User.kt").apply {
        writeText(
            """
            package com.example.user
            import com.example.Config.Companion.MAX
            val value = MAX
            """.trimIndent()
        )
    }
    val constantsFile = File(rootDir, "Config.kt").apply {
        writeText(
            """
            package com.example
            class Config {
                companion object {
                    const val MAX = 1
                }
            }
            """.trimIndent()
        )
    }

    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    val database = ConstRefCacheDatabase(File(rootDir, "const_ref.db"), logger)
    val scheduler = ConstRefEngine(
        analyzer = ConstRefAnalyzer(logger),
        database = database,
        logger = logger,
        backgroundTaskRunner = CoroutineBackgroundTaskRunner(scope),
        repoSharedFingerprintStore = RepoSharedFingerprintStore(logger, File(rootDir, "repo_fingerprint.db")),
    )
    try {
        scheduler.analyzeOnDemand(listOf(userFile.absolutePath))
        scheduler.analyzeOnDemand(listOf(constantsFile.absolutePath))
        scheduler.getEffectedFiles(listOf(constantsFile.absolutePath))

        constantsFile.writeText(
            """
            package com.example
            class Config {
                companion object {
                    const val MAX = 2
                }
            }
            """.trimIndent()
        )
        constantsFile.setLastModified(constantsFile.lastModified() + 1000L)
        scheduler.onFileSaved(constantsFile.absolutePath)
        scheduler.awaitAnalysis(listOf(constantsFile.absolutePath), timeoutMs = 10_000L)

        val effectedPaths = scheduler.getEffectedFiles(listOf(constantsFile.absolutePath))
            .map { it.refFilePath }
            .toSet()
        assertEquals(setOf(userFile.toStdPath()), effectedPaths)
    } finally {
        scheduler.dispose()
        scope.cancel()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :main:test --tests 'com.sickworm.intellij.jugg.compiler.constref.ConstRefEngineTest.should find effected file when reference is scanned before definition'
```

Expected: FAIL because no effected file is returned.

### Task 2: Red Test For Kotlin Candidate Parsing

**Files:**
- Modify: `main/src/test/java/com/sickworm/intellij/jugg/compiler/constref/KotlinConstParserTest.kt`

- [ ] **Step 1: Write the failing test**

Add a parser-level test that does not provide definitions and still records candidate facts for companion const imports and owner expressions.

```kotlin
@Test
fun `should parse kotlin const reference candidates without definitions`() {
    val rootDir = createTempDirectory("kotlin_const_ref_candidates")
    val userFile = File(rootDir, "User.kt").apply {
        writeText(
            """
            package com.example.user
            import com.example.Config.Companion.MAX
            import com.example.BasePager
            val a = MAX
            val b = BasePager.THEME_ID_DEFAULT_WHITE_ANDROID
            """.trimIndent()
        )
    }

    val parser = KotlinConstParser(logger)
    try {
        val candidates = parser.parseReferenceCandidates(userFile)
        assertTrue(candidates.any {
            it.constName == "MAX" &&
                it.ownerName == "com.example.Config.Companion" &&
                it.ownerKind == ConstReferenceOwnerKind.EXPLICIT_CONST_IMPORT
        })
        assertTrue(candidates.any {
            it.constName == "THEME_ID_DEFAULT_WHITE_ANDROID" &&
                it.ownerName == "com.example.BasePager" &&
                it.ownerKind == ConstReferenceOwnerKind.OWNER_EXPRESSION
        })
    } finally {
        parser.dispose()
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :main:test --tests 'com.sickworm.intellij.jugg.compiler.constref.KotlinConstParserTest.should parse kotlin const reference candidates without definitions'
```

Expected: FAIL because `parseReferenceCandidates` and candidate model do not exist.

### Task 3: Implement Candidate Models And Kotlin Parser

**Files:**
- Modify: `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefModels.kt`
- Modify: `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/KotlinConstParser.kt`
- Modify: `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefAnalyzer.kt`

- [ ] **Step 1: Add candidate model**

Add `ConstReferenceCandidate` and `ConstReferenceOwnerKind` in `ConstRefModels.kt`. Keep existing `ConstReference` until database migration is complete.

- [ ] **Step 2: Add Kotlin parser API**

Add `fun parseReferenceCandidates(sourceFile: File): List<ConstReferenceCandidate>` in `KotlinConstParser`.

Implementation rules:
- Build import context without `ConstDefinitionLookup`.
- For explicit const imports, record owner as imported FQ name without the final const segment.
- For explicit class imports, map simple class name to imported FQ name.
- For `Owner.CONST`, record `OWNER_EXPRESSION` with owner resolved through explicit class import when possible.
- For bare `CONST`, record candidates from explicit const imports, star imports, and same package.
- Normalize only syntax; do not query definitions.

- [ ] **Step 3: Add analyzer API**

Add `parseReferenceCandidates(files: Collection<File>): Map<String, List<ConstReferenceCandidate>>` in `ConstRefAnalyzer`.

- [ ] **Step 4: Run parser test**

Run:

```bash
./gradlew :main:test --tests 'com.sickworm.intellij.jugg.compiler.constref.KotlinConstParserTest.should parse kotlin const reference candidates without definitions'
```

Expected: PASS.

### Task 4: Persist Candidate References And Match By Changed Definitions

**Files:**
- Modify: `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefCacheDatabase.kt`
- Modify: `main/src/test/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefCacheDatabaseTest.kt`

- [ ] **Step 1: Write the failing database test**

Add a test that persists a candidate reference for `com.example.Config.Companion.MAX`, persists a changed definition for `com.example.Config.MAX`, and expects `getEffectedFilesByDefinitionKeys(setOf("com.example.Config" to "MAX"), listOf(constantsPath))` to return the reference file.

- [ ] **Step 2: Run database test to verify it fails**

Run:

```bash
./gradlew :main:test --tests 'com.sickworm.intellij.jugg.compiler.constref.ConstRefCacheDatabaseTest.should match companion candidate references by changed definition'
```

Expected: FAIL because the DB still stores exact `def_fq_class_name`.

- [ ] **Step 3: Implement schema migration**

Update schema creation to store candidate fields. Bump schema version so existing `~/.jugg/const_ref/const_ref_shared.db` is rebuilt.

Recommended table shape:

```sql
CREATE TABLE IF NOT EXISTS const_reference_candidates (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    repo_key TEXT NOT NULL,
    relative_path TEXT NOT NULL,
    checksum INTEGER NOT NULL,
    package_name TEXT NOT NULL,
    const_name TEXT NOT NULL,
    owner_name TEXT,
    owner_kind TEXT NOT NULL,
    import_packages TEXT NOT NULL DEFAULT ''
)
```

Indexes:

```sql
CREATE INDEX IF NOT EXISTS idx_const_ref_candidates_const
    ON const_reference_candidates(repo_key, const_name);
CREATE INDEX IF NOT EXISTS idx_const_ref_candidates_file
    ON const_reference_candidates(repo_key, relative_path, checksum);
```

- [ ] **Step 4: Implement candidate matching**

Change effected lookup to:
- Load changed latest definitions by key.
- Query candidates in the same repo by `const_name`.
- Match in Kotlin with `mayReference(definition)`.
- Return existing local files excluding changed paths.

- [ ] **Step 5: Run database test**

Run:

```bash
./gradlew :main:test --tests 'com.sickworm.intellij.jugg.compiler.constref.ConstRefCacheDatabaseTest.should match companion candidate references by changed definition'
```

Expected: PASS.

### Task 5: Wire Engine To Candidate Parsing

**Files:**
- Modify: `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefEngine.kt`
- Modify: `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/ConstRefAnalyzer.kt`

- [ ] **Step 1: Replace reference parse call**

In analysis phase 2, replace `parseReferencesByDbOnly(file)` with candidate parsing:

```kotlin
val references = analyzer.parseReferenceCandidates(listOf(file))[readState.path].orEmpty()
```

Use the final local variable name `referenceCandidates` if that keeps types clear.

- [ ] **Step 2: Remove DB definition candidate lookup from reference scanning**

Delete or stop using `parseReferencesByDbOnly`, `queryCandidateDefinitionsForFile`, and the definition lookup cache path from reference parsing when no other tests depend on it.

- [ ] **Step 3: Run scan-order engine test**

Run:

```bash
./gradlew :main:test --tests 'com.sickworm.intellij.jugg.compiler.constref.ConstRefEngineTest.should find effected file when reference is scanned before definition'
```

Expected: PASS.

### Task 6: Java Parser Candidate Path

**Files:**
- Modify: `main/src/main/java/com/sickworm/intellij/jugg/compiler/constref/JavaConstParser.kt`
- Modify: `main/src/test/java/com/sickworm/intellij/jugg/compiler/constref/JavaConstParserTest.kt`

- [ ] **Step 1: Write failing Java parser test**

Add a Java test covering:

```java
package com.example.user;
import static com.example.Config.MAX;
import com.example.Flags;
class User {
    int a = MAX;
    int b = Flags.VALUE;
}
```

Expected candidates:
- `EXPLICIT_CONST_IMPORT` owner `com.example.Config`, const `MAX`
- `OWNER_EXPRESSION` owner `com.example.Flags`, const `VALUE`

- [ ] **Step 2: Run Java parser test to verify it fails**

Run:

```bash
./gradlew :main:test --tests 'com.sickworm.intellij.jugg.compiler.constref.JavaConstParserTest.should parse java const reference candidates without definitions'
```

Expected: FAIL.

- [ ] **Step 3: Implement Java candidate parser**

Mirror the Kotlin model using JavaParser AST data already used by `JavaConstParser`.

- [ ] **Step 4: Run Java parser test**

Run:

```bash
./gradlew :main:test --tests 'com.sickworm.intellij.jugg.compiler.constref.JavaConstParserTest.should parse java const reference candidates without definitions'
```

Expected: PASS.

### Task 7: Compatibility Cleanup And Existing Tests

**Files:**
- Modify: const-ref production and tests as needed.

- [ ] **Step 1: Update old exact-reference tests**

Tests that assert `ConstReference(defFqClassName, constName)` should assert candidate owner facts instead.

- [ ] **Step 2: Run focused const-ref tests**

Run:

```bash
./gradlew :main:test --tests 'com.sickworm.intellij.jugg.compiler.constref.*'
```

Expected: PASS.

- [ ] **Step 3: Compile main module**

Run:

```bash
./gradlew :main:compileKotlin
```

Expected: PASS.

### Task 8: Documentation And Commit

**Files:**
- Modify: `docs/ai_knowledge/03_deploy_const_ref.md`
- Modify: `docs/task/2026-05-19-const-ref-candidate-index-plan.md`

- [ ] **Step 1: Update ai_knowledge**

Document:
- Reference scanning records candidate syntax facts, not resolved definitions.
- Full scan order no longer affects reference discovery.
- Changed definitions are matched conservatively at impact lookup time.
- Candidate matching may over-report but must not under-report.

- [ ] **Step 2: Final verification**

Run:

```bash
./gradlew :main:test --tests 'com.sickworm.intellij.jugg.compiler.constref.*'
./gradlew :main:compileKotlin
```

Expected: both commands PASS.

- [ ] **Step 3: Commit only this task's files**

Run:

```bash
git status --short
git add main/src/main/java/com/sickworm/intellij/jugg/compiler/constref \
    main/src/test/java/com/sickworm/intellij/jugg/compiler/constref \
    docs/ai_knowledge/03_deploy_const_ref.md \
    docs/task/2026-05-19-const-ref-candidate-index-plan.md
git commit -m "[refactor] use candidate index for const ref impact lookup"
```

Expected: commit succeeds and does not include unrelated files.

## Self-Review

- Spec coverage: The plan removes definition dependency from reference scanning, preserves changed-definition diffing, adds companion const support, covers scan-order independence, and updates docs.
- Placeholder scan: No task contains TBD/TODO/later placeholders.
- Type consistency: `ConstReferenceCandidate`, `ConstReferenceOwnerKind`, and `EffectedConstRef` are named consistently across tasks.
