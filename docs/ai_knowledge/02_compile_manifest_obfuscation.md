# 编译系统：Manifest 与混淆映射

> 最后核对：2026-04-01  
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
| `DexMinifyCompiler` | `main/.../compiler/obfuscation/DexMinifyCompiler.kt` | dex 级别映射一致性处理；按 `usage.txt` 将已删方法重写为 compatibility stub |
| `ClassObfuscator` / `DexObfuscator` | `main/.../compiler/obfuscation/` | 具体重映射实现 |
| `R8MappingReader` | `main/.../compiler/obfuscation/R8MappingReader.kt` | `mapping.txt` 读取与查询 |
| `R8UsageReader` | `main/.../compiler/obfuscation/R8UsageReader.kt` | `usage.txt` 读取与查询（整类删除、方法删除、字段删除） |

---

## 4. 执行要点

- Manifest 仅在有变更时输出，减少无效重打包。  
- 混淆处理依赖 `mapping.txt` 是否可用；`isMinified` 仍只由 `mapping.txt` 存在与否判定。  
- `usage.txt` 是 release/minify 场景的增强输入：文件存在时在 `DexMinifyCompiler.initIfNeeded()` 中按需加载，缺失或解析失败时退化为“不改写 `_jugg_fix` 已删方法”。  
- `mapping.txt` 负责类/方法/字段重映射；`usage.txt` 负责描述最终 APK 中哪些类/成员已被 R8 删除。  
- `_jugg_fix` 生成链路会先对原始 `.class` 做基于 `usage.txt` 的 ASM compatibility stub 重写：保留已删方法的签名，但方法体改为默认返回/空实现，再继续执行 `D8 -> obfuscate() -> renameDexClassDeclaration()`。  
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
- "release 增量后 NoClassDefFoundError（_jugg_fix 类中的内部类引用）"：`DexMinifyCompiler.generateJuggFixClasses()` 生成的 `_jugg_fix` DEX 包含对原始内部类（如 `LogUtil$1`）的引用，但 APK 中该内部类已被 R8 重命名。修复：在输出前对 `_jugg_fix` DEX 调用 `obfuscator.obfuscate()` 映射内部引用。`_jugg_fix` 类名本身不在 mapping 中所以不受影响。详见 `docs/task/jugg_fix_inner_class_not_obfuscated.md`。
- "release 增量后 NoSuchMethodError（_jugg_fix 类方法名未混淆）"：`_jugg_fix` DEX 的方法名/字段名未被混淆（如保持原始名 `d`），而增量 DEX 调用方使用混淆名（如 `a`），导致方法签名不匹配。修复：采用方案 A（obfuscate-then-rename），`generateJuggFixClasses()` 流程改为"D8 → `obfuscate()` → `renameDexClassDeclaration()`"。`renameDexClassDeclaration()` 仅重命名类声明（类名、方法声明 owner、字段声明 owner），不修改代码体内的方法调用和字段引用 owner，使 `_jugg_fix` 成为桥接类：声明名带后缀，内部调用仍指向原始混淆类。同时 `redirectClassMap` 的 redirect 目标改为"混淆后类名 + 后缀"（如 `a/b/c_jugg_fix`）。详见 `docs/task/jugg_fix_full_obfuscation_analysis.md`。
- "release 增量后 ClassNotFoundException（_jugg_fix 产物路径不对）"：`generateJuggFixClasses()` 使用 D8 原始文件名（如 `LogUtil.dex`）作为输出路径，但 DEX 内部类经 obfuscate + renameDexClassDeclaration 后已变为 `La/b/c_jugg_fix;`，导致文件名与 DEX 内容不匹配。同时引发 `checkMaybeMinifiedRemoveClass` 误识别和运行时 ClassNotFoundException。修复：输出文件名改为从实际混淆后的类名推算（如 `a/b/c_jugg_fix.dex`）。详见 `docs/task/jugg_fix_full_obfuscation_analysis.md` §6.4。
- "release 增量后 IllegalAccessError（_jugg_fix 写入原始类 final field）"：`_jugg_fix` 桥接类不应有 field 声明和 `<clinit>`。`<clinit>` 会写入原始类的 final static field（如 `LogUtil.a`），触发 `IllegalAccessError`。对 keep 类和混淆类都存在此风险。修复：`renameDexClassDeclaration()` 剥离所有 field 声明（`visitField` 返回 null）和 `<clinit>` 方法（`visitMethod` 对 `<clinit>` 返回 null）。详见 `docs/task/jugg_fix_full_obfuscation_analysis.md` §6.5。
- "release 增量后 NoSuchMethodError（`_jugg_fix` 缺少 usage 已删方法）"：调用点仍被 class-level redirect 到 `_jugg_fix`，但若在 class 阶段直接删除 usage 命中的方法声明，运行时会因找不到静态/实例方法而崩溃。修复：`DexMinifyCompiler` 不再删声明，而是对命中的普通方法生成 compatibility stub，保留签名并把方法体改成默认返回/空实现，避免继续访问 release 已删成员。详见 `docs/task/jugg_fix_usage_txt_deleted_method_plan.md`。

---

## 6. 关联文档

- 资源编译：`02_compile_resource.md`
- 源码编译：`02_compile_source.md`
- 部署：`03_deploy_core.md`
