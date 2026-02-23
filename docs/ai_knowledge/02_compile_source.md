# 编译系统：源码编译链（Java/Kotlin/Dex）

> 最后核对：2026-02-23  
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页覆盖源码到 DEX 的主链路，以及 Kotlin 编译相关兼容处理。

---

## 2. 关键类

| 类 | 文件 | 作用 |
|----|------|------|
| `SourceCompiler` | `main/.../compiler/source/SourceCompiler.kt` | 模块内协调 Kotlin/Java/DataBinding 生成类，并接续 dex |
| `KotlinCompiler` | `main/.../compiler/source/kotlin/KotlinCompiler.kt` | Kotlin 源码编译 |
| `KotlinCompilerInvoker` | `main/.../compiler/source/kotlin/KotlinCompilerInvoker.kt` | Kotlin CLI 参数、插件、错误重试 |
| `K2JVMCompilerIsolate` | `main/.../compiler/source/kotlin/K2JVMCompilerIsolate.kt` | Kotlin 编译器隔离加载 |
| `JavaCompiler` / `JavaCompilerInvoker` | `main/.../compiler/source/JavaCompiler*.kt` | Java 编译 |
| `DexCompiler` / `DexFileMaker` / `DexFileMerger` | `main/.../compiler/source/` | class -> dex 与合并 |
| `DexMinifyCompiler` | `main/.../compiler/obfuscation/DexMinifyCompiler.kt` | 需要时对 dex 做映射一致性处理 |

---

## 3. 核心执行顺序

1. `SourceCompiler` 先处理 DataBinding mapper 生成（如启用）。  
2. Kotlin 先编译（便于 Java 依赖 Kotlin 产物）。  
3. Java 再编译（含 Kotlin/KAPT 产生的 Java 源）。  
4. class 产物进入 `DexCompiler`。  
5. 若处于 minified 场景，再走 `DexMinifyCompiler`。

---

## 4. Kotlin 相关要点

- `KotlinCompilerInvoker` 负责参数组装、插件参数、失败重试。  
- `K2JVMCompilerIsolate` 负责隔离加载编译器 classpath。  
- Kotlin 元数据问题由 `KotlinCompilerOutputParser` + `KmModuleMergerForCompilation` 辅助处理。
- 当 `KotlinCompilerInvoker.Options.isEnableKapt=true` 时，`KotlinCompilerOutputParser` 会将编译器 `warning/error` 文本按 `debug` 记录（仍保留错误解析与失败判定）。

---

## 5. 输入输出约定

- 输入：`CompileFile.Type.Java` / `Kotlin` / `Class`。  
- 中间产物：`Class`、部分 generated Java/Kotlin。  
- 输出：`CompileOutput.Type.Dex`（及少量非 class 附属产物）。

---

## 6. 常见问题定位

- Kotlin 元数据不兼容：`KotlinCompilerOutputParser`。  
- classpath 缺失：`K2JVMCompilerIsolate.checkClasspath` 与 `ModuleBuildPathInfo`。  
- dex 合并异常：`DexFileMerger` 与 `IncrementalCompilerHelper.mergeDex`。
- APT/KAPT 日志级别噪音：`JavaCompilerInvoker` 与 `KotlinCompilerOutputParser`（启用 APT/KAPT 时编译器输出默认降级为 `debug`）。

---

## 7. 关联文档

- 核心调度：`02_compile_core.md`
- DataBinding：`02_compile_databinding.md`
- 混淆映射：`02_compile_manifest_obfuscation.md`
