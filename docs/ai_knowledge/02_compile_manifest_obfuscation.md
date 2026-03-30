# 编译系统：Manifest 与混淆映射

> 最后核对：2026-03-30  
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页覆盖两部分：
- AndroidManifest 增量合并
- release/minified 场景下的映射一致性处理

---

## 2. Manifest 相关关键类

| 类 | 文件 | 作用 |
|----|------|------|
| `AndroidManifestCompiler` | `main/.../compiler/manifest/AndroidManifestCompiler.kt` | 处理变更清单并产出合并结果 |
| `AndroidManifestMerger` | `main/.../compiler/manifest/AndroidManifestMerger.kt` | 执行合并逻辑 |
| `ManifestDiffer` | `main/.../compiler/manifest/ManifestDiffer.kt` | 差异分析与节点匹配 |

---

## 3. 混淆相关关键类

| 类 | 文件 | 作用 |
|----|------|------|
| `ClassMinifyCompiler` | `main/.../compiler/obfuscation/ClassMinifyCompiler.kt` | class 级别映射一致性处理 |
| `DexMinifyCompiler` | `main/.../compiler/obfuscation/DexMinifyCompiler.kt` | dex 级别映射一致性处理 |
| `ClassObfuscator` / `DexObfuscator` | `main/.../compiler/obfuscation/` | 具体重映射实现 |
| `R8MappingReader` | `main/.../compiler/obfuscation/R8MappingReader.kt` | mapping 读取与查询 |

---

## 4. 执行要点

- Manifest 仅在有变更时输出，减少无效重打包。  
- 混淆处理依赖 `mapping.txt` 是否可用。  
- release 且 mapping 缺失时，系统会给出告警并按可用路径继续。

---

## 5. 常见问题定位

- "manifest 合并后行为异常"：看 `ManifestDiffer` 节点匹配与 placeholder 替换。  
- "release 增量后类找不到"：看 `ClassMinifyCompiler` 是否正确加载 mapping。  
- "dex 混淆后崩溃"：看 `DexMinifyCompiler` 的输入输出与映射完整性。
- "release 增量后运行时注解找不到"：检查 `DexObfuscator` 是否正确处理了方法级/字段级注解的类型描述符映射。注解类型描述符（`visitAnnotation` 的 `name` 参数）需要通过 `mapType()` 映射。详见 `09_plugin_runtime_debug.md` §4.4。
- "release 增量后 NoClassDefFoundError"：检查 `DexObfuscator` 的 `DexCodeVisitor` 是否覆写了所有含类型引用的方法（`visitConstStmt`、`visitFilledNewArrayStmt`、`visitTryCatch` 等）。详见 `09_plugin_runtime_debug.md` §4.5。
- "release 增量后 NoClassDefFoundError / NoSuchMethodError（minify 移除成员）"：检查 `getEffectedClassNodesForMinify` 是否正确检测到被 R8 移除的类/成员，确认 `effectedType` 为 `MINIFY_MEMBER_REMOVED`（非 `SOURCE` 或 `INLINE_IMPL_CHANGE`）。详见 `03_deploy_data_generator.md` §5.6。
- "release 增量后 IllegalAccessError / AbstractMethodError"：检查 `DexObfuscator` 是否正确执行了 access flags 宽化（`widenAccessFlags()`）及 `invoke-direct` → `invoke-virtual` 调用指令同步修改。详见 `09_plugin_runtime_debug.md` §4.6。
- "release 增量后 IncompatibleClassChangeError"：增量 DEX 中方法的 direct/virtual 分类与 APK 不一致。方案 E' 通过宽化 + 改指令同步解决。**注意**：`invoke-direct` 和 `invoke-direct/range`（`INVOKE_DIRECT_RANGE`）均需处理，遗漏 range 变体在高寄存器场景仍会 crash。详见 `09_plugin_runtime_debug.md` §4.7。
- "release 增量后 AbstractMethodError（类不在 mapping 中）"：新增类/类名变更/匿名类编号漂移时，类自身无 mapping 条目，方法名保留原名导致与 APK 中接口方法名不一致。方案 L 通过"接口/父类优先"的方法名映射策略解决：`DexObfuscator.mapMethodForCurrentClass()` 在 `visitMethod()` 中先从接口/父类 mapping 推导方法名，未命中再查类自身。详见 `docs/task/release_incremental_access_flag_mismatch.md` §12。
- "release 增量后 NoSuchMethodError（Kotlin stdlib 方法调用）"：R8 synthesized 方法在 facade 类（如 `CollectionsKt`）中使用 qualified 原始名（如 `xxx.CollectionsKt.listOf`），导致 `methodNameMap` 的 key 构建不一致。修复：`DexObfuscator.init{}` 中使用 `method.originalName.substringAfterLast('.')` 提取简单方法名构建 key。详见 `09_plugin_runtime_debug.md` §4.9。
- "release 增量后 NoSuchMethodError（带对象类型参数的 synthesized 方法）"：R8 synthesized 方法的 mapping 条目中参数类型使用"中间格式"（混淆包名 + 原始简单类名，如 `xxx.ClosedRange`），与 DEX 中的原始名（`kotlin.ranges.ClosedRange`）不匹配。修复：`DexObfuscator.init{}` 中构建 `intermediateToOriginal` 辅助映射，通过 `normalizeMethodParams()` 将参数类型规范化为原始名。详见 `09_plugin_runtime_debug.md` §4.10。
- "release 增量后 NoSuchMethodError（synthesized 条目覆盖正常方法映射）"：R8 mapping 中同一方法可能同时有正常条目（`d → a`）和 synthesized 条目（`d → d`），后者覆盖前者导致方法名未被映射。修复：`DexObfuscator.init{}` 中优先保留"真正重命名"的条目，不允许恒等映射覆盖。详见 `09_plugin_runtime_debug.md` §4.11。

---

## 6. 关联文档

- 资源编译：`02_compile_resource.md`
- 源码编译：`02_compile_source.md`
- 部署：`03_deploy_core.md`
