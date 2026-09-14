# Library module compile R.jar 的 AGP 兼容修复方案

## 1. 文档状态

- 当前阶段：已按本方案完成实现与定向验证（2026-09-14）。
- 本文范围：记录问题背景、证据、讨论过程、最终设计、测试矩阵和实施步骤。
- 实现落点：`ModuleBuildPathInfo`（候选集合与远程同步路径）+ `BaseCompileContext`（按 module 类型选择单个 R provider）。
- 评审修正：第 5.6 节记录已确认的 `Unknown` 模块规则修正，第 8.3 节按修正后的规则描述最终实现。

## 2. 背景

用户使用 Jugg `3.4.3-release` 对一个大型 Android 多模块工程执行增量编译。`ime` 模块中的
`CandHandler.java` 引用了已有资源字段：

```java
R.drawable.icon_close_cand_normal_v2_t
```

Jugg 执行 javac 时报告：

```text
错误: 找不到符号
resId = R.drawable.icon_close_cand_normal_v2_t;
                  ^
符号:   变量 icon_close_cand_normal_v2_t
位置: 类 com.baidu.input.inputbase.R.drawable
```

用户提供的 Agent 调查进一步指出：源码没有引入新的直接调用，资源字段也并未从工程中删除；失败来自
javac 实际解析到的 `R$drawable.class` 不包含该字段。

本问题表面上是“资源文件找不到”，实际失败边界是：

> Jugg 为 module 源码组装 classpath 时选择了错误版本的 compile-time R.jar，导致前面的旧
> `R$drawable.class` 遮蔽后面包含目标字段的新 R.class。

## 3. 现场证据

### 3.1 日志直接证据

附件 `compile_2026-09-14_10-37-29.0.log` 可以确认：

1. 现场加载的插件为 `idea-3.4.3-release.jar`。
2. `ime` 在 Jugg project info 中的 `moduleType` 为 `Unknown`，`buildVariant` 为 `debug`。
3. 10:46:21.175 的 javac classpath 中，`ime` 自身使用的是：

   ```text
   ime/build/intermediates/compile_only_not_namespaced_r_class_jar/debug/R.jar
   ```

4. 同一 javac 命令后部还包含 application 聚合 R：

   ```text
   app/build/intermediates/compile_and_runtime_not_namespaced_r_class_jar/mainLineDebug/R.jar
   ```

5. 10:46:21.985，`CandHandler.java:3490` 对
   `icon_close_cand_normal_v2_t` 的解析失败。javac 已经找到
   `com.baidu.input.inputbase.R.drawable` 类，只是该类中没有目标字段。这符合“同名旧类先被解析”的
   classpath shadow，而不是 R 类整体缺失。

6. 自动补依赖或刷新后再次编译仍失败。现有恢复逻辑没有改变 R.jar 的选择，因此无法消除 shadow。

### 3.2 用户 Agent 的产物检查

以下信息来自用户提供的 Agent 调查截图，本文将其作为现场产物检查结果使用，不与日志直接证据混淆：

- 工程当前使用 AGP 7.4.2。
- `ime/build/intermediates/compile_r_class_jar/debug/R.jar` 是当前构建产生的 module compile R.jar，
  包含 `icon_close_cand_normal_v2_t`。
- `ime/build/intermediates/compile_only_not_namespaced_r_class_jar/debug/R.jar` 是更早构建留下的
  兼容产物，不包含目标字段。
- application 的聚合 R.jar 也包含目标字段，但在 javac classpath 中位于旧 module R.jar 之后。

### 3.3 当前代码证据

`ModuleBuildPathInfo` 当前把 R 产物分成了两个不完整的入口：

```text
rFilePath
  → compile_and_runtime_r_class_jar
  → compile_and_runtime_not_namespaced_r_class_jar

libraryRFilePathInLowAgp
  → compile_only_not_namespaced_r_class_jar
```

缺失的路径是：

```text
compile_r_class_jar
```

`allClassPath` 同时加入 `rFilePath` 和 `libraryRFilePathInLowAgp`。因此在 AGP 7.4.2 的 library/unknown
module 中，如果升级前的 `compile_only_not_namespaced_r_class_jar` 仍存在，Jugg 会把它加入
classpath，却完全看不到当前的 `compile_r_class_jar`。

`BaseCompileContext.getModuleDependencies()` 的关键顺序为：

```text
android.jar
→ Jugg temp module
→ included-build target R
→ 当前 module allClassPath
→ 依赖 module allClassPath
→ library dependencies
→ application/dynamic-feature final R.jar
→ task dependency paths
```

旧 module R.jar 位于 application 聚合 R.jar 之前，足以形成现场 shadow。

## 4. 根因定义

根因不是单纯的“AGP 旧产物未清理”，而是两个条件共同成立：

```text
AGP 升级后，同一 module build 目录保留旧 compile-only R.jar
→ 当前 AGP 生成新的 compile_r_class_jar
→ Jugg 只识别旧路径，不识别当前路径
→ javac 首先解析旧 R$drawable.class
→ 后面的正确 application R.class 无法覆盖同名类
→ 新字段解析失败
```

其中：

- 旧产物并存是触发条件。
- Jugg 缺失 `compile_r_class_jar` 兼容和错误的 R provider 选择是 behavior owner。
- javac、资源源码和 `DependencyMissingResolver` 都不是根因 owner。

## 5. 讨论过程与结论收敛

### 5.1 第一轮：原 Agent 的结论是否成立

原 Agent 判断“不是资源缺失，而是旧 library R.jar 遮蔽新 R.jar”。

复核结论：**主体成立。** 日志已直接证明 javac 先加入旧
`compile_only_not_namespaced_r_class_jar`，错误形态也证明同名 R 类存在但字段缺失。当前日志没有包含
两份 jar 的 `javap` 输出，因此“每个 jar 的具体字段集合”依赖用户 Agent 的现场产物检查；这不影响对
classpath shadow 的判断。

### 5.2 第二轮：“选最新”和“AGP 遗留产物”是否矛盾

初步表述曾把“选最新”限制得过窄，容易产生矛盾：既然新旧 AGP 产物并存，为什么不能直接选最新？

检查提交历史后确认：Jugg 的 `newestFile()` 本来就是为 dirty workspace 中不同 AGP 版本产物并存而设计。
已有修复已经对以下路径采用相同策略：

- application R.jar：`2f0940c00`
- javac 输出目录：`32d993281`
- merged manifest：`6e2d39ebd`

因此正确结论是：

> “选最新”适用于跨 AGP 版本、但语义角色相同的候选产物。

本问题中的两个 module compile R 路径语义相同：

```text
compile_r_class_jar
compile_only_not_namespaced_r_class_jar
```

它们应属于同一候选集合，并在集合内选择最新产物。

但是下面两类 R 语义不同，不能放进同一个候选集合比较：

```text
module compile R
application/dynamic-feature aggregate R
```

### 5.3 第三轮：`compile_r_class_jar` 是 application 还是 library jar

`compile_r_class_jar` 表达的是 Android component 的 compile-time R 产物，不应仅凭目录名定义为
application-only 或 library-only。不同 AGP、构建选项和 component 类型可能决定实际是否生成该目录。

本修复不通过“目录是否存在”反推 module 类型，而是维持明确的语义分工：

- Application/DynamicFeature 的最终资源表、styleable 和运行时一致性继续由聚合 `rFilePath` 表达。
- Library 的源码编译使用 module compile R。
- `Unknown` module 的归属按第 5.6 节的修正规则判定，不按产物存在性判定。现场 `ime` 属于"非 APK
  owner 的 Unknown"，使用 module compile R。

这样既能修复现场，也不会因为 application 目录下偶然出现 `compile_r_class_jar` 就改变 application R
的现有行为。

### 5.4 第四轮：是否影响 Jugg 新增资源和 library R 覆盖

Jugg 的资源增量链路是：

```text
增量资源编译生成 R.java
→ SourceCompiler 编译本轮 R.java
→ RDexForSubmoduleCompiler 为各 module 生成改包后的 R.class/R.dex
→ 改包后的 R.class 写入 Jugg temp module classpath
→ 随后的 Java/Kotlin 源码编译使用 temp R
```

历史提交 `89c2a3a83` 已把 Jugg 生成的 R.class 从 Gradle module 编译目录移到独立 temp classpath，避免
Gradle 后续报告重复 R class；`7508b1737` 保证新增资源和源码同轮变化时会重新生成各 module R。

因此本方案的约束是：

- Jugg temp R 必须继续位于所有 Gradle R.jar 之前。
- 不修改 `RDexForSubmoduleCompiler` 的输出位置。
- 不向 `compile_r_class_jar` 写入、覆盖或重新打包 Jugg 生成的 R。

在这些约束下，补充 Gradle module compile R 只影响“本轮没有更新该 R，或需要完整构建基线兜底”的
classpath 输入，不影响新增资源的同轮增量编译。

### 5.5 第五轮：是否还有其他场景副作用

讨论后确认的主要风险如下：

| 风险 | 约束 |
|---|---|
| application 同时存在 compile R 和 aggregate R，出现同名类竞争 | application/dynamic feature 不切换到 module compile R |
| 新增资源仍被 Gradle R.jar 遮蔽 | temp R 顺序保持最高，不调整增量生成链路 |
| 新旧 module compile R 同时进入 classpath | 候选集合只输出一个 `newestFile()` |
| application aggregate R 被替换，影响 styleable/final ID | `rFilePath`、`getRFiles()`、`StyleableFileGenerator` 不改 |
| 远程模式本地看不到新路径 | `compile_r_class_jar` 加入 `allBuildPaths` |
| AGP 降级时固定优先现代路径 | 现代和 legacy 候选按 mtime 选最新，不固定现代路径胜出 |
| included build / dynamic feature 已有目标 R 顺序回归 | `findIncludedBuildTargetRFiles()` 及其插入位置不改 |
| 修改 Gradle jar 导致重复类或缓存污染 | 所有 Gradle R.jar 只读 |

### 5.6 第六轮（评审修正）：`Unknown` 不能按 aggregate R 存在性判定归属

第三轮曾把 `Unknown` 规则表述为"存在 application 聚合 R 时保持 application 语义"。评审确认该表述
不成立，必须修正：

```text
aggregate R 文件存在  ≠  该 module 是 Application
```

原因是 dirty workspace 中 `compile_and_runtime_not_namespaced_r_class_jar` 也可能是升级前遗留产物；
仅凭文件存在把 `Unknown` 当成 Application，会继续选中遗留 aggregate R，现场 shadow 不会消失。

修正后的规则：

| `Unknown` 状态 | R provider |
|---|---|
| 已被 `BaseCompileContext` 解析为 `applicationModule` | aggregate `rFilePath` |
| 已被解析为 dynamic feature module | aggregate `rFilePath` |
| 其他 `Unknown` | 选中的 module compile R |

身份比较使用稳定的 module root 路径（`moduleRootDir.normalizedPath`），因此 project info 重新构造
出的同名/同根 module 副本仍然命中同一分支，不依赖对象相等或 `name` 字符串。

该修正同时解释了第 10.3 节的两个 `Unknown` 回归用例：
`Unknown` 非 APK owner 选 module compile R；`Unknown` 已被解析为 APK owner 时仍选 aggregate R。

## 6. 已确认事实、推断边界和非目标

### 6.1 已确认事实

- 现场 javac 使用旧 `compile_only_not_namespaced_r_class_jar`。
- 失败类存在，只缺少目标字段，符合 classpath shadow。
- 当前代码未识别 `compile_r_class_jar`。
- 现有“选最新”逻辑明确用于 AGP 产物并存。
- Jugg 本轮 R.class 位于独立 temp classpath，不应写回 Gradle 输出。
- `DependencyMissingResolver` 不负责改变 R.jar 选择。

### 6.2 推断边界

- “AGP 7.4.2 当前 jar 包含字段、旧 jar 不包含字段”来自用户 Agent 的产物检查，不是日志自身输出。
- 用户 clean 后是否一定恢复，取决于 clean 后是否完成正确 variant 的完整 Gradle 构建，以及 application
  聚合 R 是否覆盖源码所需 R package；现有证据只支持“很可能在本现场临时恢复”，不支持普遍保证。
- mtime 是仓库现有 best-effort 选择规则。复制产物时保留相同时间戳等极端场景仍存在歧义，不在本次引入
  hash、Gradle task graph 或内容扫描。

### 6.3 非目标

- 不修改 AGP 或用户工程的 clean 行为。
- 不自动删除用户工程中的旧 build 产物。
- 不扫描 R.jar 内容来推断哪个 jar“字段更多”。
- 不将 application aggregate R 与 module compile R 合并成一个通用候选集合。
- 不调整资源 ID、aapt2、styleable、DataBinding、R.dex 或部署协议。
- 不新增配置开关或通用 classpath provider 框架。

## 7. 方案比较

| 方案 | 优点 | 问题 | 结论 |
|---|---|---|---|
| 提示用户 clean 后重试 | 无代码改动；本现场可能恢复 | 只是移除触发条件；Jugg 仍不认识当前 module R 路径，远程/included-build 等场景仍可能失败 | 不作为修复，仅保留为临时 workaround |
| 固定优先 `compile_r_class_jar` | 实现最少；覆盖 AGP 升级 | AGP 降级后现代旧产物可能反向遮蔽当前 legacy 产物；违背已有“选最新”设计 | 不采用 |
| 把所有 R.jar 都加入 classpath | 不需要选择规则 | 同名 R class 更多，shadow 和 duplicate 风险更高 | 不采用 |
| 用 module compile R 替换所有 application aggregate R | 模型表面统一 | 改变 application、dynamic feature、styleable 和 final ID 语义，影响面远超现场 | 不采用 |
| Jugg 生成 R 后覆盖 Gradle R.jar | 能让 jar 含最新字段 | 污染 Gradle cache/output，历史上已导致 duplicate R class | 禁止 |
| 按语义拆分候选集合，各自选最新，再按 module 类型组装 classpath | 复用现有规则；兼容升级/降级；不改变 application 和增量 R | 需要在路径模型和 classpath owner 各做一处局部修改 | **采用** |

## 8. 最终方案

### 8.1 R 产物分层

建立两个独立候选集合：

```text
applicationAggregateRFileCandidates
  → compile_and_runtime_r_class_jar
  → compile_and_runtime_not_namespaced_r_class_jar

moduleCompileRFileCandidates
  → compile_r_class_jar
  → compile_only_not_namespaced_r_class_jar
```

说明：现有 `rFilePathCandidates` 可以继续作为 application aggregate 集合，不要求为命名统一进行无关重构。
新增 module compile 集合即可。

每个集合内部：

1. 只收集实际存在的 `R.jar`。
2. 支持 variant 根目录和 task 子目录，复用递归查找。
3. 按绝对路径去重。
4. 复用 `newestFile()` 按 jar `lastModified` 选择。
5. mtime 相同时保留候选声明顺序，与当前 application R 行为一致。

### 8.2 `ModuleBuildPathInfo` 落点

在 `main/src/main/java/com/sickworm/intellij/jugg/project/data/JuggProjectInfo.kt` 中：

1. 增加 `moduleCompileRFileDir`（`compile_r_class_jar/<variant>`）。
2. 增加 `moduleCompileRFileCandidates`：modern 目录递归候选在前，legacy
   `libraryRFileDirInLowAgp` 递归候选在后，按绝对路径去重，内部复用 `newestFile()`。
3. 增加 nullable `moduleCompileRFile`，没有真实候选时返回 `null`，不制造一个不存在的现代路径。
4. 保留 `libraryRFilePathInLowAgp`，供低 AGP application module 识别兼容逻辑使用；但源码编译不再把它作为
   一个独立 R provider 与现代路径同时加入。
5. `allClassPath` 改为不含任何 Gradle R.jar 的普通 classpath 集合，避免调用方通过文件名/path filter
   删除 R；`ClassFileLookupHelper` 只使用其中的目录项，行为不变。
6. 将 `moduleCompileRFileDir` 加入 `allBuildPaths`，保证远程 full build 后能够同步到本地。

不增加新的序列化字段；以上均为基于现有 `buildDir` 和 `buildVariant` 计算的属性。

### 8.3 `BaseCompileContext` 落点

在 `main/src/main/java/com/sickworm/intellij/jugg/project/BaseCompileContext.kt` 中增加小型私有方法，根据
`ModuleInfo.Type` 选择当前 module 的 Gradle R provider：

| Module type | R provider |
|---|---|
| `Application` | existing application aggregate `rFilePath` |
| `DynamicFeature` | existing aggregate `rFilePath` |
| `Library` | selected `moduleCompileRFile` |
| `JavaLibrary` | none |
| `Unknown` 且已解析为 APK owner | existing aggregate `rFilePath` |
| 其他 `Unknown` | selected `moduleCompileRFile` |

`Unknown` 的判定使用第 5.6 节的修正规则，只看 `BaseCompileContext` 已解析的 `applicationModule` /
`dynamicFeatureModules` 身份，不看 aggregate R 是否存在。

最终落点：

```text
getModuleClassPath(moduleInfo)     普通输出（allClassPath 中存在的项）+ 单个 Gradle R.jar
getGradleRFilePaths(moduleInfo)    按上表选择唯一 R provider，无候选时返回空列表
ModuleInfo.isApkOwnerModule()      Application/DynamicFeature，或已解析为 owner 的 Unknown
```

当前 module 和直接 module dependency 都使用同一选择方法，避免只修复 self module、依赖 module 仍继续
暴露旧 R。

`findApplicationModule()` 的低 AGP 分支仍用 `libraryRFilePathInLowAgp.exists()` 识别"哪个 module 不是
library"；该判定属于既有类型推断，不参与 R provider 选择，因此不受本次修改影响。同理，
`Application`/`DynamicFeature` module 不会在同一构建中同时产生
`compile_only_not_namespaced_r_class_jar`，低 AGP 下 application 的 R.class 位于 javac 输出目录，所以按
类型只选 aggregate 不会让低 AGP application 丢失 R。

### 8.4 最终 classpath 顺序

顺序保持为：

```text
android.jar
→ Jugg temp module classpath
→ included-build target/base/feature R
→ 当前 module 普通输出 + 按类型选择的单个 Gradle R
→ 依赖 module 普通输出 + 各自选择的单个 Gradle R
→ library dependencies / parent dependencies
→ 其他 application/dynamic-feature final R
→ task dependency paths
```

关键契约：

- Jugg temp R 仍然最高，保证新增资源和源码同轮编译。
- included-build target R 的既有优先级不变。
- 一个 module 最多贡献一个显式 Gradle compile R.jar。
- application aggregate R 仍保留为最终兜底和运行资源表来源。

### 8.5 日志

复用现有多候选 R.jar debug 日志，在发现多个 module compile R 候选时记录：

- module name/type
- 每个候选的 absolute path、lastModified、size
- 最终选择路径

日志只使用 `debug`，不向普通用户展示兼容细节；不因候选缺失阻断编译。

## 9. clean 与恢复行为

用户执行 clean 的效果是删除旧 `compile_only_not_namespaced_r_class_jar`，从而消除本次 shadow 条件。
如果随后完整构建生成了正确的 application aggregate R，本现场很可能恢复。

但 clean 不是正式修复，原因是：

- Jugg 仍忽略当前 `compile_r_class_jar`。
- 不是所有 module R package 都保证存在于 application aggregate R。
- 远程编译可能重新同步不完整路径集合。
- 后续 AGP 切换或未完全清理时问题会再次出现。

正式修复后的期望是：clean 与否不再决定 Jugg 能否选中当前 module compile R；dirty workspace 由
`newestFile()` best-effort 处理。

## 10. 测试价值与测试矩阵

### 10.1 测试价值判断

本问题通过测试价值门禁：

- “跨 AGP module compile R 候选只选择当前产物”是稳定兼容契约。
- “temp R、module R、application R 的优先级”是用户可观察的编译行为。
- 可以通过真实生成的最小 R.jar 和 javac 结果断言，不需要测试专用生产 seam，也不需要扫描源码实现。

### 10.2 失败证据

- 现场日志已经提供真实失败证据。
- 自动化红灯分两级取得：
  1. 新契约不存在时 `:main:compileTestKotlin` 直接编译失败（`moduleCompileRFile` 未解析）。
  2. 只补齐 `ModuleBuildPathInfo`、未改 `BaseCompileContext` 时，`unknown module uses module compile R
     when a stale aggregate R also exists` 与 `jugg temp R stays before gradle module compile R`
     以 `AssertionError` 失败；前者就是现场形态：遗留 aggregate R 先被 javac 命中，目标字段解析失败。
- 测试使用两个真实生成的 R.jar，分别声明 `sample.R.drawable` 的不同字段/取值，并通过真实 javac 编译
  引用方源码后读取内联常量值，因此断言的是"哪个 jar 生效"，不是生产实现的内部状态。

### 10.3 测试矩阵（实现后）

| 层级 | Owner | 场景 | 修改前预期 | 修改后预期 |
|---|---|---|---|---|
| L1 | `ModuleBuildPathInfoTest#moduleCompileRFile resolves AGP 7 compile_r_class_jar path` | 仅存在 `compile_r_class_jar` | 无法解析 module compile R | 返回现代 R.jar |
| L1 | `#moduleCompileRFile selects newest module compile R jar when AGP upgrade leaves both paths` | modern 新、legacy 旧 | 只返回 legacy | 选择 modern，候选不重复 |
| L1 | `#moduleCompileRFile selects legacy R jar when AGP downgrade updated it` | AGP 降级后 legacy 更新 | 仍可能固定选 modern | 选择较新的 legacy |
| L1 | `#moduleCompileRFile keeps candidate order when module compile R jars have same modified time` | 两者 mtime 相同 | 未定义新集合行为 | 保留声明顺序（modern 优先），结果确定 |
| L1 | `#moduleCompileRFile stays null when module has no compile R jar` | 无任何 module R | — | 返回 `null`，不制造现代路径 |
| L1 | `#allClassPath keeps gradle R jars out so each module resolves one R provider` | aggregate + modern + legacy 并存 | 旧 provider 混入普通 classpath | `allClassPath` 不含任何 R.jar |
| L1 | `#allBuildPathRelative includes AGP 7 module compile R jar directory` | 远程同步路径 | 不包含现代目录 | `allBuildPathRelative` 包含 `compile_r_class_jar` |
| L1 | `BaseCompileContextModuleDependenciesTest#unknown module uses module compile R when a stale aggregate R also exists` | `Unknown` 非 APK owner，同时有遗留 aggregate R 与 modern module compile R | 遗留 aggregate 先命中，真实 javac 找不到字段 | 选中 modern module compile R，javac 读到 modern 取值 |
| L1 | `#unknown module resolved as apk owner keeps using aggregate R` | `Unknown` 已被解析为 `applicationModule`，同时有 aggregate R 与 module compile R | 仍选 aggregate | 仍选 aggregate（防止按存在性误判） |
| L1 | `#jugg temp R stays before gradle module compile R` | temp R 与 Gradle module R 同名 | 若顺序回归会读旧值 | temp R 保持更前，源码读到本轮值 |
| 回归 | `BaseCompileContextModuleDependenciesTest` 现有 included-build 用例 | target/base/feature R 顺序 | 可能被本次重排改变 | 全部保持通过 |
| 回归 | `JuggCompileTest#compileResourceAddIds` | 新增资源和源码同轮编译 | 可能被 Gradle R 遮蔽 | 继续成功 |
| 回归 | `RDexForSubmoduleCompilerTest` | library R 改包与输出 | 可能误写 Gradle 目录 | 继续写 Jugg temp classpath |
| 回归 | `ReadProjectInfoScriptContentTest` | `ModuleBuildPathInfo` 进入 init script 生成输入 | — | 生成脚本语法与契约保持 |

不新增完整 L3 fixture：本次行为可以由真实 R.jar + javac 的 L1 编译结果稳定覆盖；已有资源 Flow 用例负责
保护增量阶段顺序。若实施时发现现有测试基础设施无法覆盖真实 Java/Kotlin classpath，再升级为
`JuggCompilerTest` 集成用例，不预先扩张 demo 工程。

### 10.4 定向验证命令

```bash
./gradlew :main:test \
  --tests "com.sickworm.intellij.jugg.project.data.ModuleBuildPathInfoTest" \
  --tests "com.sickworm.intellij.jugg.project.BaseCompileContextModuleDependenciesTest"

./gradlew :idea:test \
  --tests "com.sickworm.intellij.jugg.compile.JuggCompileTest.compileResourceAddIds" \
  --tests "com.sickworm.intellij.jugg.compile.RDexForSubmoduleCompilerTest"

./gradlew :idea:compileKotlin
```

若 Gradle 的方法级过滤无法匹配 Kotlin backtick/方法名，则只过滤对应测试类，不执行无过滤的全量
`:main:test` 或 `:idea:test`。

## 11. 实施步骤（已执行）

1. 在 `ModuleBuildPathInfoTest` 写现代/legacy 候选选择失败测试并确认红灯（编译级红灯）。
2. 在 `BaseCompileContextModuleDependenciesTest` 用两个真实 R.jar + javac 写现场 shadow 失败测试并确认红灯。
3. 在 `ModuleBuildPathInfo` 增加现代 module compile R 目录、候选集合和选最新逻辑。
4. `allClassPath` 改为不含 Gradle R.jar 的普通 classpath；保留 application/low-AGP 属性契约。
5. 在 `BaseCompileContext` 按 module type 选择单个 Gradle R provider，并用于当前 module 和 module dependency。
6. 把 `moduleCompileRFileDir` 加入远程同步 `allBuildPaths`。
7. 补充多候选 debug 日志（`logModuleCompileRFileCandidates`），只使用 `debug` 等级。
8. 同步 `main`/`idea` 测试侧 `SimpleCompileContext`，使其 classpath 组装与新契约一致。
9. 运行定向 L1、资源增量回归、init script 契约回归和 `:idea:compileKotlin`。
10. 同步知识库并检查 Wiki。

提交信息：

```text
[bugfix] fix library resources missing after AGP changes
```

## 12. 验收标准

### 12.1 现场行为

- AGP 7.4.2 且新旧目录并存时，`ime` javac classpath 使用
  `compile_r_class_jar/debug/.../R.jar`。
- 同一 module 的旧 `compile_only_not_namespaced_r_class_jar` 不再同时进入 classpath。
- `CandHandler.java` 能解析 `icon_close_cand_normal_v2_t`。
- `DependencyMissingResolver` 不需要通过重复刷新掩盖该问题。

### 12.2 兼容性

- 仅存在 legacy `compile_only_not_namespaced_r_class_jar` 的低 AGP 工程继续可编译。
- AGP 升级和降级后的 dirty workspace 均按最新有效 module compile R 选择。
- Application/DynamicFeature 继续使用 aggregate R；已被解析为 APK owner 的 `Unknown` 同样使用 aggregate R。
- 非 APK owner 的 `Unknown` 即使残留 aggregate R，也使用 module compile R。
- 新增资源和源码同轮编译继续使用 Jugg temp R。
- included build target/base/feature R 顺序不变。
- 远程编译可以同步现代 module compile R 目录。

## 13. 文档同步（已执行）

- `docs/ai_knowledge/04_engineering_project.md`
  - 区分 application aggregate R 与 module compile R 候选集合。
  - 记录两个集合内部按 mtime 选最新，集合之间不比较；`Unknown` 按已解析身份而非产物存在性归属。
  - 记录远程同步包含 `compile_r_class_jar`。
- `docs/ai_knowledge/02_compile_source.md`
  - 记录 temp R、module compile R、application final R 的顺序和职责，以及"一个 module 最多一个
    Gradle R.jar"的契约。
- `docs/ai_knowledge/09_plugin_runtime_debug.md`
  - 增加“R 类存在但字段缺失”时检查 javac/kotlinc 中第一个同名 R provider、AGP 新旧 module R 目录的排查入口。

`docs/wiki` 无面向用户的 R classpath / AGP 兼容排查页面，本次属于内部自动兼容，不新增配置或用户操作，
因此不更新 Wiki。
