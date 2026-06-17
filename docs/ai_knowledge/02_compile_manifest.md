# 编译系统：Manifest 增量合并

> 最后核对：2026-06-17
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页只覆盖 AndroidManifest 的增量合并：从变更 manifest 到 APK-scoped `AndroidManifest.xml` overlay。

资源 flat/link 细节见 `02_compile_resource.md`；源码到 dex 的顺序见 `02_compile_source.md`；release 混淆映射见 `02_compile_obfuscation.md`。

---

## 2. 核心源码索引

| 类/接口 | 文件 | 作用 |
|---|---|---|
| `AndroidManifestCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/manifest/AndroidManifestCompiler.kt` | Manifest 编译入口；按 APK 归属读取基准 merged manifest，补 placeholder 后产出部署用 manifest overlay |
| `AndroidManifestMerger` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/manifest/AndroidManifestMerger.kt` | 在已合并 manifest 上套用变更 diff；不是重新跑标准 ManifestMerger2 |
| `ManifestDiffer` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/manifest/ManifestDiffer.kt` | 比较原 manifest 与变更 manifest，生成需要 patch 到 merged manifest 的节点/属性 |
| `ManifestNodeMatcher` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/manifest/ManifestDiffer.kt` | 在 merged manifest 子树里找相对节点，决定新增节点或递归更新 |

---

## 3. 核心数据流

| 数据 | 生产者 | 消费者 | 关键约束 |
|---|---|---|---|
| 基准 merged manifest | Gradle 上次构建产物，或 Jugg 上轮写入 `tempModule/res/AndroidManifest.xml` | `AndroidManifestCompiler` | Jugg 在最终 merged manifest 上 patch，避免 raw manifest 丢失 variant merge 结果 |
| `ChangedManifestFile` | `AndroidManifestCompiler` | `ManifestDiffer` | application module 会补内置 `applicationId` placeholder；存在 namespace 时补 `JUGG_NAMESPACE_IN_GRADLE` |
| Manifest diff element | `ManifestDiffer` | `AndroidManifestMerger` | `tools:*` 属性、manifest `package`、application `android:name` 不会被直接覆盖 |

---

## 4. 核心调用链路

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

---

## 5. 隐形约束 / 设计思路 / 已知边界

- Manifest 输出为空是有效结果：library manifest 未变更、diff 后无变化时不会输出 `AndroidManifest.xml`，避免触发无意义 APK repackage。
- `AndroidManifestCompiler` 会把成功合并结果复制回 `tempModule/res/AndroidManifest.xml`；后续 manifest 增量以这个文件优先作为基准，不能只看 Gradle merged manifest。
- `ModuleBuildPathInfo.mergedManifest` 会在 `merged_manifests` / `merged_manifest` 候选里优先选取最新的 `AndroidManifest.xml`，避免 AGP 升级后旧目录产物遮蔽新目录产物。
- library manifest 会先和 `oldManifest` 做 CRC 比较；未变化直接跳过，避免对依赖库 manifest 做重复 patch。
- Manifest merge 会忽略 `tools:*` 属性、manifest `package` 和 application `android:name` 更新；这不是漏合并，而是为了避免增量 patch 覆盖运行时关键身份。

---

## 6. 排查入口

| 现象 | 优先入口 |
|---|---|
| Manifest 修改后未生效 | `AndroidManifestCompiler.doApkCompile()`：确认是否被 CRC、empty diff 或 `filterResources` 过滤 |
| manifest 合并结果覆盖了不该覆盖的字段 | `AndroidManifestMerger.merge()`：检查 `tools:*`、`package`、`android:name` 的忽略规则 |
| aapt2 link 后触发不必要重打包 | `ResourceOverlayCompiler.filterResources(...)`：确认无 manifest 变更时是否仍输出根 `AndroidManifest.xml` |

---

## 7. 关联文档

- 资源编译：`02_compile_resource.md`
- 源码编译：`02_compile_source.md`
- 编译核心调度：`02_compile_core.md`
- 混淆映射：`02_compile_obfuscation.md`
