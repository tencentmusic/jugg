# 修复 3: getMinifyInfo 传入未混淆类名导致大量 missing classes

## 背景

上一个 commit 将 `getMinifyInfo` 的数据源从 `stateTracker.getStagingFiles()` 改为 `task.files`，解决了 staging 区不包含当前编译轮次 dex 的问题。但引入了新问题：

`DexMinifyCompiler.process()` 中的 `task.files` 是 DexCompiler 产出的**未混淆 dex 文件**（原始类名如 `com/example/MyClass.dex`），而 `getMinifyInfo` 内部会：
1. 将 dex 文件 `toDeployItem()` → `ApkParser.parseDex()` 读出类名
2. 用类名去 DB（存的是 APK 中**混淆后**的类名如 `La/b;`）查询

原始类名查不到混淆 DB → 误判为 "completely removed class" → 产生大量 missing classes。

## 流程分析

### 编译阶段时序

```
SourceCompiler.compileDexOutputs():
  1. Java/Kotlin → .class (原始类名)
  2. DexCompiler → .dex (原始类名, 输出到 un_minify/ 临时目录)
  3. DexMinifyCompiler ← task.files (未混淆的 dex)
     ├── process() 调 context.getMinifyInfo(task.files) ← 问题点
     └── obfuscateDexFile() 对 dex 内容做混淆
```

### 旧逻辑（staging files）为何正确

staging 区存的是**上一轮编译**的最终产物，已经经过 `DexMinifyCompiler` 混淆，所以 parseDex 读出的是混淆后的类名，能正确匹配 DB。

### task.files 为何不正确

task.files 是 DexCompiler 的直接产出，还未经过混淆，所以 parseDex 读出的是原始类名，无法匹配 DB。

## 方案对比

| 方案 | 描述 | 评价 |
|------|------|------|
| A: 先混淆后查 | DexMinifyCompiler 先做普通混淆，再用混淆结果查 getMinifyInfo | ❌ 鸡生蛋：getMinifyInfo 结果需要用于 obfuscateWithInlineRedirect |
| B: 传入 obfuscator 做类名翻译 | 在 analyzer 层将原始类名翻译为混淆名 | ❌ parseDex 在 DeployDataGenerator 深处，改动大 |
| C: 回退 staging + fallback | 恢复旧逻辑 | ❌ 上个 commit 已分析 staging 时机问题 |
| **D: 在 DexMinifyCompiler 中预混淆 dex bytes** | 先对 dex 内容做纯混淆（不含 inline redirect），用混淆后的 bytes 构建 CompileOutput 传给 getMinifyInfo | ✅ 最精确，不改接口，只改 DexMinifyCompiler 内部逻辑 |

## 采用方案: D — DexMinifyCompiler 预混淆 dex bytes

### 核心思路

在 `DexMinifyCompiler.process()` 中：
1. 先对 task.files 的 dex bytes 做**纯混淆**（`obfuscator.obfuscate(bytes)`，不含 inline redirect）
2. 把混淆后的 bytes 写入临时文件，组装为 `CompileFile` 列表
3. 用这些混淆后的 `CompileFile` 调 `context.getMinifyInfo(obfuscatedFiles)`
4. 用返回的 `minifyInfo` 对原始 task.files 做**完整混淆**（`obfuscateWithInlineRedirect`）

### 改动范围

| 文件 | 改动 |
|------|------|
| `DexMinifyCompiler.kt` L91-96 | `process()` 方法中，在调 getMinifyInfo 前先预混淆 task.files |

### 关键代码变化

```kotlin
// Before (broken):
val minifyInfo = context.getMinifyInfo(task.files)

// After (fixed):
val obfuscatedCompileFiles = preObfuscateForMinifyInfo(task)
val minifyInfo = context.getMinifyInfo(obfuscatedCompileFiles)
```

新增 `preObfuscateForMinifyInfo` 方法：
- 遍历 task.files，对每个 dex 调 `obfuscator.obfuscate(bytes)` 
- 混淆后的 bytes 写入临时目录
- 组装为新的 CompileFile 列表返回

### 优势

1. 只改 `DexMinifyCompiler` 一个文件，不影响接口签名
2. 混淆结果精确，使用的是已加载的 obfuscator（和后续混淆一致）
3. 没有额外的 mapping 读取开销（obfuscator 已初始化）

## 执行状态

### 已完成

#### 1. TDD 测试
- 文件: `main/src/test/java/com/sickworm/intellij/jugg/compiler/obfuscation/DexMinifyCompilerPreObfuscateTest.kt`
- 验证: `getMinifyInfo` 接收到的 dex 文件内容包含混淆后的类名，而非原始类名
- Mockito `ArgumentCaptor` 捕获 `getMinifyInfo` 参数，解析 dex bytes 验证类名

#### 2. 业务代码修复
- 文件: `DexMinifyCompiler.kt`
- `process()` 方法: `context.getMinifyInfo(task.files)` → `context.getMinifyInfo(preObfuscateForMinifyInfo(task))`
- 新增 `preObfuscateForMinifyInfo(task)` 方法:
  - 遍历 task.files 中的 Dex 类型文件
  - 对每个 dex 调 `obfuscator.obfuscate(bytes)` 做纯混淆（不含 inline redirect）
  - 混淆后的 bytes 写入 `tempCompileDir/pre_obfuscate_for_minify/` 临时目录
  - 用 `compileFile.copy(file=tempFile, baseDir=preObfuscateDir)` 组装新 CompileFile
  - 无 mapping 的文件原样返回

#### 3. 测试结果
- `DexMinifyCompilerPreObfuscateTest`: ✅ PASSED
- `DexObfuscatorTest` + `ClassObfuscatorTest` + `R8MappingReaderTest` + `R8MappingTest`: ✅ 无回归
- `GetMinifyInfoSignatureTest` + `CompileEffectAnalyzerMinifyFilterTest`: ✅ 无回归
