# 编译系统：Manifest 与混淆映射

> 最后核对：2026-02-23  
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

---

## 6. 关联文档

- 资源编译：`02_compile_resource.md`
- 源码编译：`02_compile_source.md`
- 部署：`03_deploy_core.md`
