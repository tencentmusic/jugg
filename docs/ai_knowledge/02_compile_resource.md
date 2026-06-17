# 编译系统：资源编译链（res/assets/arsc）

> 最后核对：2026-05-23
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页覆盖资源相关增量编译：`res/`、`assets/`、native lib 和 manifest 如何变成可部署 overlay。它重点说明 APK-scoped 编译、aapt2 `inclink` 状态、DataBinding/ViewBinding 产物交接和资源过滤边界。

Manifest diff 见 `02_compile_manifest.md`；release 混淆见 `02_compile_obfuscation.md`；DataBinding 详细策略见 `02_compile_databinding.md`；部署如何消费资源 overlay 见 `03_deploy_core.md`。

---

## 2. 核心源码索引

| 类/接口 | 文件 | 作用 |
|---|---|---|
| `ResourceOverlayCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/ResourceOverlayCompiler.kt` | 资源主协调器；按 APK scoped 任务串联 manifest、flat compile、arsc link，并过滤最终 overlay |
| `ResourceCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/ResourceCompiler.kt` | 将资源文件或资源目录编译为 `.flat`；先处理 ViewBinding/DataBinding split XML 和生成源码 |
| `ArscCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/ArscCompiler.kt` | 使用 aapt2 `inclink` 载入当前 APK 资源表并 link 出 `resources.arsc`、compiled res、`R.java` |
| `AssetOverlayCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/AssetOverlayCompiler.kt` | 处理 `assets/` 和 native lib 等非 res overlay |
| `AndroidManifestCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/manifest/AndroidManifestCompiler.kt` | Manifest 增量合并，产物作为 `ArscCompiler` 输入 |
| `DataBindingGenBaseClassesCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/databinding/DataBindingGenBaseClassesCompiler.kt` | layout 资源进入 aapt2 前生成 ViewBinding/DataBinding 基础类与 split XML |
| `RJavaFixer` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/RJavaFixer.kt` | 修正 aapt2 生成的 `R.java`，供后续源码编译消费 |
| `StyleableFileGenerator` / `ResGuardMappingFileGenerator` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/` | 为 `inclink --load` 提供 styleable 与资源混淆映射输入 |

---

## 3. 核心数据流

| 数据 | 生产者 | 消费者 | 关键约束 |
|---|---|---|---|
| `CompileFile.Type.Resource` | 变更扫描 / 上游编译任务 | `ResourceOverlayCompiler` | 可能是单个文件，也可能是目录；目录会展开成真实资源文件集合 |
| split layout XML | `DataBindingGenBaseClassesCompiler` | `ResourceCompiler.aapt2Compile()` | 原 layout 会在 aapt2 compile 前被 split XML 替换 |
| generated Java/Kotlin | `ResourceCompiler` | `SourceCompiler` | ViewBinding/DataBinding 生成源码不部署，必须回流源码编译阶段 |
| `.flat` | `ResourceCompiler` | `ArscCompiler` | 只作为 link 输入，不直接部署 |
| latest res APK | `ArscCompiler.getResApk()` | aapt2 `inclink --load` | 若当前 APK 曾部署过 `resources.arsc`，会用已部署 arsc + manifest 组成临时 res APK，避免从原始 APK 旧资源表继续 link |
| `resources.arsc` / compiled res / manifest | `ArscCompiler` | 部署数据转换 | `CompileOutput.apkPath` 绑定当前 APK；多 APK 归属不能丢 |
| `targetApkPaths` | `CompileOutput` / 下游 deploy item | 部署分流 | class/dex 可归属多个 APK；资源/manifest 仍按 APK scoped 输出 |

---

## 4. 核心调用链路

```text
JuggCompiler 资源阶段
  -> ResourceOverlayCompiler.splitApkAndCompile()
  -> BaseCompiler 按 moduleBelongsApkMap 把同一 module 输入拆给每个归属 APK
  -> AndroidManifestCompiler.doApkCompile() 只在 manifest 有真实 diff 时输出 manifest overlay
  -> ResourceCompiler.doModuleCompile() 处理 layout split / generated source，再 aapt2 compile 为 .flat
  -> ArscCompiler.doApkCompile() 为当前 APK loadTable，再 inclink flat + 可选 manifest
  -> ResourceOverlayCompiler.filterResources() 删除不应部署的 aapt2 额外产物和无变更 manifest
  -> 输出资源 overlay，同时把 generated Java/Kotlin 留给 SourceCompiler
```

多 APK 场景下，资源链路不是“同一份输出复制到多个 APK”。`splitApkAndCompile()` 会为每个 `ApkFileUnit` 单独调用 `doApkCompile()`，因为每个 APK 的资源表、package id、manifest 和 dynamic feature 依赖关系都可能不同。

---

## 5. 隐形约束 / 设计思路 / 已知边界

- `ArscCompiler` 为每个 APK 缓存一个 `Aapt2DaemonInvoker`；invoker 死亡或 link 失败会 release，下一轮重新 `loadTable`。
- dynamic feature 编译依赖 base APK：base arsc 更新后，`ArscCompiler` 会把 base 本轮 flat 文件加入 feature 的 link 输入，以同步资源 ID。
- `getResApk()` 会优先使用已部署的 `resources.arsc` 和 manifest 组成临时资源 APK；只看原始 APK 会漏掉上轮 Jugg 资源增量。
- `ResourceOverlayCompiler.filterResources()` 会删除根 `Manifest.java`，并在 manifest 无真实变更时删除根 `AndroidManifest.xml`，避免触发 APK repackage。
- aapt2 可能为一个资源生成多个配置目录产物；如果额外产物对应的 override XML 已存在，过滤逻辑会移除该额外产物，避免覆盖用户显式资源。
- `ResourceCompiler` 对目录输入使用目录路径 MD5 建子输出目录，避免不同资源目录 flat 文件名冲突。
- DataBinding mapper 生成不在资源阶段完成；资源阶段只处理 base class / split XML，mapper 交给 `SourceCompiler` 在源码编译前处理。

---

## 6. 排查入口

| 现象 | 优先入口 |
|---|---|
| aapt2 compile 失败 | `ResourceCompiler.aapt2Compile()`：看 `compile --legacy` 命令与 flat 输出是否存在 |
| aapt2 link / arsc 失败 | `ArscCompiler.doApkCompile()` 和 `incLinkCompile()`：看 `loadTable`、`inclink` errorOutput、invoker 是否重建 |
| dynamic feature 资源 ID 异常 | `ArscCompiler.isBaseApkArscUpdate` / `baseApkUpdateFlatFiles`：确认 base 更新是否参与 feature link |
| 资源 overlay 输出到错误 APK | `BaseCompiler.splitApkAndCompile()` 与 `CompileOutput.apkPath`：确认 module 到 APK 的归属和输出 apkPath |
| manifest 无变更却触发重打包 | `ResourceOverlayCompiler.filterResources(...)`：确认 `isNeedOutputManifest=false` 时根 manifest 是否被过滤 |
| layout 相关 generated source 未参与源码编译 | `ResourceCompiler.processViewBinding()` 和 `SourceCompiler.prepareSourceCompile()` |
| R 引用异常 | `ArscCompiler.incLinkCompile()` 生成的 `R.java` 与 `RJavaFixer.fixIfNeeded()` |

---

## 7. 关联文档

- Manifest 增量合并：`02_compile_manifest.md`
- 混淆映射：`02_compile_obfuscation.md`
- 源码编译：`02_compile_source.md`
- DataBinding：`02_compile_databinding.md`
- 部署核心：`03_deploy_core.md`
