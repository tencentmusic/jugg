# 编译系统：资源编译链（res/assets/arsc）

> 最后核对：2026-02-23  
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页描述资源相关编译：res/assets/manifest 到可部署 overlay 的主链路。

---

## 2. 关键类

| 类 | 文件 | 作用 |
|----|------|------|
| `ResourceOverlayCompiler` | `main/.../compiler/overlay/ResourceOverlayCompiler.kt` | 资源主协调器（res + manifest） |
| `ResourceCompiler` | `main/.../compiler/overlay/ResourceCompiler.kt` | 资源编译到 flat，并处理 binding 相关产物 |
| `ArscCompiler` | `main/.../compiler/overlay/ArscCompiler.kt` | aapt2 link 产出 `resources.arsc` 与相关输出 |
| `AssetOverlayCompiler` | `main/.../compiler/overlay/AssetOverlayCompiler.kt` | assets/native lib 变更处理 |
| `AndroidManifestCompiler` | `main/.../compiler/manifest/AndroidManifestCompiler.kt` | 变更 manifest 合并输出 |
| `RJavaFixer` / `RDexForSubmoduleCompiler` | `main/.../compiler/overlay/` | R 相关产物修正与子模块 R.dex |

---

## 3. 核心流程

1. `ResourceOverlayCompiler` 拆分 manifest 与 resource 任务。  
2. `AndroidManifestCompiler` 合并并输出变更清单。  
3. `ResourceCompiler` 将资源编译为 flat。  
4. `ArscCompiler` link 产出 overlay 资源与 arsc。  
5. 过滤无效或不应输出项（如未变更 manifest 场景）。

资源链路支持多 APK 归属部署：

- 资源、manifest、asset 仍按 APK 维度生成输出，因为不同 APK 的资源表、manifest 与 package 信息不能直接复用。
- `BaseCompiler.splitApkAndCompile()` 会把同一 module 的资源输入分发到 `getAllBelongsApk()` 返回的每个 APK，子编译器通过 `doApkCompile(task, apkFileUnit)` 生成 APK-scoped 输出。
- 下游部署优先读取 `targetApkPaths`，因此资源输出转换为 `DeployItem` 时也要保留 target 归属。

---

## 4. 与源码编译的衔接

- 资源流程会产生 `R.java` 与 binding 相关 generated 源。  
- `JuggCompiler` 将这些 generated 源转入 `SourceCompiler` 后续阶段。

---

## 5. 高风险点

- 多模块/多 APK 场景下路径与相对目录映射。  
- 首次全量资源推送耗时较高，行为由部署层处理。  
- manifest 无变更时误输出会触发不必要重打包，需关注过滤逻辑。

---

## 6. 常见排查入口

- aapt2 link 失败：`ArscCompiler` 日志输出。  
- 资源被错误过滤：`ResourceOverlayCompiler.filterResources(...)`。  
- R 相关引用异常：`RJavaFixer`, `RDexForSubmoduleCompiler`。

---

## 7. 关联文档

- Manifest/混淆：`02_compile_manifest_obfuscation.md`
- DataBinding：`02_compile_databinding.md`
- 部署：`03_deploy_core.md`
