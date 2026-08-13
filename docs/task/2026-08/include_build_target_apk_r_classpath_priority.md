# Include build 场景目标 APK R classpath 优先级

## 背景

2026-08-13 用户反馈：修改 RIF included build 中 `fragment` 的源码后，Jugg 增量编译成功，但运行时因资源 ID 错误发生 crash。

现场产物：

```text
jugg_scene_module-app_20260813_143324.zip
```

本问题只涉及源码增量编译时的 R 解析顺序。本阶段记录问题、比较方案并确定验证边界，不修改生产代码。

## 用户可见问题

RIF 作为 included build 可以独立执行 Gradle 构建，并产生基于 RIF 自身资源表的 `R.class` / `R.jar`。RIF 模块最终运行在 QQMusic 目标 APK 中时，QQMusic APK 的最终资源表可能为同名资源分配不同 ID。

Jugg 重新编译 RIF Fragment 时，classpath 优先命中了 included build 模块编译目录中的 R，而不是目标 QQMusic APK 的最终 R。Kotlin 编译器随后把错误的 `static final int` 资源 ID 内联到新 class，部署后出现资源不存在或类型不匹配的 runtime crash。

## 现场证据

### 字节码与 APK 资源表

本轮生成的 `MiddleGiftPanelFragment.class` 内联了以下资源 ID：

```text
layout = 0x7f0b0091
id = 0x7f080217, 0x7f080211, 0x7f080218,
     0x7f080219, 0x7f080245, 0x7f080215
```

QQMusic baseline APK 中：

```text
drawable type id = 0x08
id type id       = 0x09
layout type id   = 0x0c
```

例如 `0x7f080217` 在 QQMusic APK 中属于 `drawable/ai_assistant_message_song_play_all`，并不是 Fragment 源码引用的 view id。这说明新 class 内联的值不属于实际运行 APK 的资源表。

### 真实 Kotlin classpath

现场 Kotlin 命令尾部同时包含：

```text
QQMusic app R.jar
TME_RIF_Android/app phoneDebug R.jar
其他 APK / module R.jar
```

QQMusic R.jar 在尾部列表中实际位于 RIF app R.jar 之前，因此不能把问题简单归因于两个最终 R.jar 之间的排序。更可能的首个 R provider 位于更早的 RIF module `kotlinClassPath`、`javaClassPath` 或 `rFilePath`。

现场采集包未包含这些原始 classpath 目录，当前不能精确指出最先命中的具体文件；但字节码和目标 APK 资源表已足以证明编译采用了非目标资源表的 R。

## 当前实现

`BaseCompileContext.getModuleDependencies()` 当前顺序为：

```text
android.jar
→ tempModule allClassPath / temp libraries
→ current module allClassPath
→ direct module dependencies allClassPath
→ library dependencies
→ parent library dependencies
→ all final R.jar
→ task dependencyPaths
```

其中：

- `tempModule` 保存本轮 Jugg 生成的 class，包括本轮资源编译产生的 R class。
- `ModuleBuildPathInfo.allClassPath` 包含 `kotlinClassPath`、`javaClassPath`、`rFilePath` 等。
- `getRFiles()` 收集所有存在的 R.jar，并按文件大小降序排列，不区分模块最终属于哪个 APK。
- `finalRFiles` 被刻意放在普通模块输出之后，历史目的为让 Jugg 新生成的 R 优先于完整构建的旧 R.jar。

因此，当前实现把两种语义不同的 classpath 混在了一起：

1. 本轮 Jugg 生成、应当最高优先的 R。
2. included build 独立构建遗留、可能与目标 APK 不一致的 R。

## 根因

include build 是产生多份不一致 R 的场景条件，Jugg classpath 的错误优先级是直接根因：

```text
RIF 独立构建生成自己的 R
→ RIF R 与 QQMusic 目标 APK 最终资源表不一致
→ RIF module output 位于目标 APK R 之前
→ Kotlin 首先解析 RIF R 并内联资源 ID
→ 新 class 在 QQMusic 资源表中使用错误 ID
→ runtime crash
```

准确的问题定义是：

> 对源码引用的每个同名 `R$*` 类，classpath 中第一个 provider 没有保证属于本轮目标资源表。

## 目标顺序

概念上的优先级为：

```text
本轮 Jugg 生成的、与目标运行资源表一致的 R
→ 模块目标 APK 的最终 R
→ 普通模块编译输出和依赖
→ 其他非目标 APK / included build 的 R
```

这里的顺序必须覆盖全部 R provider，而不只是调整 `finalRFiles` 内部的 R.jar 顺序。普通 module class directory 也可能包含 `R.class` 并发生 classpath shadow。

## 已确认事实

- `moduleBelongsApkMap` 已能得到 module 的 primary `ApkFileUnit`，资源编译和部署链路已使用该归属。
- `applicationModule` 表示 base APK 的目标 Application module。
- `dynamicFeatureModules` 与 `moduleBelongsApkMap` 可以用于定位 feature APK 对应 module。
- 本轮资源编译生成的 R class 写入 `tempModule.buildPathInfo.javaClassPath`，当前已经位于普通 module output 前。
- 如果本轮没有资源变化，`tempModule` 不提供新的 R，源码编译应直接使用目标 APK 最终 R。
- 仅按 R.jar 文件大小排序不能表达 APK ownership。
- 仅把目标 R.jar 调整到 `finalRFiles` 第一位仍然无效，因为普通 module output 位于整个 `finalRFiles` 之前。

## 当前假设

- 对当前 non-namespaced/transitive R 工程，目标 Application 或 Dynamic Feature 的最终 R.jar 包含源码需要解析的业务 module R package。
  - 验证方式：使用 QQMusic/RIF 或测试 fixture 枚举目标 R.jar 中相关 `R$layout`、`R$id` entry。
- 同一轮资源和源码一起变化时，`tempModule` 生成的 R class 已包含新资源字段，并且应继续高于目标 APK R。
  - 验证方式：保留并扩展 `compileResourceAddIds` Flow，检查随后编译源码时使用新 ID。
- 当前事故中最先命中的错误 provider 来自 RIF module output，而不是尾部 RIF app R.jar。
  - 验证方式：在原始工程按真实 Kotlin `-cp` 顺序枚举第一个包含对应 `R$layout.class` / `R$id.class` 的目录或 jar。

## 方案比较

### 方案一：只重排 `finalRFiles`

按 module 目标 APK 把对应 R.jar 放在其他 R.jar 前面：

```text
tempModule
→ current/module dependencies
→ target APK R.jar
→ other R.jar
```

优点：

- 修改最小。
- 能解决仅由多个尾部 R.jar 相互遮蔽引起的问题。

缺点：

- 无法解决本次已识别的核心边界：前面的 module class directory 或 module `rFilePath` 仍可抢先提供错误 R。
- 目标顺序没有真正成立。

结论：不采用。

### 方案二：目标 APK R 整体前移到普通 module output 之前

按 module 归属选出目标 Application / Dynamic Feature R.jar，构造：

```text
android.jar
→ tempModule
→ target APK R.jar
→ current/module/library classpath
→ other R.jar
→ task dependencyPaths
```

优点：

- 改动集中在 `BaseCompileContext.getModuleDependencies()`。
- 复用现有 `moduleBelongsApkMap`，不新增 project info 字段或公共接口。
- 对当前有 application R.jar 的 AGP 场景，可以让目标 APK R 遮蔽 ordinary module output 中的错误 R。
- 保留 `tempModule` 第一优先级，不破坏本轮新增资源字段。

缺点与边界：

- `allClassPath` 内仍同时包含普通 class 和 R provider，代码只能通过“目标 R 前移”实现遮蔽，不能显式剥离其他目录中的 R。
- 低 AGP 没有 application R.jar、R class 只存在于 application Java output 时，不能把整个 application classes directory 前移；否则可能让其中普通业务 class 遮蔽当前 module output。该场景应保持既有 best-effort 顺序并打印 debug 日志。
- 如果目标 module 与 APK 的映射失败，只能安全回退到 `applicationModule`，不能猜测 included build Application。

结论：作为首版推荐方案。

### 方案三：显式建立 R provider 分层

将依赖拆为：

```text
generated R providers
target APK R providers
ordinary class providers
non-target R providers
libraries
```

编译前扫描 directory/jar 中的 `R.class` / `R$*.class`，或为 `ModuleBuildPathInfo` 增加只表达普通 class、R provider 的不同集合，确保每个层级没有隐含 R。

优点：

- 语义最准确，可以完整实现四层模型。
- 能处理低 AGP application Java output 中夹带 R 的情况。
- 可以输出明确的 R provider 诊断信息。

缺点：

- directory 无法只靠 classpath 参数排除其中少量 R class，需要创建过滤后的临时 classpath、调整完整构建产物布局，或引入自定义 classloader/file manager 逻辑。
- 影响 Java、Kotlin、APT/KAPT/KSP 等多个编译入口，复杂度和回归面明显扩大。
- 当前已有方案二可以覆盖已确认失败模式，直接实施方案三违反最简设计和 YAGNI。

结论：暂不采用；只有低 AGP 或其他目录级 R shadow 被真实报告后再升级。

## 推荐实现

### 1. 按 module 解析目标 R module

在 `BaseCompileContext` 内增加小型私有逻辑，输入待编译 `moduleInfo`，返回优先 R file：

```text
moduleBelongsApkMap.getBelongsApk(moduleInfo)
  → base APK: applicationModule
  → feature APK: 匹配同一 ApkFileUnit 的 dynamicFeatureModule
  → androidTest/self-targeting APK: 当前阶段仅在能确定对应 R module 时使用
  → 无法匹配: applicationModule best-effort fallback
```

选择到的 module 只有在 `buildPathInfo.rFilePath` 实际存在时才产生 `targetRFile`。

注意：这里复用 APK ownership，不根据 included build 路径、module 名称或 R.jar 大小猜测。

### 2. 对普通 classpath 去除重复的显式 R.jar

`current module allClassPath` 和 `module dependencies allClassPath` 自身包含各自 `rFilePath`。组装 ordinary classpath 时应过滤所有已知 `ModuleBuildPathInfo.rFilePath`，避免同一个 R.jar 同时出现在 ordinary 和 final R 层级。

不删除 `kotlinClassPath` / `javaClassPath`，因为它们还承载普通业务 class；其中若夹带 R，由排在前面的 `targetRFile` 完成遮蔽。

### 3. 调整最终依赖顺序

推荐首版实际顺序：

```text
android.jar
→ tempDependencies
→ targetRFile
→ current module ordinary classpath
→ module dependencies ordinary classpath
→ library dependencies
→ parent library dependencies
→ nonTargetRFiles
→ task dependencyPaths
```

其中：

- `tempDependencies` 继续最高，承载本轮 Jugg R 和本轮生成 class。
- `targetRFile` 位于所有完整构建 module output 之前。
- `nonTargetRFiles` 保留在最后作为兼容兜底。
- 最终列表按 absolute path 去重，并保持首次出现的优先级。

### 4. 回退与日志

- 目标 R.jar 存在：打印 debug，包含 source module、target APK、target R module、target R path。
- module 有 APK 归属但找不到对应 R.jar：打印 debug，保持既有 ordinary classpath + all final R 的 best-effort 行为。
- 不因辅助归属失败阻断源码编译。
- 不新增设置开关，不增加重试。

## 非目标

- 不修改 Gradle include build 的构建方式。
- 不要求 RIF 停止独立构建。
- 不修改 Android 资源 ID 分配。
- 不扫描或重写所有 module class directory。
- 不新增通用 classpath provider 抽象。
- 不在本次顺带调整 styleable、DataBinding 或 R.dex 的已有生成契约。

## 测试价值判断

该问题具有稳定、独立、可观察的行为：同名 R provider 并存时，源码编译必须使用所属 APK 的资源 ID。修改 classpath 顺序很容易在后续重构中再次破坏，值得自动化保护。

### 测试矩阵

| 层级 | 现有或拟新增 owner | 场景 | 修改前预期失败 | 修改后预期结果 |
|---|---|---|---|---|
| L1 | 新增 `BaseCompileContextModuleDependenciesTest`，或扩展最接近的 context 测试 | target APK R 与 included build R 同时存在，included module output 在普通依赖中 | `getModuleDependencies()` 中错误 module output 位于 target R 前 | temp R 第一、target R 位于 ordinary module output 前、other R 最后 |
| L1 | 同一测试 owner | target R 也出现在 module `allClassPath` | R.jar 重复且首次位置不受控 | 显式 R.jar 去重，首次位置为 target R 层级 |
| L1 | 同一测试 owner | target R.jar 不存在的低 AGP/不完整现场 | 错误前移 application classes directory或丢失原 classpath | 保持 ordinary classpath，走 best-effort fallback |
| L2 | `ModuleApkBelongsUtilsAndroidTestTest` 或 context 测试 | base、dynamic feature、self-targeting androidTest 的 APK owner | target R module 选错 | 使用与 module primary APK 一致的 R module；无法确定时明确 fallback |
| L3/Flow | `JuggCompilerTest` 或专用 include-build Flow fixture | 两套同名 R 具有不同常量，修改 Fragment 源码后编译 | class 内联 included build 的错误值 | class/dex 中内联目标 APK ID，资源 + 源码同轮变化仍使用 Jugg 新 R |

L3 是最终 runtime 风险的 owner。若现有 demo 无法稳定构造 included build 两套真实 R，可先用两个真实生成的最小 R.jar 完成 L1 编译产物断言，并保留 QQMusic 现场字节码对比作为替代验证；不得仅断言私有 list 的字符串顺序后宣称 runtime 已覆盖。

## 实施步骤草案

用户明确授权实现后，按以下顺序执行：

1. 建立失败测试：构造 target R 与 included-build R 的相同 `R$id` 字段、不同常量，证明修改前源码 class 内联错误值。
2. 在 `BaseCompileContext` 中实现按 `moduleBelongsApkMap` 选择 target R module/file 的私有逻辑。
3. 将已知 module `rFilePath` 从 ordinary `allClassPath` 层过滤出来。
4. 按推荐顺序组装 dependencies，按绝对路径稳定去重。
5. 同步 main/idea 测试用 `SimpleCompileContext`，避免测试 context 与生产语义长期漂移；优先抽取最小共享排序 helper，只有确实能减少重复并保持职责清晰时才做。
6. 运行定向 L1/L2 测试、`JuggCompilerTest` 对应 Flow、`./gradlew :idea:compileKotlin`。
7. 检查真实编译命令和产物字节码，确认第一个相关 R provider 与内联常量属于目标 APK。
8. 实现完成后同步 `docs/ai_knowledge/02_compile_source.md`、`02_compile_resource.md` 和必要的 `09_plugin_runtime_debug.md` 排查说明。

## 待决策事项

1. 首版是否只覆盖存在目标 R.jar 的主流 AGP 场景。
   - 推荐：是。低 AGP 不存在可安全前移的纯 R provider，保留 best-effort，不扩大修改范围。
2. androidTest/self-targeting test APK 是否纳入首版 target R 选择。
   - 推荐：保持现有 APK ownership，只有能确定对应 R module/file 时优先；否则回退，不为本问题新增 test APK R 模型。
3. 是否要求新增真实 include-build L3 fixture。
   - 推荐：先检查现有测试基础设施能否低成本复用；若需引入完整第二 Gradle build，先以真实 R.jar 编译产物测试 + 用户现场回归作为最小充分证据。
