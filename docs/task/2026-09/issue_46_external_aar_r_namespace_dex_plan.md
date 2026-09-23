# Issue #46 外部 AAR namespace R.dex 生成方案

## 1. 文档状态

- 当前阶段：已实施，等待独立 review；实施结果与验证证据见 §16。
- 对应 Issue：[tencentmusic/jugg#46](https://github.com/tencentmusic/jugg/issues/46)。
- 现场报告：`a0247301`。
- 已有失败用例：`JuggCompilerTest.external AAR resource update generates library namespace R dex`。
- 失败用例提交：`186b54362 [test] reproduce missing external AAR R dex after resource update`。
- 本文定义实施范围、兼容策略与验证门禁。

## 2. 问题描述

外部 AAR 的业务字节码会以自身 namespace 访问资源，例如：

```text
com.mars.feature.photostudio.R$string.photo_studio_album_open_test_toast
```

这个 `R$string` 不在 AAR 的 `classes.jar` 中。AAR 编译时由 Android Gradle Plugin 提供 compile-time
R class，使业务源码可以通过编译；宿主应用构建时，再为该依赖 namespace 生成最终可运行的 R class 并打入
APK DEX。

Jugg 增量资源编译当前只更新宿主 application namespace 的 `R.dex`。当外部 AAR 资源发生变化时，新的资源
字段已进入宿主资源表，但外部 namespace 的 R class 没有同步生成，运行时会出现：

```text
NoSuchFieldError
  → 依赖类初始化失败
  → 后续访问可能表现为 NoClassDefFoundError
```

因此，外部 namespace R.dex 不是 AAR 自带产物，而是宿主资源构建链路必须提供的运行时桥接类。

## 3. 现有链路与根因

### 3.1 外部依赖变更进入临时模块

`DependencyDiffResultHelper` 会把外部 AAR 的 manifest、res 和 classes.jar 转换为
`ChangedFile`，并统一归属 `tempModule`。同一依赖的文件通过 `dependencyName` 关联。

### 3.2 RDex 编译器跳过临时模块

`RDexForSubmoduleCompiler` 当前按 Jugg module 的 namespace 生成改包后的 R.dex，并显式排除
`tempModule`。这个限制源于临时模块没有自身 Android Manifest，无法按 module 维度取得 namespace。

### 3.3 编译任务丢失外部 Manifest 上下文

`JuggCompiler` 创建 RDex 任务时只保留 Java、Kotlin 和 Resource 输入，外部 AAR Manifest 不会进入
RDex 编译器。即使取消 `tempModule` 过滤，编译器仍无法知道每个 Resource 属于哪个 namespace。

### 3.4 根因定义

失败边界是：

```text
外部 AAR 资源变化
→ 资源编译更新宿主主 R.dex
→ tempModule 没有可用的依赖 namespace
→ RDexForSubmoduleCompiler 不生成外部 namespace R.dex
→ 外部库字节码访问自身 R$string 时找不到新字段
```

behavior owner 是外部依赖信息读取、变更元数据透传与 RDex 目标计算的组合，而不是 AAR 的
`classes.jar` 或业务代码。

## 4. namespace 来源与兼容结论

### 4.1 不能只依赖 Manifest `package`

现代 AGP 以 Gradle `namespace` 决定生成 R class 的包名。Android 官方文档明确区分：

- module `namespace`：生成 R、BuildConfig 等类的包名；
- application `applicationId`：最终安装包标识；
- 最终合并后的 application Manifest `package`：可能等于 `applicationId`，不能反推依赖 namespace。

AAR 格式要求包含 `AndroidManifest.xml`，但格式契约没有保证高版本 AAR 的 Manifest 必须继续携带
`package` 属性。因此 AAR Manifest `package` 可以作为兼容回退，不能作为唯一来源。

参考：

- [Android module namespace](https://developer.android.com/build/configure-app-module#set-namespace)
- [Create an Android library / AAR contents](https://developer.android.com/studio/projects/android-library#aar-contents)

### 4.2 已确认的稳定语义来源

AGP 的 `android-symbol-with-package-name` artifact 对应 `package-aware-r.txt`。其首个非空行保存该
Android library 的 R package/namespace，后续内容为资源符号表。它与外部库编译时使用的 R class
语义一致，是首选来源。

该 artifact 类型仍属于 AGP 构建模型能力，不视为跨所有 AGP 版本都必然可用。读取必须遵循
Best-effort：当前 AGP 不支持或结果为空时，只降级到 AAR Manifest，不中断其他依赖信息采集。

### 4.3 最终优先级

外部依赖的 R namespace 按以下顺序解析：

1. `android-symbol-with-package-name` / `package-aware-r.txt` 的首个非空行；
2. AAR `AndroidManifest.xml` 的 `package`；
3. 两者均不存在时，明确失败并提示执行完整 Gradle 构建，不静默遗漏外部 R.dex。

如果两种来源同时存在但值不同，以 symbol artifact 为准，并记录 debug 日志说明冲突与降级结果。

## 5. 目标与非目标

### 5.1 目标

- 外部 AAR 资源变化时，识别其真实 R namespace。
- 从宿主本轮生成的主 R DEX 派生外部 namespace R.dex。
- 同一轮支持多个外部 AAR namespace。
- 多个依赖共享 namespace 时只生成一次。
- 保持普通 project module 的现有 R.dex 生成与 APK 路由行为不变。
- 兼容旧 project info 缓存和不提供 symbol artifact 的 AGP。

### 5.2 非目标

- 不从 AAR `classes.jar` 扫描或推断 R 引用。
- 不按 Maven group/artifact 坐标猜测 namespace。
- 不为每个外部 AAR 建立新的虚拟 `ModuleInfo`。
- 不改变资源 ID 分配、资源编译或主 R.dex 的生成方式。
- 本次仅覆盖 Issue #46 的 base APK 外部依赖场景；不恢复已在 `tempModule` 聚合过程中丢失的
  dynamic-feature 依赖归属。
- 纯 jar 依赖或没有资源变更的 AAR 不触发外部 R.dex 生成。

## 6. 最终设计

### 6.1 在现有依赖模型携带可选 namespace

为 `LibraryDependency` 增加可选字段：

```kotlin
val rPackageName: String? = null
```

使用可选字段而不是新增一种 `LibraryDependency` artifact，原因是：

- 旧缓存中不存在该字段，反序列化后自然为 `null`；
- 依赖文件数量和路径集合保持不变；
- `LibraryDependencySet.equals` 当前只比较文件路径和 CRC，不会因为 metadata 从 `null` 补全而把
  所有 AAR 误判为更新；
- namespace 是依赖语义元数据，不是需要参与增量变更判断的新输入文件。

实现时必须检查 project info 的生成、序列化、反序列化、合并、备份和临时模块缓存路径，确保复制
`LibraryDependency` 时不会丢失该字段。

### 6.2 Gradle project info 读取 symbol artifact

`GradleProjectInfoReader` 在现有 `android-res`、`android-manifest`、`jar`、`processed-jar` artifact
读取之外，Best-effort 请求：

```text
android-symbol-with-package-name
```

处理规则：

1. 按 Gradle component identifier 与 dependency name 建立关联；
2. 读取 `package-aware-r.txt` 的首个非空行；
3. 把结果附加到同一依赖已有的 res、manifest、jar `LibraryDependency` 对象；
4. artifact view 不可用、文件不存在或内容为空时保留 `null`，让后续走 Manifest 回退；
5. 不把 symbol 文件本身加入 `libraries` 集合，不参与 CRC diff。

生成的 `readProjectInfo.gradle.kts` 必须通过现有生成器刷新，不手工维护两份实现。

### 6.3 在依赖变更边界解析并透传 namespace

`DependencyDiffResultHelper` 已经可以通过 `dependencyName` 关联同一 AAR 的 manifest、res 和 jar。
在这个边界解析最终 namespace，并把它写入外部 Resource `ChangedFile.extraInfo`：

```text
KEY_R_PACKAGE_NAME
```

解析规则：

```text
LibraryDependency.rPackageName
  → AAR Manifest package
  → 缺失标记
```

只给外部 Android resource 输入附加该值。普通工程 module 继续使用 `module.namespace` 和已有 Manifest
逻辑，避免改变现有调用链。

### 6.4 RDex 编译器按 namespace 计算目标

`RDexForSubmoduleCompiler` 保留普通 module 逻辑，并增加 `tempModule` 分支：

1. 从 Resource `CompileFile.extraInfo` 读取 `rPackageName`；
2. 收集本轮需要生成的外部 namespace；
3. 排除 application 主 R package，避免生成重复目标；
4. 按 namespace 去重，不再仅按 `module.name` 去重；
5. 对宿主本轮产生的所有主 R `*.dex` 执行 `DexPackageRenamer`；
6. 将每个 namespace 的 R class/R.dex 写入现有 temp module 输出和 base APK 目标。

普通 project module 的目标 APK 继续由 `ModuleApkBelongsUtils` 决定。外部依赖继续沿用当前
`tempModule` 的 base APK 路由，不在本补丁中引入额外 owner 映射。

### 6.5 缺失信息与失败策略

当本轮主 R 已变化，且某个外部 Android resource 依赖既没有 symbol namespace，也没有 Manifest
`package` 时：

- 不吞掉问题；
- 不猜测 namespace；
- 抛出包含 dependency name 的明确编译异常；
- 提示用户执行完整 Gradle 构建作为恢复路径。

读取 symbol artifact 失败本身不是全局失败条件，因为 Manifest 仍可能提供有效回退。只有确认需要生成
该依赖 R.dex 且所有 namespace 来源都缺失时，才终止本轮增量编译。

## 7. 数据流

```text
Gradle dependency artifacts
  ├─ android-symbol-with-package-name ─首选─┐
  ├─ android-manifest ───────────────回退──┤
  └─ android-res ─────────────────资源变化─┘
                         ↓
LibraryDependency.rPackageName
                         ↓
DependencyDiffResultHelper
                         ↓
Resource ChangedFile.extraInfo[KEY_R_PACKAGE_NAME]
                         ↓
JuggCompiler RDex task
                         ↓
RDexForSubmoduleCompiler 按 namespace 去重
                         ↓
宿主主 R*.dex 改包
                         ↓
base APK: <external namespace>/R*.dex
```

## 8. 预计改动范围

| 文件 | 计划改动 |
|---|---|
| `main/.../project/data/JuggProjectInfo.kt` | 为 `LibraryDependency` 增加可选 `rPackageName` |
| `main/.../gradle/script/GradleProjectInfoReader.kt` | Best-effort 读取 symbol artifact，并附加到现有依赖对象 |
| `main/src/main/resources/gradle/readProjectInfo.gradle.kts` | 通过生成流程同步 project info 读取脚本 |
| `main/.../project/BaseCompileContext.kt` | 保存 temp libraries 时保留 namespace 元数据 |
| `main/.../project/dependency/DependencyDiffResultHelper.kt` | 合并 symbol/Manifest namespace 并透传到资源变更 |
| `main/.../compiler/CompilerExt.kt` | 增加 `KEY_R_PACKAGE_NAME` 及最小访问方法 |
| `main/.../compiler/overlay/RDexForSubmoduleCompiler.kt` | 支持 tempModule 外部 namespace、多目标与去重 |
| 现有相关测试文件 | 增加模型兼容、元数据透传和多 namespace RDex 回归 |

路径表中的 `...` 仅用于提高可读性，实际修改前以 `98_code_map.md` 和 IDE 索引定位结果为准。

## 9. 兼容性与迁移

| 场景 | 预期行为 |
|---|---|
| 新 project info + symbol artifact 可用 | 使用 `package-aware-r.txt` namespace |
| 新 project info + symbol artifact 不可用 | 回退 AAR Manifest `package` |
| 旧 project info 缓存 | `rPackageName=null`，不因字段新增导致反序列化失败 |
| 旧缓存与新读取结果仅 metadata 不同 | 不产生虚假的 dependency update |
| symbol 与 Manifest namespace 不同 | symbol 优先，记录 debug 日志 |
| 两种来源都缺失且资源发生变化 | 明确失败，提示完整 Gradle 构建 |
| 多个 AAR 使用相同 namespace | 只生成一组 R*.dex |
| 多个 AAR 使用不同 namespace | 分别生成对应 R*.dex |
| 普通 project module | 维持现有 module namespace 与 APK 路由 |
| 外部依赖属于 dynamic feature | 本次仍按 tempModule/base APK 处理，不扩展归属模型 |

## 10. 测试价值与验证方案

### 10.1 测试价值门禁

该问题保护的是稳定、用户可观察且容易回归的行为：外部 AAR 新增资源后，其既有业务代码能够在增量部署
后正常读取自身 namespace 下的新 R 字段。测试不需要生产代码专用 seam，符合新增自动化测试条件。

### 10.2 TDD 与测试 owner

| 层级 | owner / 用例 | 断言 |
|---|---|---|
| L1 | `DependencyDiffResultTest` | `rPackageName` 从空变为有效值时，不单独触发 dependency update |
| L1 | project info script/content 测试 | 生成脚本包含 symbol artifact 读取，且生成源与资源副本一致 |
| L1 | `RDexForSubmoduleCompilerTest` | 同一 tempModule 中两个 namespace 生成两组 R.dex |
| L1 | `RDexForSubmoduleCompilerTest` | 相同 namespace 去重；主 application namespace 不重复生成 |
| L1 | `RDexForSubmoduleCompilerTest` | 需要外部 R.dex 但 namespace 缺失时明确失败 |
| L2 | `JuggCompilerTest.external AAR resource update generates library namespace R dex` | 现有失败用例由红转绿，并强化检查外部 `R$string.dex` |
| 回归 | `RDexForSubmoduleCompilerTest` 现有 project module 用例 | feature/project module 的目标 APK 行为不变 |
| 回归 | `JuggCompilerTest.testCompileResDir` | 普通资源目录增量编译保持正常 |

### 10.3 Gradle 版本验证

涉及 project info Gradle 脚本，实施后至少执行：

- `ReadProjectInfoScriptContentTest`；
- 当前环境可运行的 Gradle 7 与 Gradle 9 兼容测试；
- Gradle 5/6 在兼容 JDK 可用时执行，否则记录环境限制与替代的脚本内容验证；
- `GradleProjectInfoReaderAndroidTestTest` 中与 Android artifact 读取相关的定向用例。

不执行无 `--tests` 过滤的全量 `:main:test` 或 `:idea:test`。

### 10.4 L3 判断

本补丁不改变 deploy 编排和设备安装协议，不新增独立 L3 自动化用例。Issue 报告和现有失败复现提供运行时
失败证据；实现完成后应在可用 Android 示例工程中执行一次外部 AAR 新增 string、Jugg 增量部署、调用库
代码的替代验证，确认 toast/资源读取成功且日志中生成了外部 namespace R.dex。

## 11. 实施顺序

1. 强化现有 Issue #46 失败用例，明确断言 `R$string.dex`，保留红灯证据。
2. 为 `LibraryDependency` 增加可选字段，并补旧缓存与 diff 稳定性测试。
3. 在 project info 读取端接入 `android-symbol-with-package-name`，同步生成脚本并完成 Gradle 兼容验证。
4. 在 `DependencyDiffResultHelper` 实现 symbol 优先、Manifest 回退和冲突记录，将结果透传到 Resource。
5. 在 `RDexForSubmoduleCompiler` 实现 tempModule namespace 收集、去重、改包和缺失失败。
6. 运行 L1/L2 定向测试，确认既有 project module 行为未回归。
7. 使用 Android 示例工程或 Issue 现场执行替代运行时验证。
8. 同步 `docs/ai_knowledge/02_compile_resource.md`；如 project info 数据契约说明受影响，同步
   `docs/ai_knowledge/04_engineering_project.md`，并检查现有 Wiki 是否存在需更新页面。

## 12. 风险与约束

### 12.1 AGP 内部 artifact 名称变化

风险：部分 AGP 不支持 `android-symbol-with-package-name`。

约束：artifact view 读取采用局部 Best-effort；失败只关闭该来源，继续使用 Manifest 回退。

### 12.2 全量依赖被误判更新

风险：把 `package-aware-r.txt` 作为新依赖文件加入列表会改变集合大小，旧缓存升级后可能把全部 AAR
标记为更新。

约束：symbol 文件只提供 metadata，不加入 `LibraryDependencySet` 文件集合；增加专门回归测试。

### 12.3 namespace 与依赖关联错误

风险：仅按文件名或 Maven artifactId 关联会在同名依赖、classifier 或 included build 中串包。

约束：优先复用 Gradle component identifier 和当前 dependency name 生成链路；不新增字符串猜测规则。

### 12.4 dynamic feature 路由

风险：tempModule 当前不能区分外部依赖最终属于 base 还是某个 dynamic feature。

约束：本次保持现状并明确只修复 base APK 场景。未来若有真实 feature 失败证据，再单独扩展依赖 owner
模型，避免 Issue #46 补丁过度设计。

## 13. 验收标准

- Issue #46 失败用例在修改生产代码前稳定失败，在实现后稳定通过。
- 外部 AAR 新增 string 资源后，产物中存在其 namespace 下的 `R$string.dex`，且包含新增字段。
- 外部库原有业务代码可以正常读取该字段，不再出现 `NoSuchFieldError` / 连带
  `NoClassDefFoundError`。
- 同轮多个 namespace 均被生成，相同 namespace 不重复生成。
- 旧 project info 缓存可读取，不会因为 metadata 补全触发全量外部依赖更新。
- symbol artifact 不可用时，带 Manifest `package` 的 AAR 仍可完成增量编译。
- namespace 完全不可解析时返回清晰错误，不伪造成功。
- 普通 module、feature module 和非外部资源增量用例保持通过。

## 14. 明确不采用的方案

- **仅读取 AAR Manifest `package`**：当前常见但格式契约不保证，不能作为高版本长期唯一来源。
- **扫描 classes.jar 的 R 引用**：可能同时引用自身、宿主和传递依赖 R，无法可靠确定生成目标。
- **使用 Maven 坐标推导 namespace**：坐标与 Java package 没有强制关系。
- **把 symbol 文件作为新的依赖 artifact**：会影响集合大小和旧缓存 diff，扩大变更面。
- **每个 AAR 建立虚拟 module**：Issue #46 只需要 namespace 与目标 DEX，额外模块模型属于过度设计。
- **本次同时补 dynamic-feature owner 映射**：缺少对应失败证据，应独立讨论和验证。

## 15. 实施授权边界

当前授权仅为落地本方案文档。开始修改生产代码、测试或知识库前，需要用户另行明确授权实施。

## 16. 实施结果

生产改动与 §8 计划范围一致：

- `LibraryDependency` 增加可选 `rPackageName`。主构造函数不带默认值，由 2 参 / 4 参 secondary constructor 与 5 参 `libraryDependency` 工厂提供兼容入口。
- `GradleProjectInfoReader` Best-effort 读取 `android-symbol-with-package-name`，按 component identifier 关联到同一依赖的 res / manifest / jar；`readProjectInfo.gradle.kts` 全部由 `buildReadProjectInfoScript` 刷新生成。
- `DependencyDiffResultHelper` 按 `LibraryDependency.rPackageName` -> AAR Manifest `package` -> `null` 解析，冲突时 symbol 优先并记录 `JuggLogger.debug`；结果只写入外部 Resource 变更的 `r_package_name`。
- `RDexForSubmoduleCompiler` 保留普通 module 逻辑，新增 temp module 分支：按 namespace 去重、排除 application 主 R package、对宿主本轮全部主 R `*.dex` 执行 `DexPackageRenamer`，沿用 temp module 的 base APK 路由；namespace 完全缺失时抛出含 dependency name 的 `JuggException`。
- 复制与序列化路径显式保留字段：`ProjectInfoSerializerInGradle` 的手工 `JsonGenerator` 白名单转换器与 `load()`、`BaseCompileContext.saveTempLibraries()`。

与方案的已知偏差：§6.1 给出的 `val rPackageName: String? = null` 会让生成 init script 在 Gradle 8.11.1 的 Kotlin DSL 编译期崩溃（`JvmDefaultParameterInjector` IR lowering 异常），因此改为主构造函数不带默认值、由 secondary constructor 承担默认值，对外契约不变，旧快照仍按 `null` 读取。

验证证据：

| 层级 | 用例 | 结果 |
|---|---|---|
| L1 | `RDexForSubmoduleCompilerTest`（多 namespace / 相同 namespace 去重 / application namespace 排除 / namespace 缺失失败 / feature APK 回归） | 4 通过 |
| L1 | `DependencyDiffResultTest`（symbol 优先 / Manifest 回退 / 两者缺失 / metadata 不触发依赖更新） | 21 通过 |
| L1 | `JuggProjectInfoSerializerAndroidTestTest`、`ProjectInfoSerializerInGradleAndroidTestTest`（旧快照兼容与往返） | 19 / 13 通过 |
| L2 | `JuggCompilerTest`（含 Issue #46 用例与 `testCompileResDir` 回归） | 19 通过 |
| L2 | `ReadProjectInfoGradle5CompatTest` / `6` / `7` / `9`（含 Gradle 8.11.1 用例）、`ReadProjectInfoScriptContentTest`、`GradleProjectInfoReaderAndroidTestTest` | 1 / 1 / 5 / 12 / 12 / 14 通过 |

失败证据：强化前的 `JuggCompilerTest.external AAR resource update generates library namespace R dex` 在 `JuggCompilerTest.kt:264` 断言外部 R.dex 未生成而失败；强化后（断言外部 namespace 下 `R$string.dex` 及其新增字段）在 `JuggCompilerTest.kt:270` 继续失败，实施后稳定通过。

产物证据：真实 demo 工程走完整 `JuggCompiler` 链路后，`android_demo_project/build/jugg/build/staging/classes/com/example/external/` 生成 14 个 `R*.dex`，其中 `R$string.dex` 同时包含 `Lcom/example/external/R$string;` 与字段名 `external_new_title`。

未完成项：§10.4 要求的真机运行时替代验证未执行。阻碍是 demo 工程只有 `fileTree(dir: 'libs', include: ['*.jar'])`，不存在自带 namespace 且资源会发生变化的外部 AAR；复现需要额外构造两个 AAR 版本、刷新完整 Gradle 基线，并让本工作区构建的插件在 IDE 中接管运行。当前设备链路（`jugg status`）连接的不是本工作区插件产物，也没有完整构建基线（`hasBeenFullCompiled: false`），因此不做伪造结论。最接近的证据是上述真实编译链路的产物与字节码断言。
