# Jugg androidTest 支持——阶段 2 设计文档

> 创建时间：2026-04-21
> 状态：设计定稿，待实现
> 前置：阶段 1 已在 7059f42a / 0fcc1cd9 实现
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 目标

让 androidTest 源码的变更走 Jugg 增量编译，而不是每次都触发 Gradle full compile。

- 首次切换到 `ANDROID_TEST` 模式仍触发 Gradle full compile
- 之后 app 源码或 androidTest 源码的任何变更均走增量编译
- 增量编译后的部署策略与 app 侧一致（code swap 优先，降级时 full swap 或 install）

---

## 2. 核心决策

| 决策项 | 结论 | 理由 |
|---|---|---|
| androidTest 是否独立 ModuleInfo | **是，独立 ModuleInfo** | 编译流程（`doModuleCompile` 是 module 粒度）、APK 归属（`moduleBelongsApkMap`）、依赖链路均与 module 对齐，独立后全部复用现有多模块多 APK 路径 |
| moduleType | 复用 `ModuleInfo.Type.Library` | 不新增枚举，用新字段 `instrumentationTargetPackage` 区分 |
| androidTest ModuleInfo 在哪里生成 | **Gradle 侧**（`GradleProjectInfoReader`） | `JuggProjectInfo` fixture 完整，单元测试好写；`ModuleBuildPathInfo` 路径自动对齐 `debugAndroidTest` variant |
| Merger 层 | 零改动 | Gradle 有、IDE 无的模块走既有 `missingModules` 直通路径（`JuggProjectInfoMerger.kt:223-228`） |
| buildTarget 从哪里读 | `BaseBuildCommandHelper.getBaseBuildCmdRecord()?.buildTarget` | 阶段 1 已持久化，`CompileContextManager` 直接读 |
| 部署路由 | `JuggDeployTask.run()` 已按 `applicationId` groupBy，零改动 | test apk 有独立 `applicationId`，天然落入不同分组 |

---

## 3. 数据模型

### 3.1 `ModuleInfo` 新增字段

```kotlin
data class ModuleInfo(
    // ...现有字段不变...
    val instrumentationTargetPackage: String? = null,  // 末尾追加
) {
    /** Returns true when this module represents an androidTest source set. */
    val isAndroidTestModule: Boolean get() = instrumentationTargetPackage != null
}
```

- `null` → 普通 app/library module，行为与现在完全一致
- 非 `null` → androidTest module，值为被测 app 的 `applicationId`（如 `"com.example.app"`）

**序列化注意**：根据 `ModuleInfo` 注释中的更新清单，以下文件需同步追加字段（旧 JSON 缺字段 → 反序列化为 `null`，向后兼容）：
- `JuggProjectInfoSerialize`
- `ProjectInfoSerializerInGradle`
- `JuggProjectInfoMerger`
- `CmdLineContextManager`
- `LibrariesBackupHelper`

### 3.2 androidTest ModuleInfo 的字段约定

Gradle 侧生成时的字段值：

| 字段 | 值 |
|---|---|
| `name` | `"${appModuleName}.androidTest"` |
| `moduleType` | `ModuleInfo.Type.Library` |
| `moduleRootDir` | 与 app module 相同（androidTest source set 不是独立目录） |
| `buildVariant` | `"debugAndroidTest"` |
| `applicationId` | `testApplicationId` 或 `"${appApplicationId}.test"` |
| `instrumentationTargetPackage` | app 的 `applicationId` |
| `sourceDirs` | `androidExt["sourceSets"]["androidTest"]` 的 Java/Kotlin 目录 |
| `libraryDependencies` | `androidTestImplementation` 配置解析 |
| `moduleDependencies` | `[appModuleName]` |

`ModuleBuildPathInfo(buildVariant = "debugAndroidTest")` 后路径自动对齐，零改动：
- `kotlinClassPath` → `build/tmp/kotlin-classes/debugAndroidTest/`
- `javaClassPath` → `build/intermediates/javac/debugAndroidTest/classes/`

---

## 4. 改动面总览

### 4.1 `main` 模块

| 改动点 | 文件 | 性质 |
|---|---|---|
| `ModuleInfo.instrumentationTargetPackage` | `main/.../project/data/JuggProjectInfo.kt` | 新增字段（默认 null） |
| `ModuleInfo.isAndroidTestModule` | 同上 | 新增便捷属性 |
| `GradleProjectInfoReader.getProjectInfo` | `main/.../gradle/script/GradleProjectInfoReader.kt` | Application 模块后额外生成 androidTest ModuleInfo |
| `GradleProjectInfoReader.getAndroidTestModuleInfo` | 同上 | 新增私有方法 |
| `ProjectInfoSerializerInGradle` | `main/.../gradle/script/ProjectInfoSerializerInGradle.kt` | 序列化新增字段，向后兼容 |
| `JuggProjectInfoSerialize` | 序列化类 | 同上 |
| `ModuleApkBelongsUtils.getModuleApkBelongs` | `main/.../ModuleApkBelongsUtils.kt` | 新增 Step 0：`isAndroidTestModule` → test ApkFileUnit |

### 4.2 `idea` 模块

| 改动点 | 文件 | 性质 |
|---|---|---|
| `CompileContextManager.initModuleRoots` | `idea/.../project/CompileContextManager.kt:345-351` | `.androidTest` 过滤条件化（`buildTarget != ANDROID_TEST` 时才过滤） |
| `buildTarget` 注入 | `CompileContextManager` 调用方（`JuggCompileHelper`） | 从 `BaseBuildCommandHelper.getBaseBuildCmdRecord()` 读取后传入 |

### 4.3 零改动（复用现有路径）

- `JuggProjectInfoMerger`：`missingModules` 直通路径天然处理 androidTest ModuleInfo
- `BaseCompileContext.findApplicationModule` / `findDynamicFeatureModules`：按 `moduleType` 过滤，androidTest（`Library`）天然不被选中
- `BaseCompiler.splitApkAndCompile`：`moduleBelongsApkMap` 路由后自动携带正确 apk 路径
- `JuggDeployTask.run()`：`groupBy(applicationId)` 已将 test apk 独立分组

---

## 5. 关键流程

### 5.1 Gradle full compile 后的 ModuleInfo 建立

```
GradleProjectInfoReader.getProjectInfo()
  └─ forEach subprojects:
       └─ getModuleInfo(project) → appModuleInfo
       └─ if Application: getAndroidTestModuleInfo(project, appModuleInfo)
            └─ 读 sourceSets["androidTest"] source roots
            └─ 读 testApplicationId / 推导 "${appId}.test"
            └─ 读 androidTestImplementation 依赖
            └─ 返回 ModuleInfo(
                   name = "app.androidTest",
                   buildVariant = "debugAndroidTest",
                   applicationId = "com.example.app.test",
                   instrumentationTargetPackage = "com.example.app",
               )
            ↓
JuggProjectInfoMerger.doMerge()
  └─ missingModules（Gradle 有 IDE 无）直通 → mergedModules 包含 androidTest ModuleInfo
```

### 5.2 增量编译路由

```
androidTest 源文件变更
  └─ 文件监听器（sourceDirs 包含 src/androidTest/java）→ ChangedFile
  └─ CompileContextManager（buildTarget=ANDROID_TEST，不过滤 .androidTest 模块）
  └─ moduleBelongsApkMap[androidTestModuleInfo] = test ApkFileUnit
  └─ DexCompiler.doDex → CompileOutput(apkPath = test apk 路径)
  └─ JuggDeployTask.run()
       └─ packages["com.example.app.test"] → optimisticSwap
```

### 5.3 APK 路由优先级（`ModuleApkBelongsUtils`）

```
Step 0（新增）: instrumentationTargetPackage != null → test ApkFileUnit
Step 1（既有）: featureSplit manifest 匹配           → dynamic feature ApkFileUnit
Step 2（既有）: 依赖 application module              → base ApkFileUnit
Step 3（既有）: 兜底                                  → base ApkFileUnit
```

---

## 6. 测试策略

### 6.1 main 模块单元测试（TDD 强制前置）

| 测试文件 | 核心用例 |
|---|---|
| `ModuleInfoAndroidTestTest.kt` | `isAndroidTestModule` 在 `instrumentationTargetPackage = null` 时为 false；非 null 时为 true |
| `JuggProjectInfoSerializerAndroidTestTest.kt` | 新字段序列化往返；旧 JSON 缺字段 → null |
| `ModuleApkBelongsUtilsAndroidTestTest.kt` | androidTest module → test ApkFileUnit；app module → base ApkFileUnit；test apk 不存在时兜底 base apk |
| `GradleProjectInfoReaderAndroidTestTest.kt` | Application 模块生成 androidTest ModuleInfo；`buildVariant = "debugAndroidTest"`；`applicationId = testApplicationId`；`instrumentationTargetPackage = appApplicationId`；无 androidTest source set 时返回 null |

### 6.2 idea 模块集成测试

| 测试文件 | 核心用例 |
|---|---|
| `CompileContextManagerAndroidTestFilterTest.kt` | `buildTarget=APP` 时 `.androidTest` 被过滤；`buildTarget=ANDROID_TEST` 时被纳入；`.test` / `.unitTest` 在两种 target 下均被过滤 |
| `AndroidTestIncrementalCompileFlowTest.kt`（选做） | mock androidTest ModuleInfo + test ApkInfo → 改 androidTest 源文件 → 验证 deploy 路由到 test apk |

### 6.3 YAGNI——阶段 2 不做

- 不支持 flavor × androidTest 多 variant 组合（只覆盖 `debugAndroidTest`）
- 不支持 `androidTestAnnotationProcessor` / `androidTestKapt`
- 不做 androidTest resource 增量（res 变更仍走 Gradle full compile）
- 不改 `JuggJvmtiAgentManagerHelper.pushAgentToApps`

### 6.4 回归基线

- `buildTarget = APP` 时所有行为与阶段 1 完全一致
- 既有多 APK（dynamic feature）路由不受影响（Step 0 只处理 `isAndroidTestModule == true` 的 module）
- `JuggProjectInfoMerger.missingModules` 直通路径既有测试继续覆盖

---

## 7. 变更历史

- 2026-04-21：初版，阶段 2 设计定稿。
