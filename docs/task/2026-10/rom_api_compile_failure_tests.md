# ROM API 遮蔽的 Java / Kotlin 失败测试

## 范围与依据

本任务只准备修复前的失败测试，不实现恢复策略。Behavior owner 为
`JavaCompilerInvoker` / `KotlinCompilerInvoker`，新增 `RomApiCompileTest` 作为同一份
Java / Kotlin 场景矩阵的 L2 编译回归 owner。

- 报告 `261e2001`：Java 引用 `BluetoothProfile.HID_HOST` 失败；SDK 36 的
  `android.jar` 在 ROM framework jar 前面。
- 报告 `752724a0`：Kotlin 的 `WirelessNetworkManager.kt` 直接调用六个 Wi-Fi
  方法失败；SDK 35 的 `android.jar` 在第 1 项，ROM `framework.jar` 在第 127 项。
- 本地实验补充了直接引用嵌套类型、方法重载、构造函数重载三种错误。
- 原有间接父类型场景：Kotlin 需要既有换序重试；Java 直接按 binary name 解析，原本即可成功。
- 测试价值：保护用户源码在已有 ROM 编译依赖下能够完成增量编译的可观察结果；断言真实
  编译结果和 `Use.class` 产物，不匹配生产源码、不反射私有状态、不添加生产代码测试 seam。

前置阅读：`00_overview.md`、`99_index.md`、`98_code_map.md`、
`09_plugin_runtime_debug.md`、`02_compile_core.md`、`02_compile_source.md`、
`06_testing.md`，以及历史方案 `docs/task/2026-09/android_hidden_api_classpath_order_retry.md`。

## 测试结构

路径：`main/src/test/java/com/sickworm/intellij/jugg/compiler/source/RomApiCompileTest.kt`。

使用真实 SDK 35，在 JUnit 临时目录中以 javac 编译最小完整 framework 类和间接父类型，
打成临时 `framework.jar`。ROM fixture 只模拟已确认的类和成员查找契约，不代表报告中的
实际 ROM jar；报告附件没有包含后者。

每个场景分别运行 Java / Kotlin，每种语言有两项测试：

1. `ROM APIs compile when framework precedes SDK`：关闭自动重试，验证正确依赖顺序下
   的源码本身能够编译，并生成 `Use.class`。
2. `ROM APIs recover when SDK precedes framework`：先关闭自动重试取得确定失败和直接源码
   诊断，再开启自动重试断言应当成功。Java 间接父类型场景的无重试基线本来就应成功。

第二项的成功断言是待修复行为，当前保留红灯，不使用 `@Ignore` 或预期异常隐藏缺口。
测试未规定将来应当如何检测遮蔽、调整顺序或组织重试。

## 场景及当前结果

| 参数 | 代表 API / 代码形态 | Java SDK 在前 | Kotlin SDK 在前 | framework 在前对照 |
|---|---|---|---|---|
| `HIDDEN_FIELD` | `BluetoothProfile.HID_HOST` | 失败 | 失败 | 两种语言均通过 |
| `HIDDEN_METHODS` | 下列六个 Wi-Fi 方法 | 失败 | 失败 | 两种语言均通过 |
| `HIDDEN_NESTED_TYPE` | 直接声明 `WifiManager.SoftApCallback` 类型 | 失败 | 失败 | 两种语言均通过 |
| `METHOD_OVERLOAD` | `Activity.finish(Int)` | 失败 | 失败 | 两种语言均通过 |
| `CONSTRUCTOR_OVERLOAD` | `Activity(Int)` | 失败 | 失败 | 两种语言均通过 |
| `INDIRECT_SUPERTYPE` | 继承 `RomSoftApCallback`，其父类为隐藏的嵌套类型 | 无需重试，通过 | 既有重试恢复，通过 | 两种语言均通过 |

Wi-Fi 场景同时编译报告中的六种调用：`WifiInfo.removeDoubleQuotes`、
`WifiManager.connect`、`WifiManager.forget`、`WifiManager.disconnectForNetId`、
`WifiConfiguration.getLastConnectedTimestamp`、`WifiManager.ipConfigurationLostReceived`。
矩阵的前五类场景都是直接源码引用，不会产生现有的父类型诊断；方法和构造函数重载会产生
调用匹配错误，不能仅靠扩大 `unresolved reference` 字符串覆盖。

## 定向运行与失败证据

需要 JDK 17+、`ANDROID_HOME/platforms/android-35/android.jar` 及同目录
`core-for-system-modules.jar`。Java 源码和 Kotlin JVM target 均为 17；Kotlin 调用
Jugg 内置 compiler，语言版本由现有 invoker 配置为 1.9。当前矩阵不覆盖 Java source 8 的
独立 boot classpath 分支，也不遍历项目 Kotlin compiler 版本。

本机仓库 Gradle Wrapper 为 7.3.3，使用 JDK 17 启动：

```bash
JAVA_HOME=/Users/wormchen/Library/Java/JavaVirtualMachines/corretto-17.0.17/Contents/Home \
ANDROID_HOME=/Users/wormchen/Library/Android/sdk \
./gradlew :main:test \
  --tests 'com.sickworm.intellij.jugg.compiler.source.RomApiCompileTest' \
  --console=plain
```

2026-10-23 首轮实跑：**24 项，10 项失败，14 项通过，0 项跳过**。10 项失败恰好是上述
前五种场景的 Java / Kotlin 恢复断言；全部 12 项 framework 在前的对照成功，两个
间接父类型恢复用例成功。命令返回非零是预期红灯，不是测试编译或初始化失败。

Gradle 报告：`main/build/reports/tests/test/index.html`；JUnit XML：
`main/build/test-results/test/TEST-com.sickworm.intellij.jugg.compiler.source.RomApiCompileTest.xml`。
这些构建产物不提交，后续运行可能覆盖。

最终定向验证在同一命令中额外加入以下两个过滤项：

```bash
--tests 'com.sickworm.intellij.jugg.compiler.source.JavaCompilerInvokerTest' \
--tests 'com.sickworm.intellij.jugg.compiler.source.kotlin.AndroidJarClasspathRetryTest'
```

结果为 **39 项，10 项预期失败，29 项通过，0 项跳过**；新增矩阵仍为 24 项、10 项红灯，
既有 `JavaCompilerInvokerTest` 的 9 项和 `AndroidJarClasspathRetryTest` 的 6 项全部通过。
原有 `SourceCompileTest` 的 demo/Dex 场景本次未重跑，间接父类型兼容已在新增矩阵中通过
真实 invoker 等价验证；本任务未修改 source/dex 编排。

## 变更边界

- 不修改生产编译器、SDK 顺序、诊断匹配或恢复策略。
- 不修改 Android demo、ROM 二进制依赖、设备或用户报告原件。
- 不涉及产品行为或架构变更，不需要同步 AI 知识库和 Wiki。
- 修复会话应复用本测试矩阵，最终让 10 项红灯转绿并保留现有成功对照。
