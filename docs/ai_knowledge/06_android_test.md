# androidTest 支持指南

> 最后核对：2026-04-29
> 对应提交：`793d0a0f`、`0bd78f20`、`e36bfdac`、`39b54ba3`
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 能力范围

Jugg 目前支持 **app 模块的 androidTest**：

- app RunConfig 开启 `enableAndroidTest` 后，编译目标切到 `BuildTarget.ANDROID_TEST`。
- Gradle full compile 会同时产出 app APK 与 app test APK。
- 后续 app 源码与 `app/src/androidTest` 源码变更都可以进入 Jugg 增量编译。
- 部署阶段不引入 test APK 专用协议，继续复用当前 `install / code swap / full swap` 策略。
- 部署成功后执行 `am instrument`，并把 instrumentation 输出渲染到 Jugg console。

当前不覆盖：

- library 模块 androidTest。
- androidTest resource 增量编译。
- `androidTestAnnotationProcessor` / `androidTestKapt`。
- SM Test Runner 面板、Debug Executor、rerun failed tests。
- 常驻 test harness 或保活 test 进程内 redefine。

---

## 2. 核心模型

### 2.1 BuildTarget

入口：`main/src/main/java/com/sickworm/intellij/jugg/compiler/BuildTarget.kt`

| Target | 编译范围 | 启动策略 |
|--------|----------|----------|
| `APP` | app variant | `am start` |
| `ANDROID_TEST` | app variant + androidTest variant | `am instrument` |

`BuildTarget.ANDROID_TEST` 只是一层运行会话 tag，不把 androidTest 当成独立 app 运行模式。普通 app run 仍默认走 `APP`，避免影响既有行为。

### 2.2 test APK 识别

入口：

- `main/src/main/java/com/sickworm/intellij/jugg/apk/ApkInfo.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/apk/ApkInfoReader.kt`

`ApkInfo` 通过 APK manifest 的 `<instrumentation>` 元素识别 test APK：

| 字段 | 含义 |
|------|------|
| `instrumentationTargetPackage` | 被测 app package；非空表示这是 test APK |
| `instrumentationRunner` | test APK manifest 中声明的 runner |
| `isTestApk` | `instrumentationTargetPackage != null` |

下游不要靠路径或文件名猜测 test APK，应优先使用 `ApkInfo.isTestApk`。

### 2.3 androidTest ModuleInfo

入口：

- `main/src/main/java/com/sickworm/intellij/jugg/project/data/JuggProjectInfo.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/gradle/script/GradleProjectInfoReader.kt`

阶段 2 最终采用 **独立 synthetic ModuleInfo**，不是早期方案里的“合入 app ModuleInfo”：

| 字段 | 当前约定 |
|------|----------|
| `name` | `${appModuleName}.androidTest` |
| `moduleType` | `ModuleInfo.Type.Library` |
| `buildVariant` | `debugAndroidTest` |
| `applicationId` | test APK applicationId |
| `instrumentationTargetPackage` | app applicationId |
| `sourceDirs` | app module 的 `src/androidTest` Java/Kotlin 源码目录 |
| `moduleDependencies` | app module |

判断 androidTest module 使用 `ModuleInfo.isAndroidTestModule`。

---

## 3. 编译链路

### 3.1 Gradle full compile

入口：

- `main/src/main/java/com/sickworm/intellij/jugg/gradle/compile/AndroidTestCommandDeriver.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/gradle/compile/BaseBuildCommandHelper.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompileHelper.kt`

`AndroidTestCommandDeriver` 基于用户 App RunConfig 中已有的 app 编译命令派生 androidTest 命令，不回写 RunConfig。

典型规则：

| app 配置 | androidTest 运行时派生 |
|----------|------------------------|
| `:app:assembleDebug` | `:app:assembleDebug :app:assembleDebugAndroidTest` |
| `app/build/outputs/apk/debug/*.apk` | 追加 `app/build/outputs/apk/androidTest/debug/*.apk` |

`base_build_cmd.txt` 已升级为 `BaseBuildCmdRecord{compileCommand, buildTarget}`。旧单行文本兼容解析为 `BuildTarget.APP`。当当前 target 与记录 target 不一致时，必须触发 Gradle full compile，避免 app/test 模式复用错误产物。

### 3.2 增量编译

入口：

- `idea/src/main/java/com/sickworm/intellij/jugg/project/CompileContextManager.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/ModuleApkBelongsUtils.kt`

`CompileContextManager` 的过滤规则：

- `BuildTarget.APP`：继续过滤 `.androidTest` module。
- `BuildTarget.ANDROID_TEST`：纳入 `.androidTest` module。
- `.test` / `.unitTest` 在两种 target 下都继续过滤。

`ModuleApkBelongsUtils` 增加 Step 0：`isAndroidTestModule` 优先路由到匹配 `instrumentationTargetPackage` 的 test APK。只有找不到 test APK 时才落回普通 base APK 兜底。

---

## 4. 运行入口

### 4.1 IDE RunConfig 与 gutter

入口：

- `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggRunConfigurationOptions.kt`
- `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggAndroidTestRunConfiguration.kt`
- `idea/src/ide_entry/java/com/sickworm/intellij/jugg/ide/JuggAndroidTestLineMarkerContributor.kt`

入口分层：

| 配置 | 职责 |
|------|------|
| `JuggRunConfiguration.enableAndroidTest` | 控制 app RunConfig 是否允许 androidTest 编译与运行 |
| `JuggAndroidTestRunConfiguration` | 承载 `testClass` / `testMethod` / `instrumentationRunner` / `extraArgs` |
| `JuggAndroidTestLineMarkerContributor` | 在 app `src/androidTest` 的 JUnit test 上提供 Jugg gutter |

gutter 约束：

- 只支持路径包含 `/app/src/androidTest/` 的测试。
- 识别 `org.junit.Test` 与 `org.junit.jupiter.api.Test`。
- Java / Kotlin PSI 都支持，gutter 通过测试注解 owner 判定，不依赖单一 PSI 类型。
- 未开启 `enableAndroidTest` 时只弹 Notification，引导用户打开 App RunConfig，不自动修改配置。

### 4.2 AndroidTestRunSpec 传递

入口：

- `main/src/main/java/com/sickworm/intellij/jugg/deploy/instrument/AndroidTestRunSpec.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/JuggManager.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggConfigurationRunner.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/ide/logic/JuggRunningTask.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/DeployOptions.kt`

主链路：

```text
JuggAndroidTestLineMarkerContributor
  -> JuggAndroidTestRunConfiguration
  -> JuggAndroidTestRunSpecFactory
  -> JuggManager.runTask(appOptions, androidTestRunSpec)
  -> JuggConfigurationRunner
  -> JuggRunningTask
  -> DeployOptions(androidTestRunSpec)
  -> JuggDeployerHelper
```

普通 app run 的 `androidTestRunSpec = null`，行为不变。

---

## 5. 部署与 instrumentation

### 5.1 部署策略

入口：

- `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployTask.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelper.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/ApkInstallOrder.kt`

部署阶段继续按 `applicationId` 分组。app APK 和 test APK 都走现有 `JuggDeployTask.perform()`，不新增 test APK 专用 deploy type。

install 顺序由 `ApkInstallOrder.sortedForInstall()` 保证 app APK 先于 test APK。

### 5.2 am instrument

入口：

- `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/TestLauncher.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/AdbCmdHelper.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/instrument/InstrumentCommandBuilder.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/instrument/InstrumentationOutputParser.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/instrument/InstrumentationConsoleRenderer.kt`

`InstrumentCommandBuilder` 输出形态：

```text
am instrument -w -r [-e class <testClass>[#<testMethod>]] [-e <key> <value>]* <testPkg>/<runner>
```

`TestLauncher` 对每台设备串行执行 instrumentation。任一设备出现以下情况，整体 Run 失败：

- instrumentation command 非 0 退出。
- `INSTRUMENTATION_ABORTED`。
- test result 为 `FAILURE` / `ERROR` / `ASSUMPTION_FAILURE`。
- 设备执行过程中抛异常。

---

## 6. 测试入口

禁止运行完整测试套件。androidTest 支持相关回归优先跑定向测试。

### 6.1 main 模块

| 测试文件 | 覆盖点 |
|----------|--------|
| `main/src/test/java/com/sickworm/intellij/jugg/gradle/compile/AndroidTestCommandDeriverTest.kt` | Gradle 命令与 APK glob 派生 |
| `main/src/test/java/com/sickworm/intellij/jugg/gradle/compile/BaseBuildCmdRecordTest.kt` | target 记录与旧格式兼容 |
| `main/src/test/java/com/sickworm/intellij/jugg/apk/ApkInfoInstrumentationTest.kt` | test APK instrumentation manifest 读取 |
| `main/src/test/java/com/sickworm/intellij/jugg/project/data/ModuleInfoAndroidTestTest.kt` | `isAndroidTestModule` |
| `main/src/test/java/com/sickworm/intellij/jugg/project/data/JuggProjectInfoSerializerAndroidTestTest.kt` | project info 新字段序列化兼容 |
| `main/src/test/java/com/sickworm/intellij/jugg/gradle/script/GradleProjectInfoReaderAndroidTestTest.kt` | Gradle 侧 synthetic module 生成 |
| `main/src/test/java/com/sickworm/intellij/jugg/ModuleApkBelongsUtilsAndroidTestTest.kt` | androidTest module 到 test APK 路由 |
| `main/src/test/java/com/sickworm/intellij/jugg/deploy/instrument/InstrumentCommandBuilderTest.kt` | `am instrument` 命令构造 |
| `main/src/test/java/com/sickworm/intellij/jugg/deploy/instrument/InstrumentationOutputParserTest.kt` | instrumentation 输出解析 |
| `main/src/test/java/com/sickworm/intellij/jugg/deploy/instrument/InstrumentationConsoleRendererTest.kt` | console 渲染 |
| `main/src/test/java/com/sickworm/intellij/jugg/deploy/run/ApkInstallOrderTest.kt` | app/test APK install 顺序 |
| `main/src/test/java/com/sickworm/intellij/jugg/ide/logic/AndroidTestRunSpecPropagationTest.kt` | spec 传递链路 |

### 6.2 idea 模块

| 测试文件 | 覆盖点 |
|----------|--------|
| `idea/src/test/java/com/sickworm/intellij/jugg/project/CompileContextManagerAndroidTestFilterTest.kt` | `.androidTest` module 过滤规则 |
| `idea/src/test/java/com/sickworm/intellij/jugg/ide/JuggAndroidTestLineMarkerContributorTest.kt` | gutter 路径与入口边界 |
| `idea/src/test/java/com/sickworm/intellij/jugg/ide/JuggAndroidTestRunSpecFactoryTest.kt` | RunConfig options 到 spec |
| `idea/src/test/java/com/sickworm/intellij/jugg/deploy/run/DeployOptionsAndroidTestSpecTest.kt` | DeployOptions 携带 spec |

### 6.3 常用定向命令

```bash
./gradlew :main:test --tests "com.sickworm.intellij.jugg.gradle.compile.AndroidTestCommandDeriverTest"
./gradlew :main:test --tests "com.sickworm.intellij.jugg.ModuleApkBelongsUtilsAndroidTestTest"
./gradlew :main:test --tests "com.sickworm.intellij.jugg.deploy.instrument.InstrumentationOutputParserTest"
./gradlew :main:test --tests "com.sickworm.intellij.jugg.deploy.run.ApkInstallOrderTest"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.project.CompileContextManagerAndroidTestFilterTest"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.ide.JuggAndroidTestRunSpecFactoryTest"
```

必要时可做编译验证：

```bash
./gradlew :idea:compileKotlin
```

---

## 7. 排查口径

### 7.1 gutter 不出现

优先确认：

1. 文件路径是否在 `/app/src/androidTest/` 下。
2. test 方法或类是否有 `org.junit.Test` / `org.junit.jupiter.api.Test`。
3. 当前是否为 app 模块 androidTest；library androidTest 当前不支持。
4. 如果是 Kotlin 文件，确认 PSI 能正确识别注解 owner；当前实现同时兼容 `getAnnotations()` 与 `getAnnotationEntries()`。

### 7.2 点击 gutter 后没有真正跑 test

优先确认：

1. App RunConfig 是否开启 `enableAndroidTest`。
2. 是否已经用 `BuildTarget.ANDROID_TEST` 做过一次 Gradle full compile。
3. `DeployOptions.androidTestRunSpec` 是否非空。
4. `deployData.apks` 中是否存在 `ApkInfo.isTestApk == true` 的 test APK。

### 7.3 增量变更没有进入 test APK

优先确认：

1. 当前 `BaseBuildCmdRecord.buildTarget` 是否为 `ANDROID_TEST`。
2. `CompileContextManager` 是否纳入 `.androidTest` module。
3. `ModuleInfo.instrumentationTargetPackage` 是否非空。
4. `ModuleApkBelongsUtils` 是否把 androidTest module 路由到 test APK。

### 7.4 instrumentation 失败

优先确认：

1. test APK manifest 中是否有正确的 `instrumentationRunner`。
2. `InstrumentCommandBuilder` 生成的 `<testPkg>/<runner>` 是否正确。
3. `InstrumentationOutputParser` 是否解析到了 `ABORTED`、`FAILURE`、`ERROR` 或 `ASSUMPTION_FAILURE`。

---

## 8. 变更历史

- 2026-04-29：新增正式知识库文档，沉淀 androidTest 支持的当前实现、边界、链路和测试入口。
