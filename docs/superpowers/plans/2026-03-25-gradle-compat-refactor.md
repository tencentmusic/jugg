# Gradle 多版本兼容性重构 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 修复 Gradle 7 manifest task 失败，并通过 `@JvmStatic` + build script 文本替换替代现有 200 行 Groovy AST 转换逻辑。

**Architecture:** 问题 A（companion 成员调用）在源文件加 `@JvmStatic` 解决；问题 B（跨类构造）完全在 `buildReadProjectInfoScript.gradle` 的生成阶段通过文本替换 + 工厂函数注入解决，源文件零改动。

**Tech Stack:** Kotlin, Groovy（build script），Gradle init script（`.kts`）

---

## 文件改动总览

| 文件 | 类型 | 改动 |
|---|---|---|
| `main/src/main/java/com/sickworm/intellij/jugg/project/dependency/DependencyDiffResult.kt` | 修改 | `@JvmStatic` × 3 |
| `main/src/main/java/com/sickworm/intellij/jugg/gradle/script/ProjectInfoSerializerInGradle.kt` | 修改 | `@JvmStatic` × 1 |
| `main/src/main/java/com/sickworm/intellij/jugg/project/data/JuggProjectInfoSerialize.kt` | 修改 | `@JvmStatic` × 2 |
| `main/src/main/java/com/sickworm/intellij/jugg/compiler/manifest/XmlAndroidManifestInfo.kt` | 修改 | `@JvmStatic` × 1 |
| `main/src/main/java/com/sickworm/intellij/jugg/project/data/JuggProjectInfo.kt` | 修改 | `@JvmStatic` × 1（`val virtualModule`） |
| `main/buildReadProjectInfoScript.gradle` | 修改 | 删除 `transformSourceForInitScript` 等旧逻辑；新增构造替换 + 工厂函数注入；加实验开关 |

测试文件只读，无需修改（现有测试覆盖所有场景）。

---

## Task 1：加 `@JvmStatic`——DependencyDiffResult.kt

**Files:**
- Modify: `main/src/main/java/com/sickworm/intellij/jugg/project/dependency/DependencyDiffResult.kt:21-27` 和 `:90-100`

- [ ] **Step 1: 在 `DependencyDiffResultSet.createEmpty` 前加 `@JvmStatic`**

  文件 `DependencyDiffResult.kt` 第 22 行，将：
  ```kotlin
      companion object {
          fun createEmpty() = DependencyDiffResultSet(
  ```
  改为：
  ```kotlin
      companion object {
          @JvmStatic
          fun createEmpty() = DependencyDiffResultSet(
  ```

- [ ] **Step 2: 在 `DependencyDiffResult.createEmpty` 和 `create` 前加 `@JvmStatic`**

  文件第 92 行和第 96 行，将：
  ```kotlin
      companion object {

          fun createEmpty(): DependencyDiffResult {
              return create(JuggProjectInfo(emptyMap()), JuggProjectInfo(emptyMap()))
          }

          fun create(
  ```
  改为：
  ```kotlin
      companion object {

          @JvmStatic
          fun createEmpty(): DependencyDiffResult {
              return create(JuggProjectInfo(emptyMap()), JuggProjectInfo(emptyMap()))
          }

          @JvmStatic
          fun create(
  ```

- [ ] **Step 3: 确认文件编译正常**

  ```bash
  cd main && ../gradlew :main:compileKotlin 2>&1 | tail -5
  ```
  预期：`BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

  ```bash
  git add main/src/main/java/com/sickworm/intellij/jugg/project/dependency/DependencyDiffResult.kt
  git commit -m "[optimize] Add @JvmStatic to DependencyDiffResult companion members for Gradle 7 compat"
  ```

---

## Task 2：加 `@JvmStatic`——其余四个文件

**Files:**
- Modify: `ProjectInfoSerializerInGradle.kt:91`
- Modify: `JuggProjectInfoSerialize.kt:28,84`
- Modify: `XmlAndroidManifestInfo.kt:14`
- Modify: `JuggProjectInfo.kt:83`

- [ ] **Step 1: `ProjectInfoSerializerInGradle.getJsonGenerator`**

  文件第 91 行，将：
  ```kotlin
          fun getJsonGenerator() : JsonGenerator {
  ```
  改为：
  ```kotlin
          @JvmStatic
          fun getJsonGenerator() : JsonGenerator {
  ```

- [ ] **Step 2: `JuggProjectInfoSerialize.serialize` 和 `deserialize`**

  文件第 28 行和第 84 行，分别在 `fun serialize` 和 `fun deserialize` 前加 `@JvmStatic`：
  ```kotlin
          @JvmStatic
          fun serialize(juggProjectInfo: JuggProjectInfo): JuggProjectInfoSerialize {
  ```
  ```kotlin
          @JvmStatic
          fun deserialize(projectInfoSerialize: JuggProjectInfoSerialize, isSkipVersionCheck: Boolean = false): JuggProjectInfo {
  ```

- [ ] **Step 3: `XmlAndroidManifestInfo.parse`**

  文件第 14 行，将：
  ```kotlin
          fun parse(file: File): XmlAndroidManifestInfo {
  ```
  改为：
  ```kotlin
          @JvmStatic
          fun parse(file: File): XmlAndroidManifestInfo {
  ```

- [ ] **Step 4: `ModuleInfo.virtualModule`**

  文件 `JuggProjectInfo.kt` 第 83 行，将：
  ```kotlin
          val virtualModule = ModuleInfo(
  ```
  改为：
  ```kotlin
          @JvmStatic
          val virtualModule = ModuleInfo(
  ```

  > 注意：`@JvmStatic` 作用于 companion `val` 合法，编译器会同时生成静态 getter。

- [ ] **Step 5: 确认编译正常**

  ```bash
  cd main && ../gradlew :main:compileKotlin 2>&1 | tail -5
  ```
  预期：`BUILD SUCCESSFUL`

- [ ] **Step 6: Commit**

  ```bash
  git add \
    main/src/main/java/com/sickworm/intellij/jugg/gradle/script/ProjectInfoSerializerInGradle.kt \
    main/src/main/java/com/sickworm/intellij/jugg/project/data/JuggProjectInfoSerialize.kt \
    main/src/main/java/com/sickworm/intellij/jugg/compiler/manifest/XmlAndroidManifestInfo.kt \
    main/src/main/java/com/sickworm/intellij/jugg/project/data/JuggProjectInfo.kt
  git commit -m "[optimize] Add @JvmStatic to companion members in script-bundled classes for Gradle 7 compat"
  ```

---

## Task 3：build script——companion 提取 + 构造替换 + 工厂函数注入（重写）

**注意**：之前的 Task 3 实现有误——删除了 companion 提取逻辑但 companion object 本身在 .kts 脚本中无法编译。本次彻底重写 doLast 块，加入简化版 companion 提取。

**Files:**
- Modify: `main/buildReadProjectInfoScript.gradle`

### 背景（执行前必读）

**为什么 companion 提取是必须的**：`.kts` init script 中所有顶层类都是脚本外部类的非静态内部类。`companion object` 在：
- Gradle 9 / Kotlin 1.9+：编译期报 "Object Companion captures the script class instance"
- Gradle 7 / Kotlin 1.5：JVM 后端 "wrong bytecode generated for static initializer" 崩溃
- Gradle 7 / Kotlin 1.5（无 @JvmStatic）：运行时 NoSuchMethodError

**companion 提取策略**：括号深度追踪法：
1. 逐行扫描，遇到 `companion object {` 开始记录深度
2. 深度归零时结束提取，将 companion 内容（去除 `companion object { }` 包装行）收集到 `companionMembers` 列表
3. 过滤掉每个成员的 `@JvmStatic` 行
4. 同时在 class body 中删除 companion object 块

**已知命名冲突处理**：`DependencyDiffResultSet.createEmpty` 和 `DependencyDiffResult.createEmpty` 同名。提取后重命名：
- `DependencyDiffResultSet.companion` 的 `createEmpty` → `dependencyDiffResultSetCreateEmpty`
- `DependencyDiffResult.companion` 的 `createEmpty` → `dependencyDiffResultCreateEmpty`

**限定调用重写**：提取完成后，对整个脚本做字符串替换：
```
DependencyDiffResultSet.createEmpty(  →  dependencyDiffResultSetCreateEmpty(
DependencyDiffResult.createEmpty(     →  dependencyDiffResultCreateEmpty(
DependencyDiffResult.create(          →  create(
ProjectInfoSerializerInGradle.getJsonGenerator(  →  getJsonGenerator(
JuggProjectInfoSerialize.serialize(   →  serialize(
JuggProjectInfoSerialize.deserialize( →  deserialize(
XmlAndroidManifestInfo.parse(         →  parse(
ModuleInfo.virtualModule              →  virtualModule
ModuleInfo.DEFAULT_BUILD_VARIANT      →  DEFAULT_BUILD_VARIANT
SigningConfig.EMPTY                   →  EMPTY
```

**新 doLast 流程**（顺序不可颠倒）：
1. 遍历 `willCollectFiles`，收集 imports；对每个文件：
   a. 去掉 package/import/@file:Suppress 行
   b. 用括号深度追踪提取 companion 内容（过滤 @JvmStatic），从 class body 中删除 companion 块
   c. 收集 `(classBody, companionMembers)` 对
2. 拼接：header → imports → 所有 companionMembers（顶层）→ 所有 class body → 末尾硬编码块
3. 做**限定调用重写**
4. 如果 `useFactoryFunctions = true`：做**构造调用替换**（`ClassName(` → `className(`），追加**工厂函数定义**
5. trailing-comma 清理
6. 写出文件

- [ ] **Step 1: 确认当前文件状态**

  先查看当前 `buildReadProjectInfoScript.gradle` 的结构（之前 Task 3 已经修改过，需确认当前状态）：
  ```bash
  wc -l main/buildReadProjectInfoScript.gradle
  grep -n "doLast\|useFactoryFunctions\|transformSourceForInitScript\|companion" \
    main/buildReadProjectInfoScript.gradle | head -20
  ```

- [ ] **Step 2: 完整替换 doLast 块**

  将当前 `doLast {` 到其对应的 `}` 整个替换为以下内容：

  ```groovy
  doLast {
      readProjectInfoKtsFile.delete()
      readProjectInfoKtsFile.parentFile.mkdirs()

      // Collect imports, class bodies, and companion members from all source files.
      // Companion members must be extracted to top level because companion object blocks
      // cause compilation errors in .kts scripts (Gradle 7: wrong bytecode; Gradle 9: captures script instance).
      List<String> companionMembers = new ArrayList<>()
      List<String> classContents = new ArrayList<>()
      Set<String> imports = new LinkedHashSet<>()

      willCollectFiles.forEach { File sourceFile ->
          List<String> lines = sourceFile.readLines()

          // Collect imports (skip jugg and gradle internal imports — already on classpath)
          lines.forEach { String line ->
              if (line.startsWith("import ")) {
                  String importClass = line.substring("import ".length()).trim()
                  if (!importClass.startsWith("com.sickworm.intellij.jugg") &&
                          !importClass.startsWith("org.gradle.api")) {
                      imports.add(importClass)
                  }
              }
          }

          // Extract companion object contents to top level using brace-depth tracking.
          // Also strips @JvmStatic (not valid on top-level functions).
          List<String> bodyLines = new ArrayList<>()
          boolean inCompanion = false
          int companionDepth = 0
          // Track the file-level companion owning class for rename disambiguation
          String currentClass = ""

          lines.forEach { String line ->
              String trimmed = line.trim()

              // Skip file-level directives
              if (trimmed.startsWith("package ") || trimmed.startsWith("import ") ||
                      trimmed.startsWith("@file:Suppress")) {
                  return
              }

              // Track current top-level class name for disambiguation
              def classMatch = (trimmed =~ /^(?:data |sealed |abstract |open )?class (\w+)/)
              if (classMatch.find()) {
                  currentClass = classMatch.group(1)
              }

              if (!inCompanion) {
                  if (trimmed == "companion object {" || trimmed.startsWith("companion object {")) {
                      inCompanion = true
                      companionDepth = 1
                      // Count any extra braces on the same line
                      line.each { char c ->
                          if (c == '{') companionDepth++
                          else if (c == '}') companionDepth--
                      }
                      // Correction: companion object { itself contributes one {
                      // We overcounted above (each char includes the one in "companion object {")
                      // Reset and recount properly
                      companionDepth = 1
                      int extraOpen = trimmed.count("{") - 1
                      int extraClose = trimmed.count("}")
                      companionDepth += extraOpen - extraClose
                      if (companionDepth <= 0) {
                          inCompanion = false
                      }
                      // Don't add this line to bodyLines (strip the companion wrapper)
                      return
                  }
                  bodyLines.add(line)
              } else {
                  // Inside companion object: track brace depth
                  trimmed.each { char c ->
                      if (c == '{') companionDepth++
                      else if (c == '}') companionDepth--
                  }

                  if (companionDepth <= 0) {
                      // Closing brace of companion object — skip it
                      inCompanion = false
                      return
                  }

                  // Skip @JvmStatic — not valid on top-level declarations
                  if (trimmed == "@JvmStatic") {
                      return
                  }

                  // Rename conflicting createEmpty functions
                  String memberLine = line
                  if (trimmed.startsWith("fun createEmpty(") || trimmed.startsWith("fun createEmpty():")) {
                      if (currentClass == "DependencyDiffResultSet") {
                          memberLine = line.replace("fun createEmpty(", "fun dependencyDiffResultSetCreateEmpty(")
                      } else if (currentClass == "DependencyDiffResult") {
                          memberLine = line.replace("fun createEmpty(", "fun dependencyDiffResultCreateEmpty(")
                      }
                  }

                  companionMembers.add(memberLine)
              }
          }

          classContents.add(bodyLines.join("\n"))
          classContents.add("\n")
      }

      // Build script content: header → imports → companion members (top-level) → class bodies → entry point
      StringBuilder finalContent = new StringBuilder()
      finalContent.append("""// use to read variables in gradle environment
  // usage: ./gradlew :app:assembleDebug -I readProjectInfo.gradle
  """)
      imports.forEach {
          finalContent.append("import ")
          finalContent.append(it)
          finalContent.append("\n")
      }
      finalContent.append("\n")

      // Top-level companion members (extracted from companion objects)
      companionMembers.forEach {
          finalContent.append(it)
          finalContent.append("\n")
      }
      finalContent.append("\n")

      classContents.forEach {
          finalContent.append(it)
          finalContent.append("\n")
      }
      finalContent.append("\n")

      finalContent.append("""
  gradle.allprojects {
      project.afterEvaluate {
          gradleApplicationInjector(rootProject).injectApplication(project)
      }
  }

  gradle.taskGraph.whenReady {
      println("Jugg: task graph ready for \${rootProject.projectDir}")
      gradleProjectInfoReaderManager(rootProject, gradle.includedBuilds).readAndSave()
  }
  """)
      finalContent.append("\n")

      String rawContent = finalContent.toString()

      // Rewrite qualified companion member calls (e.g. DependencyDiffResult.create() -> create())
      // Must run before factory function injection to handle all call sites.
      String processedContent = rawContent
          .replace("DependencyDiffResultSet.createEmpty(", "dependencyDiffResultSetCreateEmpty(")
          .replace("DependencyDiffResult.createEmpty(",    "dependencyDiffResultCreateEmpty(")
          .replace("DependencyDiffResult.create(",         "create(")
          .replace("ProjectInfoSerializerInGradle.getJsonGenerator(", "getJsonGenerator(")
          .replace("JuggProjectInfoSerialize.serialize(",  "serialize(")
          .replace("JuggProjectInfoSerialize.deserialize(","deserialize(")
          .replace("XmlAndroidManifestInfo.parse(",        "parse(")
          .replace("ModuleInfo.virtualModule",             "virtualModule")
          .replace("ModuleInfo.DEFAULT_BUILD_VARIANT",     "DEFAULT_BUILD_VARIANT")
          .replace("SigningConfig.EMPTY",                  "EMPTY")

      // Problem B fix: replace direct inner-class construction calls with top-level factory function calls.
      // Must run BEFORE appending factory function definitions to avoid replacing them too.
      // Regex (?<!class )ClassName( ensures class declarations are not affected.
      if (useFactoryFunctions) {
          [
              'InitScriptManifestXmlHelper',
              'GradleProjectInfoReader',
              'GradleDependencyDiffer',
              'ProjectInfoSerializerInGradle',
              'JuggPathManager',
              'ModuleBuildPathInfo',
              'LibraryDependency',
              'GradleApplicationInjector',
              'GradleProjectInfoReaderManager',
          ].each { String className ->
              String factoryName = className[0].toLowerCase() + className.substring(1)
              processedContent = processedContent.replaceAll("(?<!class )${className}\\(", "${factoryName}(")
          }

          // Append factory function definitions AFTER replacement so they are not themselves replaced
          processedContent += """
  // Top-level factory functions: route inner-class construction through top-level scope to avoid
  // Kotlin 1.5 (Gradle 7) NoSuchMethodError when constructing sibling inner classes from a lambda.
  fun initScriptManifestXmlHelper(manifestFile: File) = InitScriptManifestXmlHelper(manifestFile)
  fun gradleProjectInfoReader(rootProject: Project, lastProjectInfo: JuggProjectInfoSerialize?) = GradleProjectInfoReader(rootProject, lastProjectInfo)
  fun gradleDependencyDiffer(rootProject: Project, projectInfo: JuggProjectInfo) = GradleDependencyDiffer(rootProject, projectInfo)
  fun projectInfoSerializerInGradle(dataFile: File) = ProjectInfoSerializerInGradle(dataFile)
  fun juggPathManager(projectDir: File) = JuggPathManager(projectDir)
  fun moduleBuildPathInfo(projectRootDir: File, moduleRootDir: File, buildVariant: String) = ModuleBuildPathInfo(projectRootDir, moduleRootDir, buildVariant)
  fun libraryDependency(name: String, file: File) = LibraryDependency(name, file)
  fun libraryDependency(name: String, file: File, lastModifiedTime: Long, crc32: Long) = LibraryDependency(name, file, lastModifiedTime, crc32)
  fun gradleApplicationInjector(rootProject: Project) = GradleApplicationInjector(rootProject)
  fun gradleProjectInfoReaderManager(rootProject: Project, includedBuilds: Collection<IncludedBuild>) = GradleProjectInfoReaderManager(rootProject, includedBuilds)
  """
      }

      // Compat with Kotlin 1.4 grammar: remove trailing "," in last arguments
      Pattern pattern = Pattern.compile(", *(//.*)?\\n *\\)(.*)")
      Matcher matcher = pattern.matcher(processedContent)
      String finalResult = matcher.replaceAll(")\$2 \$1")

      // write it!
      readProjectInfoKtsFile.write(finalResult)
  }
  ```

  > **注意**：末尾 `gradle.allprojects {...}` 块中直接使用小写工厂函数名 `gradleApplicationInjector`、`gradleProjectInfoReaderManager`——这些名字在构造替换步骤中会被正确处理（替换列表包含这两个类）。

- [ ] **Step 3: 删除旧的辅助闭包和配置（如尚未删除）**

  确认已删除：`countChar`、`dropCompanionIndent`、`forceQualifiedCompanionMembers`、`flatMemberName`、`registerRewriteMember`、`mergeRewriteRules`、`preludeCompanionOwners`、`companionKeepOwners`、`transformSourceForInitScript`、`applyRewriteRules`

  **保留并简化 `sortWeight`**（去掉 weight=0 prelude 权重分支）：
  ```groovy
  def sortWeight = { File file ->
      String path = file.absolutePath.replace('\\', '/')
      if (path.endsWith('/project/JuggPathManager.kt')) return 1
      if (path.endsWith('/project/dependency/DependencyDiffResult.kt')) return 2
      if (path.endsWith('/compiler/manifest/XmlAndroidManifestInfo.kt')) return 3
      if (path.endsWith('/compiler/manifest/XmlParser.kt')) return 4
      if (path.endsWith('/gradle/script/ProjectInfoSerializerInGradle.kt')) return 5
      if (path.endsWith('/gradle/script/GradleDependencyDiffer.kt')) return 7
      if (path.endsWith('/gradle/script/GradleProjectInfoReaderManager.kt')) return 8
      if (path.contains('/gradle/script/')) return 6
      return 9
  }
  ```

- [ ] **Step 4: 重新生成脚本**

  ```bash
  cd /Users/wormchen/IdeaProjects/jugg/jugg_f1 && ./gradlew :main:buildReadProjectInfoScript 2>&1 | tail -10
  ```
  预期：`BUILD SUCCESSFUL`

- [ ] **Step 5: 验证生成脚本**

  ```bash
  # 无 companion object（应该为空输出）
  grep -n "companion object" main/src/main/resources/gradle/readProjectInfo.gradle.kts

  # 无 @JvmStatic（应该为空输出）
  grep -n "@JvmStatic" main/src/main/resources/gradle/readProjectInfo.gradle.kts

  # 工厂函数定义存在
  grep -c "fun initScriptManifestXmlHelper\|fun gradleProjectInfoReader\|fun juggPathManager" \
    main/src/main/resources/gradle/readProjectInfo.gradle.kts
  # 预期：3 或以上

  # createEmpty 冲突已处理（应看到 dependencyDiffResultSetCreateEmpty 和 dependencyDiffResultCreateEmpty）
  grep "CreateEmpty\|createEmpty" main/src/main/resources/gradle/readProjectInfo.gradle.kts
  ```

- [ ] **Step 6: 运行内容校验测试**

  ```bash
  cd /Users/wormchen/IdeaProjects/jugg/jugg_f1 && ./gradlew :main:test --tests "*ReadProjectInfoScriptContentTest*" 2>&1 | tail -15
  ```
  预期：全部 PASS。若失败查看断言内容（可能是 content test 检查特定 companion object 是否存在——如果测试期望 companion object 仍然在类内，需要了解其断言逻辑）。

- [ ] **Step 7: Commit**

  ```bash
  git add main/buildReadProjectInfoScript.gradle \
          main/src/main/resources/gradle/readProjectInfo.gradle.kts
  git commit -m "[refactor] Rewrite buildReadProjectInfoScript: companion extraction + factory injection"
  ```

---

## Task 4：运行完整兼容性测试

**Files:** 只读，不修改

- [ ] **Step 1: 运行所有兼容性测试**

  ```bash
  cd main && ../gradlew :main:test --tests "*ReadProjectInfo*" 2>&1 | tail -30
  ```

  预期结果：
  ```
  ReadProjectInfoGradle5CompatTest > generatedScript_shouldRunOnGradle541AndCollectFileDependencies SKIPPED
  ReadProjectInfoGradle7CompatTest > generatedScript_shouldRunOnGradle733AndCollectFileDependencies PASSED
  ReadProjectInfoGradle7CompatTest > generatedScript_shouldRunOnAndroidAppWithInjectApplicationEnabled PASSED
  ReadProjectInfoGradle7CompatTest > generatedScript_shouldRunManifestTaskOnAndroidAppWithInjectApplicationEnabled PASSED  ← 当前失败，重构后必须通过
  ReadProjectInfoGradle9CompatTest > generatedScript_shouldRunOnGradle921AndCollectFileDependencies PASSED
  ReadProjectInfoScriptContentTest > ... PASSED
  ```

  如果 Gradle 7 manifest task 测试仍失败，查看报错信息：
  - 仍是 `NoSuchMethodError: InitScriptManifestXmlHelper.<init>`：说明替换未生效，检查 Task 3 Step 5 的验证结果
  - 不同错误：根据堆栈定位新问题

- [ ] **Step 2: 确认全量测试通过后 Commit（如 Task 3 已 commit 则跳过）**

  ```bash
  git add main/src/main/resources/gradle/readProjectInfo.gradle.kts
  git commit -m "[bugfix] Fix Gradle 7 manifest task NoSuchMethodError via factory function injection"
  ```

---

## Task 5：实验——验证 `@JvmStatic` 是否能独立解决问题 B

这是可选实验，用于确认 `@JvmStatic` 的覆盖边界。

**Files:**
- Modify: `main/buildReadProjectInfoScript.gradle`（临时，实验后恢复）

- [ ] **Step 1: 将实验开关设为 false**

  在 `buildReadProjectInfoScript.gradle` 中将：
  ```groovy
  boolean useFactoryFunctions = true
  ```
  改为：
  ```groovy
  boolean useFactoryFunctions = false
  ```

- [ ] **Step 2: 重新生成脚本**

  ```bash
  cd main && ../gradlew :main:buildReadProjectInfoScript 2>&1 | tail -5
  ```

- [ ] **Step 3: 只跑 Gradle 7 manifest task 测试**

  ```bash
  cd main && ../gradlew :main:test \
    --tests "*ReadProjectInfoGradle7CompatTest.generatedScript_shouldRunManifestTaskOnAndroidAppWithInjectApplicationEnabled" \
    2>&1 | tail -20
  ```

  记录结果：
  - **PASSED** → `@JvmStatic` 同时解决了问题 B（意外发现，记录在 commit message 中）
  - **FAILED** → 符合预期，工厂函数是必需的

- [ ] **Step 4: 将开关恢复为 true**

  ```groovy
  boolean useFactoryFunctions = true
  ```

- [ ] **Step 5: 重新生成脚本，确认恢复正常**

  ```bash
  cd main && ../gradlew :main:buildReadProjectInfoScript && \
  ../gradlew :main:test --tests "*ReadProjectInfoGradle7CompatTest*" 2>&1 | tail -15
  ```
  预期：Gradle 7 全部 PASS。

- [ ] **Step 6: Commit 实验结论**

  根据 Step 3 结果选择 commit message：
  - 若 PASSED：`[docs] Experiment: @JvmStatic alone fixes Problem B; factory functions kept as defense-in-depth`
  - 若 FAILED（预期）：`[docs] Experiment: @JvmStatic does not fix Problem B; factory functions required`

  ```bash
  git add main/buildReadProjectInfoScript.gradle \
          main/src/main/resources/gradle/readProjectInfo.gradle.kts
  git commit -m "<上面选择的 message>"
  ```

---

## Task 6：删除实验开关

实验完成后清理。

**Files:**
- Modify: `main/buildReadProjectInfoScript.gradle`

- [ ] **Step 1: 删除实验开关及相关条件分支**

  在 `buildReadProjectInfoScript.gradle` 中：
  1. 删除 `boolean useFactoryFunctions = true` 及其注释
  2. 将 `if (useFactoryFunctions) { ... }` 替换为直接执行其内部代码（去掉条件判断，替换和注入逻辑无条件保留）

- [ ] **Step 2: 重新生成脚本并运行全量测试**

  ```bash
  cd main && ../gradlew :main:buildReadProjectInfoScript && \
  ../gradlew :main:test --tests "*ReadProjectInfo*" 2>&1 | tail -20
  ```
  预期：全部通过（Gradle 5 SKIPPED，其余全 PASS）。

- [ ] **Step 3: Commit**

  ```bash
  git add main/buildReadProjectInfoScript.gradle \
          main/src/main/resources/gradle/readProjectInfo.gradle.kts
  git commit -m "[refactor] Remove experiment switch, factory function injection is unconditional"
  ```
