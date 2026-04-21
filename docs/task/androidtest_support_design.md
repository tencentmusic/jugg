# Jugg androidTest 支持设计

> 最后更新：2026-04-20
> 状态：brainstorming 已全部定稿（设计节 1-6）
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 目标与范围

### 1.1 目标

让 Jugg 支持 Android 设备 instrumentation 测试（androidTest）的运行，包括：
Gradle 回退编译、两 APK install、`am instrument` 启动，以及未来阶段的 androidTest 源码增量编译与 test 类 code swap 热更。

### 1.2 三阶段递进

| 阶段 | 目标 | 本次 spec 覆盖 | 本次 plan 覆盖 |
|------|------|----------------|----------------|
| 阶段 1 | Gradle 回退 full compile + 双 APK install + `am instrument` | ✅ | ✅ |
| 阶段 2 | androidTest 源码走 Jugg 增量编译 | ✅（spec 预留接口） | ❌ |
| 阶段 3 | androidTest 类的 code swap 热更 | ✅（spec 预留接口） | ❌ |

本次 brainstorming 产出端到端 spec，实现计划仅覆盖阶段 1。

---

## 2. 已对齐的核心决策

### 2.1 模型设计

| 决策项 | 结论 | 理由 |
|---|---|---|
| **BuildTarget 抽象** | 引入 `BuildTarget { APP, ANDROID_TEST }` 作为轻量 tag + 策略容器 | 与 Gradle 真实模型对齐（androidTest 是 source set 而非独立 module），避免 IDE/Gradle 数据源冲突 |
| **BuildTarget 承载信息** | 4 类：`variantSuffix` / `includeAndroidTestSourceSet` / `launchStrategy`；**不承载 ApkInfo** | ApkInfo 列表继续由 `ICompileContext.getApkInfos()` 提供，复用既有多 APK 能力 |
| **两模式互为超集关系** | app 模式只编译 app；androidTest 模式同时编译 app + androidTest | 避免"只跑 androidTest 不跑 app"导致的不一致 |
| **增量按 APK 归属** | 改 app 代码 → 增量 app apk；改 androidTest 代码 → 增量 test apk | 复用既有按 applicationId 分组的多 APK 增量部署能力（`JuggDeployTask.kt:90`） |
| **目录隔离** | 零隔离 —— 全部复用既有 `JuggPathManager` 目录 | 两模式互为超集关系 + 模式切换必 full compile → 不存在互相覆盖 |

### 2.2 RunConfig 设计

| 决策项 | 结论 |
|---|---|
| **App RunConfig（既有）** | `JuggRunConfiguration` 新增 `enableAndroidTest: Boolean` 开关；默认 false，保持老行为 |
| **Test RunConfig（新增）** | `JuggAndroidTestRunConfiguration` 独立 ConfigurationType；只承载 launch 参数（testClass / testMethod / instrumentationRunner / extraArgs） |
| **职责分层** | "是否编译 androidTest" 由 App RunConfig 的开关管；"启动哪个 test" 由 Test RunConfig 管 |
| **gutter icon** | 新增 `RunLineMarkerContributor`，识别 @Test → 创建 `JuggAndroidTestRunConfiguration` |
| **未开启场景** | 用户未勾选 `enableAndroidTest` 时点击 @Test gutter → 弹窗提示开启，并提供"一键开启 + 运行"按钮 |

### 2.3 一致性保障机制

- `base_build_cmd.txt` 从单字符串升级为结构化 `BaseBuildCmdRecord{compileCommand, buildTarget}`（JSON），向后兼容旧单行文本（解析为 `APP`）
- 当前 RunConfig 的 target ≠ `BaseBuildCmdRecord.buildTarget` → `JuggCompileHelper.preprocessIncrementalCompile` 增加 `isBuildTargetChanged` 判据 → 强制 Gradle 回退 full compile

### 2.4 编译口径（源码归属）

- **口径 B**：`.androidTest` IDE module 不新建独立 `ModuleInfo`，而是把 androidTest source set 合入 app 的 `ModuleInfo`（阶段 2 实施）
- `CompileContextManager` 对 `.androidTest` 后缀模块的过滤逻辑条件化：仅在 `BuildTarget == ANDROID_TEST` 时纳入

---

## 3. 架构关键点

### 3.1 BuildTarget 枚举草案

```kotlin
enum class BuildTarget(
    val variantSuffix: String,              // "" | "AndroidTest"
    val includeAndroidTestSourceSet: Boolean,
    val launchStrategy: LaunchStrategy,
) {
    APP("",              false, LaunchStrategy.AM_START),
    ANDROID_TEST("AndroidTest", true, LaunchStrategy.AM_INSTRUMENT);
}

enum class LaunchStrategy { AM_START, AM_INSTRUMENT }
```

### 3.2 职责划分总表

| 组件 | 归属 | 职责 |
|---|---|---|
| `JuggRunConfiguration`（老） | 既有 + 新增字段 | App 运行配置 + `enableAndroidTest: Boolean` 开关 |
| `JuggAndroidTestRunConfiguration`（新） | 新增独立 ConfigurationType | 测试启动参数（testClass / testMethod / runner / extraArgs） |
| `RunLineMarkerContributor`（新） | 新增 | @Test 注解 → 创建 Test RunConfig |
| `BaseBuildCommandHelper`（既有） | 升级 | `BaseBuildCmdRecord{compileCommand, buildTarget}` |
| `JuggCompileHelper`（既有） | 新增判据 | `isBuildTargetChanged` → 强制 full compile |
| `CompileContextManager`（既有） | 条件化过滤 | target=ANDROID_TEST 时纳入 `.androidTest` 模块 |
| `ICompileContext`（既有） | 新增字段 | `buildTarget: BuildTarget`（默认 APP） |
| `JuggDeployTask`（既有） | 不改 | 已支持 groupBy applicationId 多 APK install |

### 3.3 风险分析对照

| 风险 | 决策 |
|---|---|
| A. 模式切换一致性 | 通过 `BaseBuildCmdRecord.buildTarget` 对比判定；切换即强制 full compile |
| B. androidTest 模块过滤 | `CompileContextManager` 条件化（target=ANDROID_TEST 时纳入） |
| C. 源码归属 | 按 module → apk 映射归到 test ApkInfo（阶段 2 实施） |
| D. 启动策略 | `BuildTarget.launchStrategy`：APP→am start；ANDROID_TEST→am instrument |

---

## 4. 代码侵入面（阶段 1）

仅列改动点，不含新增独立类。

| 改动点 | 文件路径 | 改动性质 |
|---|---|---|
| BuildTarget 枚举 | 新增 `main/.../compiler/BuildTarget.kt` | 新增 |
| `ICompileContext` | `main/.../compiler/ICompileContext.kt`（字段新增） | 新增字段（默认值保持老行为） |
| `BaseCompileContext` | `main/.../project/BaseCompileContext.kt` | 新增构造参数（默认 APP） |
| `BaseBuildCommandHelper` 升级 | `main/.../gradle/compile/BaseBuildCommandHelper.kt` | API 扩展 + 向后兼容解析 |
| `base_build_cmd.txt` 格式 | 同上 | 升级为 JSON；兼容旧单行 |
| `JuggCompileHelper` | `idea/.../compiler/JuggCompileHelper.kt` | 新增 target 变更判据 + 运行时命令派生 |
| `AndroidTestCommandDeriver` | 新增 `main/.../gradle/compile/AndroidTestCommandDeriver.kt` | 新增纯函数对象 |
| `ApkInfo` 字段 | `main/.../apk/ApkInfo.kt` | 新增两个可空字段（`instrumentationTargetPackage` / `instrumentationRunner`），默认 null |
| `ApkInfoReader` | `main/.../apk/ApkInfoReader.kt` | 解析 manifest `<instrumentation>` 填充字段 |
| `ApkInfoSerializer` | `main/.../apk/ApkInfoSerializer.kt` | 扩展字段，向后兼容（旧 JSON 缺字段 → null） |
| `JuggRunConfiguration` | `idea/src/ide_entry/.../JuggRunConfiguration.kt` | 新增 `enableAndroidTest` 字段 |
| `JuggAndroidTestRunConfiguration` | 新增 | 全新独立 ConfigurationType/Factory/Editor/Options |
| `RunLineMarkerContributor` | 新增 | 新增 IDE 扩展点 |
| App launch 侧 | `idea/.../deploy/run/*` | 按 `launchStrategy` 分派（阶段 1 需接入 am instrument） |

---

## 5. 设计节 2 定稿：Gradle 回退与 full compile 命令编排

### 5.1 核心思路

用户 RunConfig 里的 `compileCommand` 与 `outputApkName` 保持 app 场景配置不变；`enableAndroidTest=true` 时由 Jugg **运行时派生** androidTest 模式的命令与 APK glob，不污染持久化，UI 零感知。

### 5.2 命令派生规则

新增 `AndroidTestCommandDeriver`（`main/.../gradle/compile/`）。纯函数对象：

```kotlin
object AndroidTestCommandDeriver {
    fun deriveCompileCommand(appCompileCommand: String): String
    fun deriveOutputApkName(appOutputApkName: String): String
}
```

**`compileCommand` 派生规则**：

1. 保留原命令（保证 app apk 正常产出）
2. 正则 `:(\w+):assemble(\w+)` 解析出 `module` 与 variant 名，追加 `:<module>:assemble<Variant>AndroidTest`
3. 幂等：命令已含 `AndroidTest` 字样则不再追加
4. 无法识别 assemble 命令（如 bundle/install）：回退为命令末尾附加 `:app:assembleDebugAndroidTest`，并记录 warning（首版可接受，后续演进为更严格的校验）

| 输入 | 输出 |
|---|---|
| `./gradlew :app:assembleDebug` | `./gradlew :app:assembleDebug :app:assembleDebugAndroidTest` |
| `./gradlew :app:assembleDevelopmentDebug` | `./gradlew :app:assembleDevelopmentDebug :app:assembleDevelopmentDebugAndroidTest` |
| `./gradlew :app:bundleDebug` | `./gradlew :app:bundleDebug :app:assembleDebugAndroidTest`（带 warning） |

**`outputApkName` 派生规则**：

1. 保留原 `outputApkName`（`;` 分隔）
2. 对每条 `<module>/build/outputs/apk/<variant>/*.apk` 形态追加 `<module>/build/outputs/apk/androidTest/<variant>/*.apk`
3. 无法识别模式：退化为附加 `*-androidTest.apk` glob 或提示用户

**app glob 不会误匹配 test apk**：`outputs/apk/debug/*.apk` 不跨目录，test apk 位于 `outputs/apk/androidTest/debug/` 不会被捕获。

### 5.3 JuggGradleCompileOptions 结构

**不新增字段**。`compileCommand` / `outputApkName` 继续存用户原始输入。
在 `JuggCompileHelper.compile` 中根据 `buildTarget` 决定是否将 options 过一次 `AndroidTestCommandDeriver`，派生结果作为本次执行的 options 传给 `LocalGradleCompileClient.login` / `RemoteGradleCompileClient.login`。

### 5.4 test apk 识别契约

`ApkInfo` 新增两个可空字段：

```kotlin
data class ApkInfo(
    val files: List<ApkFileUnit>,
    val applicationId: String,
    val instrumentationTargetPackage: String? = null,
    val instrumentationRunner: String? = null,
)
```

由 `ApkInfoReader` 读 APK manifest 的 `<instrumentation>` 元素（`android:targetPackage` / `android:name`）填充。app apk 保持 null。下游（启动侧）据此精确识别 test apk，无需路径或命名启发。

### 5.5 签名要求

AGP 默认 debug signing config 统一签名，app apk / test apk 签名一致。
自定义 release signing 跑 test 属于边缘场景，阶段 1 不覆盖。

### 5.6 阶段 1 TDD 入口

先在 `main/src/test/` 下落失败测试：

1. `AndroidTestCommandDeriverTest`
   - 基础派生：`:app:assembleDebug` → `:app:assembleDebug :app:assembleDebugAndroidTest`
   - 带 flavor 的 variant 派生：`assembleDevelopmentDebug` → 追加 `assembleDevelopmentDebugAndroidTest`
   - 幂等性：已含 `AndroidTest` 的命令不再追加
   - bundle 类命令的回退规则
   - `outputApkName` 对不同路径模式的派生
2. `ApkInfoReaderInstrumentationTest`
   - fixture：`android_demo_project` 生成的 test apk
   - 验证 `instrumentationTargetPackage` / `instrumentationRunner` 读出正确
   - 验证 app apk 字段保持 null
3. `BaseBuildCmdRecordTest`
   - 新 JSON 格式读写往返
   - 旧单行文本兼容解析为 `buildTarget=APP`
4. `ApkInfoSerializerCompatTest`
   - 旧 JSON（无新字段）反序列化 → 新字段为 null

---

## 6. 阶段待讨论主题

### 6.1 设计节进度

- [x] 节 1：BuildTarget 模型 + 目录策略 + 一致性保障
- [x] 节 2：Gradle 回退与 full compile 命令编排
- [x] 节 3：Test RunConfig 与 gutter icon 的 IDE 集成
- [x] 节 4：部署与启动（两 APK install + am instrument + 结果回收）
- [x] 节 5：阶段 2/3 预留接口说明（BuildTarget 贯穿点 / module 口径 B 的实施细节）
- [x] 节 6：测试策略与验收标准

### 6.2 节 4 预告议题

- 两 APK install 的顺序与事务性（其中一个安装失败如何回滚）
- `am instrument` 命令的组装（`-w -r -e class <cls> -e class#method ... <pkg>/<runner>`）
- 测试结果流式解析（`INSTRUMENTATION_STATUS` / `INSTRUMENTATION_RESULT` 协议）与 IDE Test Runner 窗口集成
- 多设备场景下测试结果合并
- crash / timeout 处理

---

## 7. 设计节 3 定稿：Test RunConfig + gutter icon 的 IDE 集成

### 7.1 总体策略

- 新增独立 `JuggAndroidTestConfigurationType` + 独立 RunConfig 类族（与 `JuggConfigurationType` 并列），通过 plugin.xml 扩展点注册
- **不注册 `RunConfigurationProducer`**：避免与 AS 原生 Android Instrumentation Test 的右键菜单冲突，第一版只通过 gutter icon 作为入口
- 仅注册 `RunLineMarkerContributor`：在 androidTest source set 下的 @Test 方法/类上显示 gutter icon

### 7.2 新增类族

全部新增，不改现有代码：

| 类 | 职责 |
|---|---|
| `JuggAndroidTestRunConfigurationOptions` | 持久化：`testClass` / `testMethod` / `instrumentationRunner` / `extraArgs`（"k=v,k=v" 字符串存） |
| `JuggAndroidTestRunConfiguration` | RunConfigurationBase 实现 |
| `JuggAndroidTestConfigurationType` | ConfigurationTypeBase + 内嵌 Factory |
| `JuggAndroidTestSettingsEditor` | UI 面板 |
| `JuggAndroidTestRunProfileState` | 执行入口；校验 App RunConfig `enableAndroidTest` 与 `BaseBuildCmdRecord.buildTarget` |

### 7.3 gutter icon 识别规则

`JuggAndroidTestLineMarkerContributor`（Java + Kotlin 各注册一次）：

- **硬约束**：PSI 文件必须位于 `**/src/androidTest/**` 目录下；否则不显示 icon（避免打扰单元测试）
- **注解匹配**：精确匹配 FQN `org.junit.Test` / `org.junit.jupiter.api.Test`
- **粒度**：方法级 icon + 类级 icon（类级 = 跑类中所有 @Test 方法）
- **未勾选 enableAndroidTest 场景**：icon 仍然显示（保持入口可见），点击时走 7.5 的通知流程

### 7.4 gutter 点击流程（已勾选场景）

```
1. RunManager.getInstance(project).allSettings 定位 JuggRunConfiguration（App RunConfig）
   - 未找到 → Notification 提示用户先创建 Jugg 运行配置
2. 读 appRunConfig.state.enableAndroidTest 与 BaseBuildCmdRecord.buildTarget
   - enableAndroidTest=true 且 lastBuildTarget=ANDROID_TEST → 直接执行
   - enableAndroidTest=true 且 lastBuildTarget=APP → 静默触发 Gradle 回退 full compile
     （复用 BuildTargetChanged 判据），完成后执行
3. 动态创建 isTemporary=true 的 JuggAndroidTestRunConfiguration，填入 testClass / testMethod
4. ProgramRunnerUtil.executeConfiguration(...) 触发运行
```

### 7.5 gutter 点击流程（未勾选场景）

**只通知，不自动改**（尊重用户的 RunConfig 作为真源）：

- Notification 内容：
  > "运行 Android Instrumentation Test 前，请先在 Jugg Run Configuration 中勾选
  > 'Enable incremental Android Test'，并运行一次 Gradle 回退 full compile。"
- Notification 附带 Action："打开 Run Configuration 编辑器"（导航到 App RunConfig）
- **不自动修改** `JuggRunConfigurationOptions.enableAndroidTest`

### 7.6 临时配置策略

- gutter 创建的 RunConfig 用 `RunManager.setTemporaryConfiguration`
- Run 下拉按 IDEA 惯例显示灰显临时条目，支持 "Save Configuration" 转持久
- 与 IDEA 原生 JUnit 行为一致

### 7.7 yagni 清单（阶段 1 不做）

- 不做 `RunConfigurationProducer`（右键菜单不出现 Run with Jugg）
- 不做 Debug 支持（Test RunConfig 只实现 Run Executor；阶段 3 code swap 后再议）
- 不自动修改 App RunConfig 的持久化字段

### 7.8 改动面汇总（节 3）

| 改动点 | 文件 | 性质 |
|---|---|---|
| `JuggAndroidTestRunConfiguration` 类族 | 新增 `idea/src/ide_entry/java/.../ide/JuggAndroidTestRunConfiguration.kt`（含 5 个类） | 新增 |
| `JuggAndroidTestLineMarkerContributor` | 新增 `idea/src/ide_entry/java/.../ide/JuggAndroidTestLineMarkerContributor.kt` | 新增 |
| `plugin.xml` | `idea/src/ide_entry/resources/META-INF/plugin.xml` | 新增 1 个 `configurationType` + 2 个 `runLineMarkerContributor`（Java/Kotlin） |
| `JuggRunConfigurationOptions` | `idea/src/ide_entry/java/.../ide/JuggRunConfigurationOptions.kt` | **末尾**追加 `enableAndroidTest by property(false)`（依据既有注释"must add to the end"） |
| `JuggRunSettingsComponent` | `idea/src/main/java/.../ide/logic/JuggRunSettingsComponent.kt` | 新增 checkbox UI + getter/setter |
| `JuggRunConfigurationOptionsExt` | `idea/src/main/java/.../ide/logic/JuggRunConfigurationOptionsExt.kt` | 映射新字段到 `JuggGradleCompileOptions`（若需要） |
| `JuggManager.runTask` | `idea/src/main/java/.../JuggManager.kt` | 新增重载，接受 `AndroidTestRunSpec` |

### 7.9 TDD 入口（节 3）

1. `JuggAndroidTestLineMarkerContributorTest`：
   - `src/androidTest/` 下带 `@org.junit.Test` → Info 非空
   - `src/test/` 下同样代码 → Info 为空
   - 未知 `@Test` 注解（非 JUnit）→ Info 为空
2. `JuggAndroidTestRunConfigurationOptionsTest`：字段持久化往返
3. `GutterIconMissingAppRunConfigFlowTest`：未创建 App RunConfig 时 Notification 展示正确
4. `EnableAndroidTestNotCheckedNotificationTest`：未勾选 enableAndroidTest 时点击 gutter → 展示 Notification，**不**修改 RunConfig

---

## 8. 设计节 4 定稿：部署与启动（两 APK install + am instrument + 结果回收）

### 8.1 总体策略

- **复用既有部署链路**：`JuggDeployTask` 已按 `applicationId` groupBy 循环 install，两 APK（app apk + test apk）天然分属两个 applicationId（`<pkg>` 与 `<pkg>.test`），无需改动 `JuggDeployTask.kt:90` 的主循环
- **启动策略分派**：部署完成后按 `BuildTarget.launchStrategy` 决定启动方式
  - `AM_START` → 沿用 `JuggDeployerHelper.kt:155-163` 既有路径
  - `AM_INSTRUMENT` → 新的测试启动路径（本节重点）
- **结果回显最小可行**：阶段 1 只做纯日志流回显到现有 `SimpleProcessHandler` console，**不引入** IntelliJ SM Test Runner 面板，**不引入** ddmlib `RemoteAndroidTestRunner`
  - 理由：当前 `idea/build.gradle` 未声明 `testFramework` plugin 依赖，`main/build.gradle` 无 ddmlib 依赖；引入它们会显著扩大阶段 1 的改动面
  - 阶段 3 若要支持 code swap + Test Runner 面板集成，再按需扩大 build 依赖
- **单设备优先，多设备兜底**：复用 `JuggRunningTask.kt:194-199` 的 `devices.forEachIndexed` 顺序遍历；每台设备打印分隔头 + 独立结果段

### 8.2 两 APK install 的顺序与事务

| 决策项 | 结论 | 理由 |
|---|---|---|
| install 顺序 | app apk → test apk | test apk manifest 的 `android:targetPackage` 指向 app apk，AGP 期望 app 先在位；AS 原生 instrumentation test 任务也是此顺序 |
| 事务性 | **顺序 install + 失败即停**；test apk install 失败时**保留** app apk（不 rollback / 不 uninstall） | 保留 app apk → 下一次普通 Run 仍可直接启动 app；uninstall 会抹掉用户设备上的应用数据，代价过高 |
| 识别 test apk | 依赖 `ApkInfo.instrumentationTargetPackage != null`（节 2 已定义），**不**依赖路径或命名 | 契约明确，避免启发式误判 |
| install 通道 | 完全复用 `JuggDeployTask.perform(AndroidDeployType.INSTALL)` | 零侵入既有 `JuggDeployer.install` / `AsDeployerCompat.getInstaller` 链 |

**order 保障实现点**：在 `JuggDeployTask.run()` 的 `packages: Map<String, List<ApkInfo>> = data.apks.groupBy { it.applicationId }`（`JuggDeployTask.kt:90`）之后，增加一步稳定排序：`instrumentationTargetPackage == null` 的条目（app apk）排前，非 null 的（test apk）排后。排序是纯读 `ApkInfo` 字段，无状态，易测。

**失败处理**：`JuggDeployTask.kt:105-108` 既有 `DeployerException` 捕获已经满足"失败即停"语义（直接返回 `LaunchResult(false, ...)`）。无需新增逻辑。

### 8.3 am instrument 命令组装

#### 8.3.1 组装规则

```
am instrument -w -r [-e <k> <v>]* <test_pkg>/<runner_fqn>
```

| 片段 | 来源 | 说明 |
|---|---|---|
| `-w` | 硬编码 | 阻塞直到测试结束，否则 adb shell 立即返回 |
| `-r` | 硬编码 | raw 模式，输出带 `INSTRUMENTATION_STATUS_CODE` 等机读标签 |
| `-e class <fqn>` / `-e class <fqn>#<method>` | Test RunConfig.testClass / testMethod | 定位到类或方法；空则跑整个包 |
| `-e <k> <v>`（其他） | Test RunConfig.extraArgs（"k=v,k=v"） | 透传用户自定义参数 |
| `<test_pkg>` | test apk 的 `ApkInfo.applicationId` | = app applicationId + ".test"（AGP 默认） |
| `<runner_fqn>` | `ApkInfo.instrumentationRunner`（节 2 已定义） | manifest 读出；缺省使用 `androidx.test.runner.AndroidJUnitRunner` 并记录 warning |

**转义规则**：

- Class FQN 和 method 名受 Java 语法约束，不含空格/引号；组装时直接拼接，**不 shell-quote**
- `extraArgs` 的 value 若含空格：Jugg 检测到后用单引号包裹（`-e k 'v with space'`）；若含单引号：退化为记录 warning 并跳过该 arg，**不**做复杂转义

**幂等性**：同一 Run 触发多次，命令完全一致（除非用户改了 RunConfig）。

#### 8.3.2 新增组件

- **文件**：`main/src/main/java/com/sickworm/intellij/jugg/deploy/instrument/InstrumentCommandBuilder.kt`（新增）
- **职责**：纯函数对象，输入 `AndroidTestRunSpec` + test `ApkInfo`，输出 shell 命令字符串
- **签名**：

```kotlin
object InstrumentCommandBuilder {
    fun build(spec: AndroidTestRunSpec, testApk: ApkInfo): String
}

data class AndroidTestRunSpec(
    val testClass: String?,        // null = 跑整个包
    val testMethod: String?,       // 仅当 testClass 非空时生效
    val extraArgs: List<Pair<String, String>> = emptyList(),
    val runnerOverride: String? = null,  // 覆盖 manifest 读取的 runner，预留给高级场景
)
```

- **调用点**：新增 `AdbCmdHelper.runInstrumentation(...)` 内部使用

### 8.4 am instrument 执行通道

#### 8.4.1 复用 IDeviceAdb

决策：**复用既有 `IDeviceAdb.execAdbShellCmd` 长连接通道**，不引入独立 adb process。

- **文件**：`main/src/main/java/com/sickworm/intellij/jugg/deploy/AdbCmdHelper.kt` 新增方法
- **签名**：

```kotlin
fun runInstrumentation(
    cmd: String,                              // 由 InstrumentCommandBuilder 产出
    lineConsumer: (String) -> Unit,           // 逐行回调（用于解析 & 回显）
    cancelSignal: () -> Boolean,              // 返回 true 时中断
): InstrumentationExitCode
```

- **lineConsumer 契约**：非阻塞；Jugg 侧订阅方在 lineConsumer 内喂给 `InstrumentationOutputParser` 并写入 console
- **cancelSignal 契约**：`SimpleProcessHandler.isCanceled` 直接传入；取消时实现方应向设备发 SIGINT（`am instrument` 能响应）或关闭 shell 通道

#### 8.4.2 IDeviceAdb 是否需要扩展

- **当前能力**：`IDeviceAdb.execAdbShellCmd(cmd): String` 是同步一次性返回全量输出；对于长跑的 `am instrument -w`，这会在测试全部结束后才返回一大段字符串
- **最小改动**：给 `IDeviceAdb` 新增一个 streaming 重载 `execAdbShellCmdStreaming(cmd, lineConsumer, cancelSignal): Int`
  - 实现方（`IdeaDeviceAdb`）底层使用 AS bundled 的 `IDevice.executeShellCommand(cmd, receiver, timeoutSec, TimeUnit)` + 自定义 `IShellOutputReceiver` 实现（AS 运行时 classpath 有此 API，位于 `com.android.ddmlib.IShellOutputReceiver`；Jugg `compileOnly 'com.android.tools.build:gradle'` 并未引入，但 IDE 运行态 classpath 有）
- **风险与规避**：
  - 若 Jugg `main` 模块要编译时引用 `IShellOutputReceiver`，需 `compileOnly` 声明；目前 `IDeviceAdb` 在 `main` 模块，**契约放在 `main`、实现放在 `idea` 层** 可规避 `main` 的编译期 ddmlib 依赖
  - 具体：`IDeviceAdb` 接口新增 streaming 方法（参数仅 `String` + 两个 Kotlin lambda，不涉及 ddmlib 类型）；实现在 `idea/src/main/.../IdeaDeviceAdb.kt` 内部使用 ddmlib 的 `IShellOutputReceiver`

### 8.5 结果解析与回显

#### 8.5.1 解析器组件

- **文件**：`main/src/main/java/com/sickworm/intellij/jugg/deploy/instrument/InstrumentationOutputParser.kt`（新增）
- **职责**：逐行解析 `am instrument -r` 的机读标签，维护状态机，emit 结构化事件
- **协议关键行（AOSP `am` 源码为准）**：

| 行前缀 | 含义 |
|---|---|
| `INSTRUMENTATION_STATUS: class=<fqn>` | 当前测试所属类 |
| `INSTRUMENTATION_STATUS: test=<method>` | 当前测试方法 |
| `INSTRUMENTATION_STATUS: stack=<...>` | 失败堆栈（多行） |
| `INSTRUMENTATION_STATUS_CODE: <n>` | 1=START, 0=OK, -1=ERROR, -2=FAILURE, -3=IGNORED, -4=ASSUMPTION_FAILURE |
| `INSTRUMENTATION_RESULT: stream=<...>` | 最终汇总文本 |
| `INSTRUMENTATION_CODE: <n>` | 整体结束码（1=成功，0/负=失败） |
| `INSTRUMENTATION_ABORTED: <...>` | 被中断（crash / kill） |

- **状态机**：
  1. 空闲 → 读到 `STATUS_CODE: 1` 进入 `InTest(class, test)` 状态
  2. `InTest` 累积 `stack=` 多行 buffer
  3. 读到终止 `STATUS_CODE: 0/-1/-2/-3/-4` 发 `TestEvent(result, stack)`，回到空闲
  4. 读到 `INSTRUMENTATION_CODE:` 或 EOF 发 `SuiteEvent(totalPass/Fail/Ignored)`
  5. 读到 `INSTRUMENTATION_ABORTED` 发 `AbortEvent(reason)`

- **事件数据结构**：

```kotlin
sealed interface InstrumentationEvent {
    data class TestStarted(val className: String, val testName: String) : InstrumentationEvent
    data class TestFinished(val className: String, val testName: String,
                            val result: TestResult, val stack: String?) : InstrumentationEvent
    data class SuiteFinished(val passed: Int, val failed: Int, val ignored: Int) : InstrumentationEvent
    data class Aborted(val reason: String) : InstrumentationEvent

    enum class TestResult { OK, FAILURE, ERROR, IGNORED, ASSUMPTION_FAILURE }
}
```

#### 8.5.2 回显到 Console

- **回显目标**：`SimpleProcessHandler`（IDE run window）
- **输出格式**（纯文本，带 ANSI 色）：

```
[Device: Pixel 7 - emulator-5554] Running tests...
  ▶ com.example.FooTest.testBar ... OK (12ms)
  ✗ com.example.FooTest.testBaz ... FAILURE
      java.lang.AssertionError: expected 1 but was 2
          at com.example.FooTest.testBaz(FooTest.kt:42)
  ⊘ com.example.FooTest.testIgnored ... IGNORED
[Device: Pixel 7 - emulator-5554] Tests summary: 1 passed, 1 failed, 1 ignored
```

- **色标**：OK=绿，FAILURE/ERROR=红，IGNORED/ASSUMPTION_FAILURE=灰；复用 `SimpleProcessHandler` 已有 ANSI 转义支持
- **stack 缩进**：按 4 空格缩进一次，便于区分断言消息

#### 8.5.3 为什么不集成 SM Test Runner（阶段 1）

| 方案 | 代价 | 收益 |
|---|---|---|
| 纯日志流 | 0 | 能立即验证端到端链路，用户能看到结果 |
| 集成 SM Test Runner | `idea/build.gradle plugins` 追加 `testFramework` + 适配 `ServiceMessageBuilder` + 新 ProfileState 接 consoleView | IDE 原生测试树 + rerun failed，对 MVP 不关键 |
| 引入 ddmlib `RemoteAndroidTestRunner` | `main/build.gradle compileOnly ddmlib` + 双 adb 通道协调 | 成熟的解析器，但双通道复杂度高 |

阶段 1 结论：**纯日志流** 作为 MVP；阶段 2/3 再决定是否升级为 SM Test Runner。升级时 `InstrumentationOutputParser` 的事件结构可直接驱动 `ServiceMessageBuilder`，结构复用。

### 8.6 多设备处理

#### 8.6.1 串行 + 分段

沿用 `JuggRunningTask.kt:194-199` 的 `devices.forEachIndexed` 顺序循环，每台设备：

1. 输出 `[Device: <name>] Running tests...` 分隔头
2. 调用 `AdbCmdHelper(device).runInstrumentation(...)`，结果流写入同一 console
3. 输出 `[Device: <name>] Tests summary: ...` per-device 汇总

#### 8.6.2 全局汇总

所有设备跑完后追加一行：

```
All devices: <N> devices, <X> passed, <Y> failed, <Z> ignored. Total time: <T>s.
```

- 任何一台设备任何一个 test 失败，整体 exit code 非零（`SimpleProcessHandler` 标红）
- Run 窗口标题按既有方式显示成功/失败

#### 8.6.3 为什么不并发

- 并发 adb shell 虽然 ddmlib 支持，但 `SimpleProcessHandler` 的 console 是单流，多设备日志交错严重影响可读性
- 串行跑多设备对 MVP 够用；真有并发需求的用户可手动分多 Run 触发

### 8.7 crash / timeout / 取消处理

| 场景 | 信号 | 处理 |
|---|---|---|
| **App 进程 crash** | `InstrumentationOutputParser` 读到 `INSTRUMENTATION_ABORTED` 或 `INSTRUMENTATION_CODE` 为 0 且无终止 test 事件 | 发 `AbortEvent`，console 打印 "Test process crashed: <reason>"，剩余 test 标记为 SKIPPED，整体失败 |
| **单 test 超时** | 不显式处理（由 JUnit `@Test(timeout = ...)` 或 Espresso IdlingResource 自行触发 FAILURE） | 正常走 FAILURE 事件路径 |
| **整个 instrument 长时间无输出** | Jugg 侧不设 hard timeout（避免误杀慢测试） | 依赖用户 Cancel 按钮（`SimpleProcessHandler.isCanceled`）→ `cancelSignal` → adb shell 通道关闭 |
| **用户 Cancel** | `cancelSignal()` 返回 true | `IdeaDeviceAdb.execAdbShellCmdStreaming` 实现端关闭 `IShellOutputReceiver`，am instrument 进程随 shell 通道关闭被 SIGINT |
| **adb 通道断开** | streaming 方法抛异常 | console 打印 "Device disconnected during test run"，剩余设备继续 |

### 8.8 改动面汇总（节 4）

| 改动点 | 文件 | 性质 |
|---|---|---|
| `InstrumentCommandBuilder` | 新增 `main/src/main/java/com/sickworm/intellij/jugg/deploy/instrument/InstrumentCommandBuilder.kt` | 新增（纯函数对象） |
| `AndroidTestRunSpec` | 同上（伴生 data class） | 新增 |
| `InstrumentationOutputParser` + `InstrumentationEvent` | 新增 `main/src/main/java/com/sickworm/intellij/jugg/deploy/instrument/InstrumentationOutputParser.kt` | 新增（状态机 + sealed interface） |
| `InstrumentationConsoleRenderer` | 新增 `main/src/main/java/com/sickworm/intellij/jugg/deploy/instrument/InstrumentationConsoleRenderer.kt` | 新增（事件 → ANSI 文本） |
| `IDeviceAdb` streaming 重载 | `main/src/main/java/com/sickworm/intellij/jugg/deploy/IDeviceAdb.kt`（或所在目录） | 新增方法签名（纯 Kotlin 类型） |
| `IdeaDeviceAdb` streaming 实现 | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/IdeaDeviceAdb.kt` | 实现新接口方法，内部用 ddmlib `IShellOutputReceiver` |
| `AdbCmdHelper.runInstrumentation` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/AdbCmdHelper.kt` | 新增方法 |
| `JuggDeployTask` apk 排序 | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployTask.kt:90` | 在 groupBy 后追加稳定排序（app apk 优先） |
| `JuggDeployerHelper` 启动分派 | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelper.kt:155-163` | install 完成后按 `BuildTarget.launchStrategy` 分派：`AM_START` 走既有；`AM_INSTRUMENT` 走新路径 |
| `JuggAndroidTestRunProfileState`（节 3 已规划） | `idea/src/ide_entry/java/.../ide/JuggAndroidTestRunConfiguration.kt` | 本节补充：注入 `AndroidTestRunSpec`，串联 install + am instrument + 结果回显 |

### 8.9 阶段 1 TDD 入口（节 4）

先在 `main/src/test/` 下落失败测试：

1. **`InstrumentCommandBuilderTest`**
   - 无 testClass → 命令形如 `am instrument -w -r <pkg>/<runner>`
   - 有 testClass 无 method → `-e class <fqn>`
   - 有 testClass + method → `-e class <fqn>#<method>`
   - extraArgs 多对 → `-e k1 v1 -e k2 v2`（顺序稳定）
   - extraArgs value 含空格 → 单引号包裹
   - extraArgs value 含单引号 → 跳过该 arg（检验 logger warning，可用 spy）
   - runnerOverride 非空 → 覆盖 manifest runner
2. **`InstrumentationOutputParserTest`**
   - 单 test OK 序列（START → OK STATUS_CODE=0）→ 发 `TestStarted` + `TestFinished(OK)` + `SuiteFinished(1,0,0)`
   - 单 test FAILURE 序列（带 `stack=`）→ `TestFinished(FAILURE, stack=...)`
   - 多 test 混合（OK + FAILURE + IGNORED）→ Suite 汇总正确
   - `INSTRUMENTATION_ABORTED` → 发 `Aborted` 事件，剩余未完成 test 不发 TestFinished
   - 分块喂入（同一行拆成两次喂）→ 解析器能缓冲到整行（或明确要求 lineConsumer 按行传入，不处理分块；测试验证契约）
3. **`InstrumentationConsoleRendererTest`**
   - 事件序列 → 期望文本行（用 `String.contains` 弱断言，避免过度绑定色码）
   - OK 事件包含 `OK`；FAILURE 事件包含 `FAILURE` 与 stack 行；SUITE 行包含 pass/fail 计数
4. **`ApkInstallOrderTest`**（节 4 新增）
   - 输入：一个 groupBy `{app_pkg: [appApk], test_pkg: [testApk]}` Map
   - 验证：排序后 appApk 的 applicationId 先于 testApk
   - 验证：混入 feature apk（base + feature 同 applicationId）不被错误拆开
5. **`JuggAndroidTestRunProfileStateFlowTest`**（IDE 层，选做；集成测试，可用 mock）
   - mock `JuggManager.runTask` → 模拟 install 成功 + 模拟 am instrument 吐一段固定输出
   - 验证 console 内容包含预期 summary 行
   - 验证 Cancel 信号正确传递到 `runInstrumentation.cancelSignal`

### 8.10 yagni 清单（阶段 1 不做）

- 不引入 IntelliJ SM Test Runner（`testFramework` plugin 依赖暂不加）
- 不引入 ddmlib `RemoteAndroidTestRunner` / `InstrumentationResultParser`（`main/build.gradle` 不改）
- 不支持 test apk install 失败时 rollback app apk（保留现场）
- 不支持多设备并发跑 test（串行）
- 不支持 Debug Executor（只 Run Executor；阶段 3 code swap 后再议）
- 不集成 `.gcno/.gcda` coverage 产物（阶段 3 议题）
- 不做 rerun failed tests（依赖 SM Test Runner 面板，阶段 3 再议）
- 不设硬超时（靠用户 Cancel）

### 8.11 与既有链路的衔接点一览

```
JuggAndroidTestRunProfileState.execute()
  └─ JuggManager.runTask(..., AndroidTestRunSpec)       // 节 3 规划的重载
       ├─ JuggCompileHelper.compile(buildTarget=ANDROID_TEST)   // 节 1/2
       │    └─ 产出 app apk + test apk（ApkInfoReader 已填 instrumentation 字段）
       ├─ JuggDeployerHelper.deploy(...)                          // 既有
       │    └─ JuggDeployTask.run(launchContext)                  // 新增 apk 排序
       │         └─ JuggDeployer.install(...)  × 2 次（app → test）
       └─ TestLauncher.run(devices, spec, testApk)                // 新增入口
            └─ for each device:
                 └─ AdbCmdHelper(device).runInstrumentation(
                        cmd = InstrumentCommandBuilder.build(spec, testApk),
                        lineConsumer = { parser.feed(it); renderer.render(parser.events) → console },
                        cancelSignal = { processHandler.isCanceled }
                    )
```

- `TestLauncher` 是新增的轻量协调器（放在 `idea/src/main/.../deploy/run/TestLauncher.kt`），职责 = 多设备循环 + 聚合 summary；不做协议解析与格式化（委托 parser/renderer）
- `JuggDeployerHelper.kt:155-163` 的启动分派点改为：`if (buildTarget == ANDROID_TEST) TestLauncher.run(...) else 既有 restartApp/startApp`

---

## 9. 设计节 5 定稿：阶段 2/3 预留接口

本节不落地任何代码，只阐明阶段 1 的 spec 在阶段 2/3 的"贯穿点"与"升级路径"，让阶段 1 的抽象不至于为阶段 2/3 埋坑。

### 9.1 阶段目标回顾

| 阶段 | 能力 | 关键动作 |
|---|---|---|
| 阶段 1 | Gradle 回退 full compile 两 APK + am instrument | 本次 spec |
| 阶段 2 | androidTest 源码走 Jugg 增量编译（两 APK install 保留） | ModuleInfo 纳入 androidTest source set；apk 归属按 source set 分流 |
| 阶段 3 | androidTest 类 code swap 热更 | JVMTI redefine 覆盖 test apk 进程；Test RunConfig 支持 Debug Executor |

### 9.2 BuildTarget 贯穿点清单

阶段 2/3 需要复用的阶段 1 抽象：

| 阶段 1 产出 | 阶段 2 使用方式 | 阶段 3 使用方式 |
|---|---|---|
| `BuildTarget.ANDROID_TEST` | `JuggCompiler` 按 target 决定是否纳入 androidTest 源码 | 不变，只是增量路径被走到 |
| `ICompileContext.buildTarget` | 源码扫描与 apk 归属都读此字段 | 部署决策读此字段决定是否走 test 热更路径 |
| `BaseBuildCmdRecord.buildTarget` | 判定 target 切换强制 full compile（与阶段 1 一致） | 不变 |
| `CompileContextManager` 对 `.androidTest` 的条件化过滤 | 阶段 2 合并到 app 的 `ModuleInfo`（口径 B） | 不变 |
| `ApkInfo.instrumentationTargetPackage/Runner` | 阶段 2 的 test apk 元信息直接复用 | 阶段 3 判定 "这个 apk 对应 test 进程" |
| `BuildTarget.launchStrategy` | 不变 | 阶段 3 在 code swap 成功后决定是否还要 am instrument 重新触发 |
| `InstrumentationOutputParser` / `TestLauncher` | 不变 | 阶段 3 在 test 进程存活且 code swap 成功时可复用 parser，但启动方式改为重跑单个 test method |

**结论**：阶段 1 的抽象层级（BuildTarget 枚举 + launchStrategy + ApkInfo 元信息契约）是阶段 2/3 的公共基座，不要在阶段 1 做任何"只对 full compile 生效"的特化。

### 9.3 阶段 2：androidTest 源码纳入 Jugg 增量编译

#### 9.3.1 口径 B（源码归属）细化

当前 `CompileContextManager.kt:346-351` 过滤 `.androidTest` 后缀模块（以代码为准）。阶段 2 落地口径 B：

- **不新建独立 `ModuleInfo`**：`<module>.androidTest` IDE module 不生成独立 `ModuleInfo`
- **合并到 app `ModuleInfo`**：把 `.androidTest` 的 source roots 追加到对应 app `ModuleInfo.sourceDirs`
- **条件化执行**：仅当 `buildTarget == ANDROID_TEST` 时合并；`APP` 场景保持当前过滤行为不变

**ModuleInfo 字段的潜在扩展**（阶段 2 决策时再定稿，不要在阶段 1 写死）：

```kotlin
// 方案 A（推荐）：扩展 sourceDirs 语义，按顺序拼接 main + androidTest
// 不改结构，最小侵入；但丢失 source set 维度，增量编译影响分析时无法区分

// 方案 B：新增字段 androidTestSourceDirs: List<File>（默认空）
// 保留 source set 维度，增量编译按需读取；字段默认值保证向后兼容
```

**阶段 1 约束**：`ModuleInfo` **本次不改**。阶段 2 新增字段需遵循"默认值保持老行为"原则（与节 1 `ICompileContext.buildTarget` 的处理一致）。

#### 9.3.2 Apk 归属（源文件 → ApkInfo）

当前 `CompileEffectAnalyzer`（`main/.../compiler/CompileEffectAnalyzer.kt`）通过 class → source 反查定位受影响源文件；阶段 2 需要在此基础上再加一层 **apk 归属**：

```
ChangedFile
  └─ 所属 module (.androidTest source set ? ) → 目标 apk
      ├─ 改 app 源码 → app apk → 走既有增量路径（无变化）
      └─ 改 androidTest 源码 → test apk → 走新增量路径（阶段 2 新增）
```

归属判据（阶段 2 实施时可选）：

| 判据 | 优缺点 |
|---|---|
| **source root 路径匹配**：`file.path.contains("/src/androidTest/")` | 简单；但不稳健（非标准路径会失效） |
| **module 后缀标记**（推荐）：合并到 `ModuleInfo` 时给 androidTest source root 加 tag | 稳健；但需要 `ModuleInfo.sourceDirs` 升级为 `List<TaggedSourceRoot>` 或新字段 |
| **增量编译产出路径**：按 `build/intermediates/javac/<variant>AndroidTest/` 路径反推 | 依赖 AGP 路径稳定性，与 variant 强绑定 |

**阶段 1 保留**：`ApkInfo.instrumentationTargetPackage != null` 已经是 test apk 的精确标识，源文件的 apk 归属映射在阶段 2 消化，阶段 1 不引入中间态。

#### 9.3.3 增量编译的双 apk 协同

阶段 2 要处理的新场景：

1. **只改 app 源码** → 只增量产出 app apk 的 dex；test apk 不动
2. **只改 androidTest 源码** → 只增量产出 test apk 的 dex；app apk 不动
3. **同时改 app + androidTest** → 两 apk 的 dex 分别增量；**两 apk 的 hot reload/hot fix 分类独立计算**

关键点：`DeployDataGenerator` / `DeployDataDatabase` 当前以 applicationId 为 key（以代码为准；见 `DeployDataDatabaseSqLiteHelper` 多 APK 用例），天然支持多 apk 独立分析。阶段 2 要做的主要是**文件归属**，不是数据结构升级。

#### 9.3.4 阶段 2 新增的 TDD 入口（预告）

- `ModuleInfoAndroidTestMergerTest`（合并 androidTest source roots 正确性）
- `CompileContextManagerTargetAwareFilterTest`（APP target 仍过滤、ANDROID_TEST target 纳入）
- `CompileEffectAnalyzerApkRoutingTest`（改动文件正确路由到 app/test apk）
- `DeployDataGeneratorTwoApkIsolationTest`（两 apk 的 hot reload 分类互不污染）

### 9.4 阶段 3：androidTest 类的 code swap

#### 9.4.1 当前 code swap 能力现状

`JuggDeployer.codeSwap(classFiles, redefiners, data)` 与 `fullSwap(classFiles, data)`（以代码为准，见 `JuggDeployer.kt:101-108`）当前在 `JuggDeployTask.perform` 的 `APPLY_CHANGES` / `APPLY_CHANGES_AND_RESTART_ACTIVITY` 分支触发，**不区分 target 进程**（默认是 app 主进程）。

阶段 3 要求在 test apk 的进程（即 `am instrument` 启动的进程）上做 JVMTI redefine：

| 现状 | 阶段 3 要求 |
|---|---|
| redefine 目标 pid：app 主进程（由 `AsDeployerCompat.makeDebuggerRedefiners` 定位） | redefine 目标 pid：test 进程（`am instrument` 启动的瞬时进程） |
| JVMTI agent 注入时机：`ApplicationLike.attachBaseContext` | test 进程可能更早，agent 注入要提前 |
| `JuggJvmtiAgentManagerHelper.isHasJvmtiCompatIssue` 轮询 `code_cache` flag | test 进程的 `code_cache` 路径可能不一致，需验证 |

#### 9.4.2 阶段 3 的关键未决点（留给阶段 3 brainstorming）

1. **test 进程生命周期极短**：每次跑 test 都是 `am instrument` 触发 + 跑完退出；是否"热更"=重跑 test 方法？
2. **Test RunConfig Debug Executor**：阶段 1/2 不做 Debug；阶段 3 开放 Debug 后需要 attach debugger 到 test 进程
3. **rerun failed tests**：搭配 SM Test Runner 面板升级（节 8.5.3 已预告）

#### 9.4.3 阶段 3 对阶段 1/2 的反向约束

阶段 1/2 需要留下的钩子（**本次 spec 已满足**）：

- `TestLauncher` 的 `runInstrumentation(spec, testApk)` 入参结构允许替换 `spec` → 阶段 3 支持单方法 rerun
- `InstrumentationOutputParser` 的 `TestEvent` 事件粒度足以触发阶段 3 的 Test Runner 面板 rerun 按钮
- `JuggDeployerHelper` 的启动分派点是 target 级的 `if/else`，阶段 3 可在 `AM_INSTRUMENT` 分支内部再分 "首次启动" vs "code swap 后 rerun"

### 9.5 阶段 2/3 不改动清单（阶段 1 承诺）

阶段 1 spec 保证以下抽象在阶段 2/3 **不需要破坏性变更**：

- `BuildTarget` 枚举值（`APP`, `ANDROID_TEST`）
- `BuildTarget.launchStrategy` 语义（`AM_START`, `AM_INSTRUMENT`）
- `ApkInfo.instrumentationTargetPackage/Runner` 字段契约
- `BaseBuildCmdRecord` JSON 格式（向后兼容单行文本）
- `InstrumentCommandBuilder` / `InstrumentationOutputParser` 的事件模型

**允许阶段 2/3 做的扩展**（不破坏阶段 1）：

- `ModuleInfo` 新增 androidTest 相关字段（默认值保持老行为）
- `CompileEffectAnalyzer` 新增 apk routing 子模块
- `JuggDeployer` 新增针对 test 进程的 redefine 路径（既有方法签名不变）
- `TestLauncher` 新增 `rerunTest(method)` 方法（不影响已有入口）

---

## 10. 设计节 6 定稿：测试策略与验收标准

### 10.1 测试金字塔

阶段 1 的测试分层严格遵循 [06_testing.md §1](../ai_knowledge/06_testing.md) 的优先级规则：

```
         ┌──────────────────────────────┐
    顶   │  手测矩阵（§10.4）           │  真机 + 真 AGP + 真 JUnit
         ├──────────────────────────────┤
    中   │  idea 集成测试（§10.3）      │  JuggCompileHelper / JuggDeployTask
         ├──────────────────────────────┤
    底   │  main 单元测试（§10.2）      │  纯函数 / 状态机 / 序列化
         └──────────────────────────────┘
```

**口径**：单元测试数量 >> 集成测试 >> 手测；所有代码改动必须先有失败测试再有实现（TDD 强制前置条件，见项目根 `AGENTS.md`）。

### 10.2 main 模块单元测试（必须先写）

按阶段 1 spec 的新增组件罗列：

| 组件 | 测试文件（全部新增，不复用既有） | 核心用例 |
|---|---|---|
| `AndroidTestCommandDeriver`（节 2） | `AndroidTestCommandDeriverTest.kt` | 基础派生 / flavor 派生 / 幂等性 / bundle 回退 / outputApkName 派生 |
| `BaseBuildCmdRecord` JSON 格式（节 1） | `BaseBuildCmdRecordTest.kt` | 新格式读写往返 / 旧单行兼容解析为 APP |
| `ApkInfoReader` instrumentation 字段（节 2） | 在既有 `apk/ApkInfoReaderTest.kt` 追加（优先复用） | manifest `<instrumentation>` 正确读出 / app apk 保持 null |
| `ApkInfoSerializer` 兼容（节 2） | 在既有 `deploy/ApkInfoSerializerTest.kt` 追加 | 旧 JSON 缺字段反序列化为 null |
| `InstrumentCommandBuilder`（节 4） | `InstrumentCommandBuilderTest.kt` | 无 testClass / 有 class / class#method / extraArgs / 转义边界 / runnerOverride |
| `InstrumentationOutputParser`（节 4） | `InstrumentationOutputParserTest.kt` | OK 序列 / FAILURE 带 stack / ABORTED / 多 test 混合 / IGNORED / ASSUMPTION_FAILURE |
| `InstrumentationConsoleRenderer`（节 4） | `InstrumentationConsoleRendererTest.kt` | 事件 → 文本行包含预期关键字 |
| `JuggDeployTask` apk 排序（节 4） | `ApkInstallOrderTest.kt` | app apk 先于 test apk / 多 feature apk 不错拆 |

**覆盖率门槛**（按组件）：

- 纯函数对象（Deriver / Builder）：行覆盖 100%（分支覆盖 >= 90%）
- 状态机（Parser）：所有终止路径 + ABORTED 旁路必须覆盖
- 序列化（Record / Serializer）：新旧格式双向兼容用例必须存在

### 10.3 idea 模块集成测试（IDE API 依赖）

| 组件 | 测试文件（按 06_testing.md 4.1 先查是否可复用） | 核心用例 |
|---|---|---|
| `JuggCompileHelper.isBuildTargetChanged`（节 1） | 在既有 `JuggCompileHelper` 相关测试文件追加；无则新建 `JuggCompileHelperBuildTargetTest.kt` | target 未变 → 不强制 full；target 变更 → 强制 full |
| `JuggRunConfiguration.enableAndroidTest`（节 3） | 新建 `JuggRunConfigurationOptionsPersistenceTest.kt` | 字段持久化往返；末尾追加保证兼容 |
| `JuggAndroidTestRunConfiguration` 类族（节 3） | 新建 `JuggAndroidTestRunConfigurationOptionsTest.kt` | testClass/testMethod/runner/extraArgs 持久化 |
| `JuggAndroidTestLineMarkerContributor`（节 3） | 新建 `JuggAndroidTestLineMarkerContributorTest.kt` | androidTest 目录下 @Test → Info 非空；test 目录下 → 空；非 JUnit @Test → 空 |
| gutter 未勾选 `enableAndroidTest` 流程（节 3） | 新建 `EnableAndroidTestNotCheckedNotificationTest.kt` | 点击仅弹 Notification，不修改 RunConfig |
| gutter 缺 App RunConfig 流程（节 3） | 新建 `GutterIconMissingAppRunConfigFlowTest.kt` | 引导用户先建 App RunConfig |
| `JuggDeployTask` apk 排序落地（节 4） | 在 idea 层 `JuggDeployTaskTest.kt`（若无则复用 `DeployTargetManagerTest.kt` 模式新建） | install 顺序断言 app → test |
| `IdeaDeviceAdb.execAdbShellCmdStreaming`（节 4） | 新建 `IdeaDeviceAdbStreamingTest.kt` | lineConsumer 按行回调；cancelSignal 及时中断；异常抛出 |
| `JuggAndroidTestRunProfileState` 链路（节 4） | 新建 `JuggAndroidTestRunProfileStateFlowTest.kt` | mock install 成功 + 模拟 am instrument 固定输出 → console 含 summary；Cancel 传递 |

### 10.4 手测矩阵（真机 + 真 AGP）

| 维度 | 取值 | 必测组合数 |
|---|---|---|
| AGP 版本 | 7.2 / 8.x | 2 |
| Gradle variant | `debug` / `developmentDebug`（带 flavor） | 2 |
| 设备 | 真机 1 台 + 模拟器 1 台（API 28+） | 2 |
| 测试规模 | 单方法 / 单类 / 整个包 | 3 |
| 结果场景 | 全 PASS / 混合 / 全 FAIL / IGNORED / 进程 crash / 用户 Cancel | 6 |
| 多设备 | 单设备 / 双设备 | 2 |

**最小必测组合**（阶段 1 发布门槛）：

1. AGP 7.2 + debug + 模拟器 + 单方法 PASS
2. AGP 7.2 + debug + 模拟器 + 单方法 FAIL（含 stack）
3. AGP 7.2 + debug + 模拟器 + 整个包 + 混合结果
4. AGP 8.x + developmentDebug + 真机 + 单类 PASS
5. AGP 7.2 + debug + 模拟器 + 进程 crash
6. AGP 7.2 + debug + 模拟器 + 跑到一半按 Cancel
7. AGP 7.2 + debug + 双设备（模拟器 + 真机）+ 整个包

组合矩阵全量覆盖放入阶段 1 GA 门槛；最小必测为 beta 门槛。

### 10.5 验收标准

#### 10.5.1 功能验收（必须全绿）

| 项 | 通过条件 |
|---|---|
| F1 编译 | 勾选 `enableAndroidTest` 后 Gradle 回退 full compile 同时产出 app apk + test apk |
| F2 模式切换一致性 | 从 APP 模式切换到 ANDROID_TEST 模式（或反之）自动触发 full compile |
| F3 命令派生 | 用户 RunConfig 的 `compileCommand` 不被 Jugg 持久化修改；运行时正确派生 |
| F4 两 APK install | 顺序为 app → test；顺序稳定；失败即停且保留 app apk |
| F5 gutter icon | 仅在 `**/src/androidTest/**` 且 `@org.junit.Test` / `@org.junit.jupiter.api.Test` 下显示 |
| F6 未勾选 enableAndroidTest | 点击 gutter 仅弹 Notification，不隐式修改 RunConfig |
| F7 am instrument 组装 | `-w -r` 常驻；class/method/extraArgs 正确拼接；runner 缺省回退 AndroidJUnitRunner |
| F8 结果解析 | OK/FAILURE/ERROR/IGNORED/ASSUMPTION_FAILURE 均能正确分派 |
| F9 结果回显 | console 有 per-test 行 + per-device summary + global summary |
| F10 Cancel | Cancel 按钮能在 3s 内中断 am instrument 并释放 adb 通道 |
| F11 Crash | `INSTRUMENTATION_ABORTED` 被识别并输出友好提示 |
| F12 多设备 | 串行遍历；per-device 分隔头清晰；任一设备失败整体 exit code 非零 |

#### 10.5.2 性能验收

- `InstrumentCommandBuilder.build`：单次耗时 < 1ms（纯字符串拼接）
- `InstrumentationOutputParser.feed`：单行耗时 < 0.1ms；全量 1000 行解析 < 100ms
- am instrument 吞吐：测试结果回显延迟（从设备 stdout 到 console）< 500ms
- 首次两 APK install：相比 AS 原生 Instrumentation Test 开销 < 1.2 倍（主要是 Jugg 的 `DeployerException` 路径开销）

#### 10.5.3 兼容性验收

- **向后兼容**：
  - 既有 `JuggRunConfiguration`（未勾选 `enableAndroidTest`）行为完全不变
  - 既有 `base_build_cmd.txt` 单行文本格式仍可被解析为 `buildTarget=APP`
  - 既有 `ApkInfo` JSON（缺 instrumentation 字段）反序列化正常（字段为 null）
- **AGP 兼容**：AGP 7.2 / 8.x 两套签名/manifest 读取路径均通过 F1/F2/F4
- **Kotlin/Java 混合**：gutter icon 在 Kotlin / Java 测试类下都能显示（Java + Kotlin PSI 各注册一次）

#### 10.5.4 可观测性验收

- console 输出包含：每台设备分隔头、per-test 行（OK/FAIL/IGNORED）、stack（FAIL 时）、per-device summary、global summary
- Jugg logger 记录：命令派生原文、am instrument 完整命令、解析器状态转移（DEBUG 级）
- 失败场景（crash / cancel / 无 device）在 logger 里可通过关键字快速定位

### 10.6 回归基线

阶段 1 落地后，以下既有行为必须保持不回归：

- 普通 Run（`enableAndroidTest=false`）：编译/部署/启动链路无任何变化
- 既有 `JuggDeployTask` 多 APK install（多 applicationId）：顺序与行为不变（排序在当前场景是 no-op）
- 既有 `JuggDeployerHelper.deploy` 的 `restartApp/startApp` 分派：在 `AM_START` 分支保持不变
- 既有 `SimpleProcessHandler` 的 ANSI 渲染与 Cancel：兼容新的测试结果输出格式

回归手段：`./gradlew :main:test :idea:test` 全绿 + 手测 "普通 Run" 至少 1 次。

### 10.7 发布门槛

| 门槛 | 条件 |
|---|---|
| **beta（内部试用）** | §10.2/§10.3 全部单测绿 + §10.4 最小必测 7 组全过 + §10.5.1 F1-F9 通过 |
| **GA（对外发布）** | 上述 + §10.4 全量矩阵覆盖 + §10.5 四类验收全绿 + §10.6 回归基线无破坏 |

---

## 11. 参考实现入口

- 编译总控：`main/.../compiler/JuggCompiler.kt`、`idea/.../compiler/JuggCompileHelper.kt`
- 项目模型：`main/.../project/data/JuggProjectInfo.kt`、`main/.../gradle/script/GradleProjectInfoReader.kt`
- 多 APK 部署：`idea/.../deploy/run/JuggDeployTask.kt:90`（已具 groupBy applicationId 多包 install 能力）
- 路径管理：`main/.../project/JuggPathManager.kt`
- Gradle 命令持久化：`main/.../gradle/compile/BaseBuildCommandHelper.kt`
- Gradle 本地编译：`main/.../gradle/compile/LocalGradleCompileClient.kt`
- Gradle 远端编译：`main/.../gradle/compile/RemoteGradleCompileClient.kt`
- APK 查找命令：`main/.../gradle/compile/SshCommand.kt:178`（`FindOutputCommand`）
- APK manifest 读取：`main/.../apk/ApkInfo.kt`、`main/.../apk/ApkInfoReader.kt`
- 既有 androidTest 过滤点：`idea/.../project/CompileContextManager.kt:168`（条件化改造点）

---

## 12. 变更历史

- 2026-04-20：初版，落地设计节 1 定稿内容。
- 2026-04-20：补充设计节 2 定稿内容（Gradle 回退与命令派生）。
- 2026-04-20：补充设计节 3 定稿内容（Test RunConfig + gutter icon IDE 集成）。
- 2026-04-20：补充设计节 4 定稿内容（两 APK install + am instrument + 日志流回显 + 多设备串行）。
- 2026-04-20：补充设计节 5 定稿内容（阶段 2/3 预留接口：BuildTarget 贯穿点 / 口径 B 细化 / code swap 约束）。
- 2026-04-20：补充设计节 6 定稿内容（测试策略金字塔 / 手测矩阵 / 验收标准 / 回归基线 / 发布门槛）。
