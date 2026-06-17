# 编译系统：Manifest 与混淆映射

> 最后核对：2026-05-23
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页只覆盖两个容易误判的编译子链路：

- AndroidManifest 的增量合并：从变更 manifest 到 APK-scoped `AndroidManifest.xml` overlay。
- release/minified 场景的映射一致性：从未混淆 class/dex 到与已安装 APK 保持一致的混淆产物，以及 `_jugg_fix` 桥接类生成。

资源 flat/link 细节见 `02_compile_resource.md`；源码到 dex 的顺序见 `02_compile_source.md`；release runtime 异常排查见 `09_plugin_runtime_debug.md`。

---

## 2. 核心源码索引

| 类/接口 | 文件 | 作用 |
|---|---|---|
| `AndroidManifestCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/manifest/AndroidManifestCompiler.kt` | Manifest 编译入口；按 APK 归属读取基准 merged manifest，补 placeholder 后产出部署用 manifest overlay |
| `AndroidManifestMerger` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/manifest/AndroidManifestMerger.kt` | 在已合并 manifest 上套用变更 diff；不是重新跑标准 ManifestMerger2 |
| `ManifestDiffer` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/manifest/ManifestDiffer.kt` | 比较原 manifest 与变更 manifest，生成需要 patch 到 merged manifest 的节点/属性 |
| `ManifestNodeMatcher` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/manifest/ManifestDiffer.kt` | 在 merged manifest 子树里找相对节点，决定新增节点或递归更新 |
| `ClassMinifyCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/obfuscation/ClassMinifyCompiler.kt` | class 级 mapping 重写；无 mapping 时复制原 class |
| `DexMinifyCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/obfuscation/DexMinifyCompiler.kt` | dex 级 mapping 重写、inline 影响信息读取、`_jugg_fix` DEX 生成与 `usage.txt` compatibility stub 改写 |
| `ClassObfuscator` / `DexObfuscator` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/obfuscation/` | 执行 class/dex 名称、字段、方法与内部引用重映射 |
| `R8MappingReader` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/obfuscation/R8MappingReader.kt` | 读取 `mapping.txt` 并提供类/方法/字段映射查询 |
| `R8UsageReader` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/obfuscation/R8UsageReader.kt` | 读取 `usage.txt`，记录 R8 已删除的类、方法和字段 |

---

## 3. 核心数据流

| 数据 | 生产者 | 消费者 | 关键约束 |
|---|---|---|---|
| 基准 merged manifest | Gradle 上次构建产物，或 Jugg 上轮写入 `tempModule/res/AndroidManifest.xml` | `AndroidManifestCompiler` | Jugg 在最终 merged manifest 上 patch，避免 raw manifest 丢失 variant merge 结果 |
| `ChangedManifestFile` | `AndroidManifestCompiler` | `ManifestDiffer` | application module 会补内置 `applicationId` placeholder；存在 namespace 时补 `JUGG_NAMESPACE_IN_GRADLE` |
| Manifest diff element | `ManifestDiffer` | `AndroidManifestMerger` | `tools:*` 属性、manifest `package`、application `android:name` 不会被直接覆盖 |
| `mapping.txt` | 已安装 APK / 增量数据目录 | `ClassMinifyCompiler`, `DexMinifyCompiler` | `context.isMinified` 仍以 mapping 是否存在作为主要判断；release 缺失 mapping 只告警并继续 |
| `usage.txt` | R8/ProGuard 输出 | `DexMinifyCompiler` | 只增强 `_jugg_fix` 输入 class 的兼容改写；缺失或解析失败时退化为不裁剪 deleted method |
| `MinifyInfo` | 部署数据/影响分析链路 | `DexMinifyCompiler` | 用于识别 inline 受影响类与 `_jugg_fix` 原始 class 输入 |

---

## 4. 核心调用链路

### 4.1 Manifest 增量合并

```text
ResourceOverlayCompiler.doApkCompile()
  -> 按 APK scoped 任务调用 AndroidManifestCompiler.doApkCompile()
  -> 选择基准 manifest：优先上轮 Jugg merged manifest，否则用 application module merged manifest
  -> 为变更 manifest 补 applicationId / namespace placeholder，并为 library manifest 找上次构建相对 manifest
  -> ManifestDiffer.diff() 只提取真实新增/变更节点
  -> AndroidManifestMerger.merge() 将 diff patch 到基准 merged manifest
  -> 成功后写回 tempModule/res/AndroidManifest.xml，并输出 apkPath 绑定的 CompileOutput.Type.Res
```

这条链路的关键点是“patch merged manifest”，不是重新跑完整 Gradle manifest merge。原因写在 `AndroidManifestMerger` 注释里：标准 merge 在增量现场拿不到完整 placeholder、variant manifest 和最终 merged 结果上下文。

### 4.2 dex 混淆与 `_jugg_fix`

```text
SourceCompiler.compileDexOutputs()
  -> DexCompiler 生成未混淆 dex；minified 场景输出到 temp/un_minify
  -> DexMinifyCompiler.initIfNeeded() 加载 mapping.txt，按需加载 usage.txt
  -> preObfuscateForMinifyInfo() 先把 dex 临时混淆，供 getMinifyInfo() 按已安装 APK 的混淆类名查 DB
  -> context.getMinifyInfo() 返回 inline 受影响类与原始 class 文件
  -> generateJuggFixClasses() 将原始 class 经 usage.txt stub 改写、D8、obfuscate、renameDexClassDeclaration
  -> 普通增量 dex 再执行 obfuscateWithInlineRedirect() 或 obfuscate()
  -> 输出与 APK mapping 一致的 dex / `_jugg_fix` dex
```

`_jugg_fix` 采用“先完全混淆，再只改类声明名”的桥接策略：声明名带 `_jugg_fix` 后缀，内部调用仍指向原混淆类，避免把桥接类变成一套脱离 APK mapping 的新实现。

---

## 5. 隐形约束 / 设计思路 / 已知边界

- Manifest 输出为空是有效结果：library manifest 未变更、diff 后无变化时不会输出 `AndroidManifest.xml`，避免触发无意义 APK repackage。
- `AndroidManifestCompiler` 会把成功合并结果复制回 `tempModule/res/AndroidManifest.xml`；后续 manifest 增量以这个文件优先作为基准，不能只看 Gradle merged manifest。
- `ModuleBuildPathInfo.mergedManifest` 会在 `merged_manifests` / `merged_manifest` 候选里优先选取最新的 `AndroidManifest.xml`，避免 AGP 升级后旧目录产物遮蔽新目录产物。
- library manifest 会先和 `oldManifest` 做 CRC 比较；未变化直接跳过，避免对依赖库 manifest 做重复 patch。
- Manifest merge 会忽略 `tools:*` 属性、manifest `package` 和 application `android:name` 更新；这不是漏合并，而是为了避免增量 patch 覆盖运行时关键身份。
- release 缺 mapping 不会硬失败：`ClassMinifyCompiler` / `DexMinifyCompiler` 只 warn 并 wrap 原任务结果。排查 release 异常时要先确认日志是否出现 mapping 缺失告警。
- `usage.txt` 只参与 `_jugg_fix` 输入 class 的方法体兼容改写：已删除方法保留签名但改为空实现/默认返回；字段删除目前由 reader 记录，当前链路主要消费 removed methods。
- `preObfuscateForMinifyInfo()` 是为了让 DB 查询使用 APK 里的混淆类名；若跳过这一步，容易误判“类在 DB 中缺失”。

---

## 6. 排查入口

| 现象 | 优先入口 |
|---|---|
| Manifest 修改后未生效 | `AndroidManifestCompiler.doApkCompile()`：确认是否被 CRC、empty diff 或 `filterResources` 过滤 |
| manifest 合并结果覆盖了不该覆盖的字段 | `AndroidManifestMerger.merge()`：检查 `tools:*`、`package`、`android:name` 的忽略规则 |
| aapt2 link 后触发不必要重打包 | `ResourceOverlayCompiler.filterResources(...)`：确认无 manifest 变更时是否仍输出根 `AndroidManifest.xml` |
| release 增量后类名/方法名不匹配 | `DexMinifyCompiler.initIfNeeded()` 与 `DexObfuscator`：先确认 mapping 加载成功 |
| release 增量后 `NoClassDefFoundError` / `NoSuchMethodError` | `09_plugin_runtime_debug.md` §4.4-§4.11；同时查 `R8UsageReader` 与 `DexMinifyCompiler.generateJuggFixClasses()` |
| `_jugg_fix` 类存在但运行时调用异常 | `generateJuggFixClasses()`：检查 D8 输出类名匹配、obfuscate 后路径、`renameDexClassDeclaration()` |
| minify 删除成员影响分析异常 | `03_deploy_data_generator.md` §5.6：检查 `effectedType=MINIFY_MEMBER_REMOVED` |

---

## 7. 关联文档

- 资源编译：`02_compile_resource.md`
- 源码编译：`02_compile_source.md`
- 编译核心调度：`02_compile_core.md`
- 影响分析与 minify 类型：`03_deploy_data_generator.md`
- release runtime 排查：`09_plugin_runtime_debug.md`
