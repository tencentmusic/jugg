# 编译系统：混淆映射

> 最后核对：2026-06-17
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页只覆盖 release/minified 场景的映射一致性：从未混淆 class/dex 到与已安装 APK 保持一致的混淆产物，以及 `_jugg_fix` 桥接类生成。

源码到 dex 的顺序见 `02_compile_source.md`；Manifest 增量合并见 `02_compile_manifest.md`；release runtime 异常排查见 `09_plugin_runtime_debug.md`。

---

## 2. 核心源码索引

| 类/接口 | 文件 | 作用 |
|---|---|---|
| `ClassMinifyCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/obfuscation/ClassMinifyCompiler.kt` | class 级 mapping 重写；无 mapping 时复制原 class |
| `DexMinifyCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/obfuscation/DexMinifyCompiler.kt` | dex 级 mapping 重写、inline 影响信息读取、`_jugg_fix` DEX 生成与 `usage.txt` compatibility stub 改写 |
| `ClassObfuscator` / `DexObfuscator` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/obfuscation/` | 执行 class/dex 名称、字段、方法与内部引用重映射 |
| `R8MappingReader` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/obfuscation/R8MappingReader.kt` | 读取 `mapping.txt` 并提供类/方法/字段映射查询 |
| `R8UsageReader` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/obfuscation/R8UsageReader.kt` | 读取 `usage.txt`，记录 R8 已删除的类、方法和字段 |

---

## 3. 核心数据流

| 数据 | 生产者 | 消费者 | 关键约束 |
|---|---|---|---|
| `mapping.txt` | 已安装 APK / 增量数据目录 | `ClassMinifyCompiler`, `DexMinifyCompiler` | `context.isMinified` 仍以 mapping 是否存在作为主要判断；release 缺失 mapping 只告警并继续 |
| `usage.txt` | R8/ProGuard 输出 | `DexMinifyCompiler` | 只增强 `_jugg_fix` 输入 class 的兼容改写；缺失或解析失败时退化为不裁剪 deleted method |
| `MinifyInfo` | 部署数据/影响分析链路 | `DexMinifyCompiler` | 用于识别 inline 受影响类与 `_jugg_fix` 原始 class 输入 |

---

## 4. 核心调用链路

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

- release 缺 mapping 不会硬失败：`ClassMinifyCompiler` / `DexMinifyCompiler` 只 warn 并 wrap 原任务结果。排查 release 异常时要先确认日志是否出现 mapping 缺失告警。
- `usage.txt` 只参与 `_jugg_fix` 输入 class 的方法体兼容改写：已删除方法保留签名但改为空实现/默认返回；字段删除目前由 reader 记录，当前链路主要消费 removed methods。
- 部分 R8 版本会在 `usage.txt` 中擦除 Kotlin property accessor 的参数信息。精确签名未命中时，只有 usage 与 class bytecode 中该方法名都唯一才按名称回退；任一侧存在 overload 就保持原方法，避免误裁剪同名成员。
- `preObfuscateForMinifyInfo()` 是为了让 DB 查询使用 APK 里的混淆类名；若跳过这一步，容易误判“类在 DB 中缺失”。

---

## 6. 排查入口

| 现象 | 优先入口 |
|---|---|
| release 增量后类名/方法名不匹配 | `DexMinifyCompiler.initIfNeeded()` 与 `DexObfuscator`：先确认 mapping 加载成功 |
| release 增量后 `NoClassDefFoundError` / `NoSuchMethodError` | `09_plugin_runtime_debug.md` §4.4-§4.11；同时查 `R8UsageReader` 与 `DexMinifyCompiler.generateJuggFixClasses()` |
| `_jugg_fix` 类存在但运行时调用异常 | `generateJuggFixClasses()`：检查 D8 输出类名匹配、obfuscate 后路径、`renameDexClassDeclaration()` |
| minify 删除成员影响分析异常 | `03_deploy_data_generator.md` §5.6：检查 `effectedType=MINIFY_MEMBER_REMOVED` |

---

## 7. 关联文档

- 源码编译：`02_compile_source.md`
- Manifest 增量合并：`02_compile_manifest.md`
- 影响分析与 minify 类型：`03_deploy_data_generator.md`
- release runtime 排查：`09_plugin_runtime_debug.md`
