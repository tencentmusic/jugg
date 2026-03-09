# MCP 转向 Android UI 测试战略方案

> 日期：2026-03-09
> 状态：方案讨论

## 背景

Jugg 当前通过 MCP（Model Context Protocol）为 AI Agent 提供两大能力：
1. **操控层**：编译部署（`compile_and_deploy`）、应用重启（`restart_app`）、点击/滑动（`tap`/`swipe`）、截图（`screenshot`）、布局树（`layout_dump`）等
2. **UI 验证层**：`layout_verify` 提供 property/spacing/alignment/overlap/containment/order 6 种校验类型

设计初衷是让 AI Agent 在完成 UI 开发后，通过 MCP 调用进行界面校验，形成"开发 → 编译 → 验证"闭环。

## 核心问题

### MCP UI 验证方案的瓶颈

1. **复杂度爆炸**：6 种验证类型各有语义陷阱（`alignment.direction` 40-60% 错误率、`overlap` PASS 反直觉、`containment` target 方向混淆），需要三层防御（schema description + result message + skill doc）
2. **能力边界硬伤**：真实场景分析显示 28% 的验证需求（custom attributes、drawable internals、tint/colorFilter）**不可能通过 MCP 覆盖**，72% 也仅是 YES+PARTIAL
3. **维护成本膨胀**：每增加一个属性，需同步修改 `LayoutVerifyMcpToolAction.kt`、`LayoutVerifier.java`、MCP schema、Skill doc、test cases——5 处联动
4. **Agent 调用链过长**：一次 UI 验收需要 5~10 次 MCP 串行调用，每次都有网络延迟 + App 在线等待 + schema 解析开销
5. **私有协议负担**：Agent 需要学习 Jugg 私有的 MCP 协议语义，而非利用 AI 天然熟悉的标准 Android API

### 关键洞察

> Android UI 测试（Espresso/Compose Test）是比 MCP 更好的 UI 验证入口。
> 过去因为人工维护成本高无法普及，但 AI coding 时代 AI 写 UI 测试代码几乎零成本。

## MCP 操控层 vs Android UI 测试对比

| 维度 | MCP 操控层 | Android UI 测试 |
|------|-----------|----------------|
| 执行效率 | 每个 check 一次 HTTP + App IPC | 进程内执行，毫秒级 |
| 属性覆盖 | 受限于 dump schema + live query | 可访问 View **任意**属性和方法 |
| 可维护性 | Agent 每次需理解 MCP 私有协议 | 标准 Android API，AI 天然熟悉 |
| 可调试性 | 失败信息经 JSON-RPC 序列化，信息损失 | IDE 直接查看堆栈和断言详情 |
| 生态复用 | 仅 Jugg 用户可用 | 项目级资产，CI/CD 可复用 |
| AI 生成难度 | 需学习 Jugg 私有协议 | Espresso/Compose Test 是公开知识，AI 训练数据丰富 |
| 交互操控 | tap/swipe/navigate | onView().perform(click()/swipeUp())、ActivityScenario |
| 截图 | screenshot MCP | Screenshot.capture()（androidx.test） |
| 等待机制 | 100ms 轮询 + 10s 超时 | IdlingResource 自动等待 |
| 测试隔离 | 共享 App 状态，易污染 | 每个 test 独立 Activity launch |

**结论：操控层对 AI coding 工作流的价值很低。** AI 写一个 `@Test` 方法的成本跟调一次 MCP 差不多，但获得的能力是 MCP 的超集。

## 编译速度问题——核心挑战

### 问题

标准 `./gradlew connectedAndroidTest` 需要编译两个 APK：

```
./gradlew connectedAndroidTest
  ├── :app:assembleDebug              ← 主 APK
  ├── :app:assembleDebugAndroidTest   ← 测试 APK
  └── :app:connectedDebugAndroidTest  ← 安装 + 运行测试
```

即使只改一个测试文件，Gradle 增量编译测试 APK 也需要 **15~30 秒**（configuration phase + up-to-date 检查的固定开销）。对比 Jugg 增量编译的 **1~3 秒**，差距 5~10 倍。

AI coding 典型循环：`修改 UI 代码 → 修改/生成测试代码 → 编译 → 部署 → 运行测试 → 看结果 → 再改`

如果测试 APK 走 Gradle，Jugg 在主 APK 上省的时间会被测试 APK 编译吃掉，核心竞争力无法发挥。

### 技术路径分析

| 路径 | 方案 | 编译耗时 | 工程量 | AI 友好度 | 推荐度 |
|------|------|---------|--------|----------|--------|
| A | Jugg 增量编译测试 APK | 1~3s + 1~3s | **大**（多 APK 支持） | 高 | ⭐⭐ |
| B | 测试代码注入主 APK | 1~3s | **中**（需 test runner） | 高 | ⭐⭐⭐ |
| **C** | **Hybrid（B + 标准 API）** | **1~3s** | **中** | **最高** | **⭐⭐⭐⭐** |

### 推荐方案：路径 C——Hybrid 模式

```
1. Agent 写测试代码（标准 Espresso/AndroidX Test 风格）
2. Jugg 把测试代码作为普通源码增量编译进主 APK（1~3s）
3. Jugg 部署（code swap）
4. 通过 adb 或轻量触发器在 App 内执行测试
5. Test runner 在 App 进程内执行，输出结果回传 Agent
```

核心优势：
- **编译速度**：测试代码跟业务代码一起走 Jugg 增量编译，1~3s
- **能力完整**：进程内运行，能访问 View 的一切属性（解决 Cap-16/17/22 等不可覆盖场景）
- **AI 友好**：Agent 写标准 Android 测试 API，不需要学私有协议
- **复用 Jugg 核心能力**：增量编译 + code swap

代价：需要在 App 内嵌一个轻量 test runner，以及处理测试依赖（Espresso 等）打进主 APK 的问题。

### 可行性依据

当前 `LayoutVerifier.java` 已经证明了"App 内运行验证代码"的技术可行性——它就是运行在 App 进程内的验证代码。Hybrid 方案本质上是将其从私有协议升级为标准测试 API。

## 战略调整建议

### 第一步：冻结 MCP UI 验证能力

- 停止扩展 `layout_verify` 的 assertion 能力
- `optimization_plan.md` 中只做 bug 修复（如 alpha bug fix），不再投入新特性（如 backgroundColor 支持）
- 已有的操控层 MCP（`compile_and_deploy`、`tap`、`screenshot` 等）冻结新需求

### 第二步：设计 Hybrid 测试运行方案

- 设计轻量 test runner（基于 JUnit4/JUnit5 runner 或自定义）
- 明确测试依赖（Espresso/AndroidX Test）的集成方式
- 验证 Jugg code swap 对测试代码的兼容性

### 第三步：Skill 引导 Agent 写 UI 测试

- 扩展 `jugg-android-dev-loop` skill，增加"生成 UI 测试 → Jugg 编译 → 运行测试 → 看结果"步骤
- 提供测试代码模板和最佳实践

### 第四步：逐步废弃 MCP UI 验证

- 在 Hybrid 方案验证可行后，标记 `layout_verify` 为 deprecated
- 迁移文档和 skill 指引

## Jugg 定位转变

```
之前：提供 UI 验证 MCP 工具（与 Espresso 竞争，必输）
之后：提供最快的 Android 测试编译运行循环（让 Espresso 跑得更快，独家能力）
```

**Jugg 的核心价值不是重造 Android 测试框架，而是让标准 Android 测试框架以 1~3 秒的速度运行。**

## 风险评估

| 风险 | 严重度 | 缓解措施 |
|------|--------|---------|
| Hybrid test runner 工程量超预期 | 中 | 先做 POC 验证，从最简场景（单个 View 属性断言）开始 |
| 测试依赖（Espresso）打进主 APK 有副作用 | 中 | 使用 debugImplementation 隔离，或条件编译 |
| AI 生成的测试代码编译失败 | 低 | Jugg 编译 → 报错 → Agent 修复循环，已有闭环 |
| 已有 MCP/Skill 文档的沉没成本 | 低 | MCP 操控层文档保留供参考，仅冻结 verify 相关投入 |
| 用户已依赖 layout_verify | 极低 | 功能仍在评估阶段，尚无外部用户依赖 |

---

## Hybrid 模式详细技术方案（修订版 v2）

> v2 修订说明：基于以下原则重新设计
> 1. **Jugg 是 IDE 插件**，测试代码在用户工程中，不在 Jugg 工程中
> 2. **零侵入**：不修改用户 build.gradle、不新增源码目录、不要求用户安装额外依赖
> 3. **复用 androidTest**：测试代码直接放在用户工程已有的 `src/androidTest/java/` 目录
> 4. **直接复用用户已有的 test 依赖**：扩展 Jugg 依赖读取能力即可

### 一、Jugg 编译管线改造——支持 androidTest 源码与依赖

#### 1.1 现状分析

Jugg 当前在两个层面过滤掉了 test 相关内容：

**Gradle 端（`GradleProjectInfoReader`）**：
- `filterConfigs()` 函数过滤掉 configuration name 前缀以 `Test` 结尾的配置
- 即 `androidTestCompileClasspath`、`debugAndroidTestCompileClasspath` 等被排除
- 因此 `androidTestImplementation` 声明的库（Espresso、AndroidX Test）不在 `libraryDependencies` 中

**IDE 端（`CompileContextManager`）**：
- `doGetAllModulesByModuleManager()` 过滤掉模块名以 `.androidTest`、`.test`、`.unitTest` 结尾的模块
- 即 androidTest 模块的源码目录不被 Jugg 感知

**结论**：要让 Jugg 增量编译 androidTest 代码，需要解除这两层过滤。

#### 1.2 改造方案

##### 改造 A：`GradleProjectInfoReader` 扩展读取 androidTest 依赖

```
目标：让 Jugg 能读取到用户工程中 androidTestImplementation 声明的依赖 jar/aar

改动点：
1. GradleProjectInfoReader.getModuleInfo() 中新增：
   val androidTestDependFilterName = "${moduleInfo.buildVariant}AndroidTestCompileClasspath"
   val androidTestDependencies = getDependenciesByConfig(
       project, androidTestDependFilterName, isAndroidDepend = true
   )

2. ModuleInfo 新增字段：
   val androidTestLibraryDependencies: List<LibraryDependency>

3. filterConfigs() 保持不变，新增一个独立的 getDependenciesByConfig 调用，
   使用完整的 androidTest classpath configuration name

影响范围：GradleProjectInfoReader、ModuleInfo、JuggProjectInfoSerialize
```

##### 改造 B：`CompileContextManager` 支持 androidTest 模块

```
目标：让 Jugg 能感知 androidTest 源码目录（src/androidTest/java/）

选项 B1（最小改动）：
  不取消 .androidTest 模块的过滤，而是在检测到编译文件路径包含
  "androidTest" 时，将其依赖解析为 androidTest classpath。

  即：Jugg 的编译入口是具体文件，如果 Agent 写的测试文件在
  src/androidTest/java/ 下，Jugg 按文件路径判断这是 androidTest 文件，
  自动使用 androidTestLibraryDependencies 作为编译 classpath。

选项 B2（更完整）：
  取消 .androidTest 模块的过滤，让它作为一个独立模块参与 Jugg 编译。
  需要处理 androidTest 模块对 main 模块的隐式依赖关系。
  
推荐选项 B1：最小改动，且 Jugg 已有按文件路径判断模块的能力。
```

##### 改造 C：`BaseCompileContext.getModuleDependencies()` 扩展

```
目标：编译 androidTest 文件时，classpath 包含 main + androidTest 双层依赖

逻辑：
  if (compileFile.isAndroidTest) {  // 通过路径判断
      classpath = mainModuleDependencies + androidTestLibraryDependencies
  } else {
      classpath = mainModuleDependencies  // 现有逻辑不变
  }

androidTest 文件天然依赖 main 源码的类，所以 classpath 必须是叠加关系。
```

#### 1.3 改造后的编译流程

```
Agent 写测试代码 → src/androidTest/java/com/example/LoginScreenTest.kt
                    ↓
Jugg 检测到文件变更（路径包含 /androidTest/）
                    ↓
Jugg 编译此文件，classpath = main 依赖 + androidTest 依赖
  （androidTest 依赖来自用户 build.gradle 的 androidTestImplementation，
   由 GradleProjectInfoReader 在 Gradle sync 时读取并缓存）
                    ↓
编译产物（.dex）通过 code swap 部署到 App 进程
                    ↓
MCP run_ui_test → App 内 TestRunner 反射执行测试方法
```

**零侵入**：用户不需要修改任何文件。测试依赖（Espresso 等）本就在 build.gradle 的 `androidTestImplementation` 中声明。Jugg 只是多读了一份依赖列表。

### 二、测试依赖注入——核心问题与解法

#### 2.1 问题：androidTest 依赖不在主 APK 中

用户通过 `androidTestImplementation` 声明的 Espresso 等库，**只存在于测试 APK（`*-androidTest.apk`）中，不在主 APK 中**。

Jugg code swap 部署的 dex 运行在**主 APK 进程**中。所以测试代码虽然能编译通过（Jugg 编译时能读到 androidTest classpath），但运行时会 ClassNotFoundException（Espresso 类不在主 APK 的 ClassLoader 中）。

#### 2.2 解法：Jugg 运行时注入测试依赖 jar

Jugg 已有 **DexPatchLoader** 机制——可以在运行时通过 ClassLoader 的 dexElements 合并，注入额外的 dex 文件。同样的机制可以用来注入测试依赖 jar。

```
流程：
1. Jugg 在 Gradle sync 时读取 androidTestImplementation 依赖的 jar/aar 文件路径
   （这些文件已在用户本地 Gradle cache 中，如 ~/.gradle/caches/...）
2. 首次运行 UI 测试时，Jugg 将这些 jar/aar 中的 classes.jar 用 D8 编译为 dex
3. 通过 adb push 推送到设备，存放在 App 的 code_cache 目录
4. 通过 ViewHierarchyServer 的 DexPatchLoader（或扩展的 TestDepsLoader）
   在运行时注入到 App ClassLoader 的 dexElements 中
5. 之后 TestRunner 反射执行测试代码时，Espresso 类就能被正常加载

缓存策略：
- 依赖 jar 的 crc32 不变则不重新推送（复用 LibraryDependency.crc32 字段）
- 只在依赖版本变化（Gradle sync 更新后）才重新 D8 编译 + push
```

**优势**：
- 完全零侵入——不改 build.gradle，不改依赖声明方式
- 复用用户已有的 `androidTestImplementation` 依赖，版本完全一致
- 复用 Jugg 已有的 DexPatchLoader 运行时注入机制
- 依赖 jar 文件已在本地 Gradle cache 中，不需要额外下载

**副作用**：
- 测试依赖被注入到主 APK 进程中（仅在 Jugg 测试运行期间）
- 可能的类冲突：如果 androidTest 依赖的某些传递依赖与主 APK 已有类版本不同

#### 2.3 替代方案对比

| 方案 | 是否需改 build.gradle | 运行时可用 | 版本一致性 | 复杂度 |
|------|:---:|:---:|:---:|:---:|
| **A. 运行时注入 androidTest jar（推荐）** | 否 | 是 | 完全一致 | 中 |
| B. 改 androidTestImpl 为 debugImpl | 是（破坏零侵入） | 是 | 一致 | 低 |
| C. customClasspath 只编译时注入 | 否 | 否（运行时 ClassNotFound） | — | 低 |
| D. Jugg 内嵌固定版本 Espresso jar | 否 | 是 | 可能不一致 | 低 |

### 三、测试框架选型——直接复用用户已有依赖

#### 3.1 核心原则

**不引入任何 Jugg 自研测试断言库**。原因：
- 用户工程已有 Espresso/Compose Test 依赖（`androidTestImplementation`）
- Agent（AI）天然熟悉 Espresso/Compose Test API，无需额外学习
- 自研断言库是另一种"私有协议"，违背 Hybrid 方案初衷

#### 3.2 Agent 写的测试代码示例

Agent 直接使用标准 Android 测试 API，放在用户工程的 `src/androidTest/java/` 目录：

```kotlin
// src/androidTest/java/com/example/app/LoginScreenTest.kt
// Agent 写的测试代码，使用标准 Espresso API

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import org.junit.Test

class LoginScreenTest {
    @Test
    fun verifyLoginButton() {
        onView(withText("Login"))
            .check(matches(isDisplayed()))
            .check(matches(isClickable()))
    }
    
    @Test
    fun verifyEmailFieldHint() {
        onView(withId(R.id.email_input))
            .check(matches(withHint("Enter your email")))
    }
}
```

```kotlin
// Compose 项目的测试
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertIsDisplayed

class ComposeLoginScreenTest {
    @get:Rule
    val composeTestRule = createComposeRule()
    
    @Test
    fun verifyTitle() {
        composeTestRule.onNodeWithText("Welcome")
            .assertIsDisplayed()
    }
}
```

**零私有 API，零额外学习成本。**

#### 3.3 未配置 androidTest 依赖的工程怎么办？

部分用户工程可能从未写过 UI 测试，没有 `androidTestImplementation` 声明。

```
检测逻辑：
  Jugg 在首次触发 run_ui_test 时检查 androidTestLibraryDependencies 是否为空。
  
如果为空：
  方案 1（推荐）：Agent 通过写文件工具自动在 build.gradle 中添加依赖
    - Agent 已有写文件能力，这是 Agent 行为而非 Jugg 行为
    - 添加后触发 Gradle sync → Jugg 自动读取到新依赖
    - 这不违反 Jugg 零侵入原则（修改的是 Agent，不是 Jugg）
  
  方案 2：Jugg run_ui_test 返回错误信息，提示缺少测试依赖
    - 由 Agent 或用户决定如何处理
```

### 四、跨版本兼容性——核心风险分析

#### 4.1 版本差异全景

| 依赖 | 关键 API 变化 | 影响 |
|------|-------------|------|
| **Espresso** 3.1→3.6 | API 基本稳定，新增 `ViewAssertion` 少量方法 | 低风险 |
| **AndroidX Test Runner** 1.1→1.6 | `ActivityScenario` 1.2+ 才引入；`InstrumentationRegistry` API 稳定 | 中风险 |
| **Compose UI Test** 1.0→1.7 | API 频繁变化（`SemanticsNodeInteraction` 方法签名变化） | 高风险 |
| **JUnit 4** 4.12→4.13.2 | 基本无 API 变化 | 无风险 |
| **Kotlin** 1.5→2.0 | 影响编译不影响运行时 API | 编译侧风险（见 4.2） |

#### 4.2 Jugg 编译侧的兼容风险

Jugg 增量编译使用的是 IDE 内置的 Kotlin/Java 编译器。编译 androidTest 文件时：

```
风险点：
1. Kotlin 版本差异：用户工程 Kotlin 1.7 + Espresso 3.5 的 API 签名 vs
   Jugg 编译器使用的 Kotlin 版本是否匹配？
   
   分析：Jugg 使用用户工程配置的 Kotlin 编译器版本（从 Gradle sync 读取），
   所以 Kotlin 版本与用户工程一致，无额外风险。

2. 依赖传递冲突：androidTest classpath 中某个库的传递依赖版本与
   main classpath 中的版本不一致。
   
   分析：这是用户工程本身的问题（Gradle 会在 build 时报错），
   不是 Jugg 引入的新问题。Jugg 只是忠实读取 resolved classpath。

3. D8 编译兼容性：将 androidTest jar 用 D8 编译为 dex 时的 minSdk 兼容性。
   
   分析：使用与用户工程一致的 minSdkVersion 配置即可。
```

**结论**：Jugg 编译侧的跨版本兼容风险极低，因为 Jugg 完全复用用户工程已有的编译器版本和依赖版本。

#### 4.3 运行时注入的兼容风险

```
风险 1：类版本冲突（高关注）
  场景：androidTest 传递依赖的 guava 版本与主 APK 中的 guava 版本不同。
  DexPatchLoader 的 dexElements 合并是"先到先得"——主 APK 的类已加载，
  测试依赖注入的同名类不会覆盖（被忽略）。
  
  影响：如果测试代码依赖的某个类方法在主 APK 版本中不存在 → NoSuchMethodError
  
  缓解：
  - Espresso 的核心依赖链（hamcrest、junit）版本变化极小
  - 运行时真正新增的类（Espresso 自身的 Matcher、ViewAction 等）在主 APK 中不存在，
    注入后可被正常加载
  - 只有"同名不同版本"的类才有风险，这在 Espresso 生态中概率很低

风险 2：Instrumentation 对象缺失（高关注）
  场景：Espresso 的 onView() 内部依赖 InstrumentationRegistry.getInstrumentation()
  在非 instrument 启动的 App 进程中，这个对象为 null。
  
  影响：直接调用 Espresso API 会 NPE
  
  解法（分阶段）：
  Phase 1：不使用 Espresso 高级 API，只用基础的 View 查找 + 属性断言
    - 通过 ViewHierarchyServer 已有的 View 遍历能力获取 View 引用
    - 断言使用 JUnit Assert 或 hamcrest Matcher（这些不依赖 Instrumentation）
  
  Phase 2：初始化一个 mock/fake Instrumentation 对象
    - 通过反射设置 InstrumentationRegistry 的 instance
    - 或通过 JVMTI Agent 在 App 启动时注入真实 Instrumentation
  
  Phase 3：走标准 `adb shell am instrument` 路径
    - 需要用户工程 AndroidManifest 有 <instrumentation> 声明
    - 大多数 Android 项目的 debug 变体已有此声明

风险 3：ComposeTestRule 需要 Activity 生命周期控制（中关注）
  场景：createComposeRule() 需要 ActivityScenario，后者依赖 Instrumentation。
  
  影响：Compose 项目的测试在 Phase 1 无法直接使用 ComposeTestRule
  
  缓解：Phase 1 对 Compose 项目可退化为属性级断言（通过 Semantics 树查询）
```

#### 4.4 兼容性风险总结

| 风险 | 概率 | 严重度 | Phase 1 影响 | 缓解手段 |
|------|------|--------|-------------|---------|
| 类版本冲突 | 低 | 中 | 可忽略 | dexElements 排序 + 日志监控 |
| Instrumentation 缺失 | 确定 | 高 | Phase 1 避开相关 API | Phase 2 注入 mock Instrumentation |
| ComposeTestRule 不可用 | 确定 | 中 | Phase 1 退化为属性断言 | Phase 3 走 am instrument |
| D8 编译失败 | 极低 | 高 | — | 使用用户工程同版本 D8 |

### 五、App 内 Test Runner 设计

#### 5.1 改造点：`ViewHierarchyServer` 新增 `run_test` action

```java
// ViewHierarchyServer.java 新增 handler
case "run_test":
    String testClass = params.getString("testClass");
    String testMethod = params.optString("testMethod", null); // null = run all
    JSONObject result = TestRunner.run(testClass, testMethod);
    return result; // { "status": "PASS|FAIL", "results": [...] }
```

#### 5.2 TestRunner 实现（Phase 1）

```java
// 新增 TestRunner.java in jvmti_agent
public class TestRunner {
    public static JSONObject run(String className, String methodName) {
        Class<?> testClass = Class.forName(className);
        Object instance = testClass.newInstance();
        
        List<Method> methods;
        if (methodName != null) {
            methods = List.of(testClass.getMethod(methodName));
        } else {
            methods = findAnnotatedMethods(testClass, Test.class);
        }
        
        JSONArray results = new JSONArray();
        for (Method method : methods) {
            try {
                // Run @Before methods
                runAnnotatedMethods(instance, Before.class);
                // Run on main thread (UI assertions need main thread)
                runOnMainThread(() -> method.invoke(instance));
                // Run @After methods
                runAnnotatedMethods(instance, After.class);
                results.put(new JSONObject()
                    .put("method", method.getName())
                    .put("status", "PASS"));
            } catch (InvocationTargetException e) {
                results.put(new JSONObject()
                    .put("method", method.getName())
                    .put("status", "FAIL")
                    .put("message", e.getCause().getMessage())
                    .put("stacktrace", getStackTrace(e.getCause())));
            }
        }
        return new JSONObject()
            .put("status", results.allPass() ? "PASS" : "FAIL")
            .put("results", results);
    }
}
```

Phase 1 的 TestRunner 是纯反射的 JUnit4 轻量执行器，不依赖 Instrumentation。

#### 5.3 MCP `run_ui_test` 工具

```
新增 MCP 工具：run_ui_test
参数：
  - testClass: String (必选) - 完整类名
  - testMethod: String (可选) - 方法名，不传则运行全部 @Test 方法

返回：
  {
    "status": "PASS" | "FAIL" | "ERROR",
    "results": [
      { "method": "verifyLoginButton", "status": "PASS" },
      { "method": "verifySpacing", "status": "FAIL", 
        "message": "Expected 8dp but was 12dp", 
        "stacktrace": "..." }
    ],
    "duration_ms": 150
  }

错误场景：
  - ClassNotFoundException → 提示需要先 compile_and_deploy
  - 测试依赖未注入 → 提示缺少 androidTest 依赖
```

### 六、Jugg 侧改造清单（修订版）

| 改造项 | 影响模块 | 工程量 | 优先级 | 说明 |
|-------|---------|--------|--------|------|
| **GradleProjectInfoReader 读取 androidTest 依赖** | `gradle/script/` | 小 | P0 | 新增 androidTestCompileClasspath 读取 |
| **ModuleInfo 扩展 androidTestLibraryDependencies** | `project/data/` | 小 | P0 | 数据结构扩展 |
| **BaseCompileContext 支持 androidTest classpath** | `project/` | 中 | P0 | 编译文件路径判断 + classpath 合并 |
| **TestRunner（jvmti_agent 内）** | `jvmti_agent/` | 中 | P0 | 轻量 JUnit4 反射执行器 |
| **运行时 androidTest jar 注入** | `deploy/` + `jvmti_agent/` | 中 | P0 | D8 编译 jar → push → dexElements 注入 |
| **MCP `run_ui_test` 工具** | `mcp/actions/` | 小 | P0 | — |
| **ViewHierarchyServer 扩展** | `jvmti_agent/viewhierarchy/` | 小 | P0 | 新增 run_test action |
| Instrumentation mock 注入（Phase 2） | `jvmti_agent/` | 中 | P1 | 解锁完整 Espresso 能力 |
| Skill 文档更新 | `jugg-android-dev-loop` | 小 | P1 | — |

### 七、端到端流程示例（修订版）

```
AI Agent 开发循环（Hybrid 模式）：

前提：用户工程 build.gradle 已有 androidTestImplementation 'espresso-core:3.5.1' 等声明
     （大多数 Android 项目创建时就有）

首次运行准备（一次性，~10s）：
  a. Jugg Gradle sync 时读取 androidTestLibraryDependencies
  b. 首次 run_ui_test 触发时，Jugg 将 androidTest jar 用 D8 编译为 dex
  c. adb push dex 到设备 → DexPatchLoader 注入到 App ClassLoader

日常循环：
  1. Agent 修改 UI 代码（如 LoginActivity.kt）
  2. Agent 生成测试代码 → 写入 src/androidTest/java/（标准 Espresso 代码）
  3. Agent 调用 compile_and_deploy
     └── Jugg 增量编译 UI 代码 + 测试代码（自动识别 androidTest classpath）→ 1~3s
     └── Jugg code swap 部署
  4. Agent 调用 run_ui_test(testClass="com.example.LoginScreenTest")
     └── Jugg IDE → ViewHierarchyClient → App TestRunner
     └── TestRunner 反射执行 @Test 方法
     └── 返回 { status: "PASS" } 或 { status: "FAIL", message: "..." }
  5. 若 FAIL → Agent 修改代码 → 回到步骤 1

总耗时：1~3s 编译 + <1s 测试执行 ≈ 2~4s/轮
对比 MCP 方案：1~3s 编译 + 5~10 次 MCP 调用（每次 0.5~1s）≈ 5~13s/轮
对比 Gradle androidTest：15~30s 编译 + 测试执行 ≈ 20~35s/轮
```

### 八、待讨论问题

1. **Phase 1 能力边界**：不使用 Instrumentation，Espresso 的 `onView().check()` 等 API 能否工作？需要 POC 验证。如果不行，Phase 1 可退化为"ViewHierarchyServer 获取 View 引用 + JUnit Assert 断言属性值"。
2. **androidTest jar 注入的时机**：是在 App 启动时就注入，还是首次 run_ui_test 时按需注入？后者更轻量但有首次延迟。
3. **多模块工程**：用户 App 依赖多个 library module，每个 module 可能有自己的 androidTest 依赖。Jugg 需要处理依赖去重和版本冲突解析（复用 Gradle 的 resolved configuration 可避免此问题）。
