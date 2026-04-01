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

### Manifest

- "manifest 合并后行为异常"：看 `ManifestDiffer` 节点匹配与 placeholder 替换。

### DexObfuscator 重映射

- "release 增量后类找不到 / 崩溃"：先确认 `DexMinifyCompiler` 是否正确加载了 mapping，再看 `DexObfuscator` 输入输出与映射完整性。
- **release 增量后各类 runtime crash**（注解不匹配 / NoClassDefFoundError / IllegalAccessError / AbstractMethodError / IncompatibleClassChangeError / NoSuchMethodError）：统一查 `09_plugin_runtime_debug.md` §4.4-§4.11，该手册按异常类型提供完整排查步骤和修复方案。

### minify 移除检测

- "release 增量后 NoClassDefFoundError / NoSuchMethodError（minify 移除成员）"：检查 `effectedType` 是否为 `MINIFY_MEMBER_REMOVED`。详见 `03_deploy_data_generator.md` §5.6。

### _jugg_fix 桥接类

- `_jugg_fix` 相关问题（内部类引用未混淆 / 方法名不匹配 / 产物路径错误 / `<clinit>` 非法写入 / usage 已删方法）：详见对应 task 文档 `jugg_fix_inner_class_not_obfuscated.md`、`jugg_fix_full_obfuscation_analysis.md`、`jugg_fix_usage_txt_deleted_method_plan.md`。

---

## 6. 关联文档

- 资源编译：`02_compile_resource.md`
- 源码编译：`02_compile_source.md`
- 部署：`03_deploy_core.md`
