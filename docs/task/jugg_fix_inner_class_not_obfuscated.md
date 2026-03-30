# 修复: _jugg_fix 类内部引用未混淆导致 NoClassDefFoundError

## 背景

`DexMinifyCompiler.generateJuggFixClasses()` 为受 minify 影响的类生成 `_jugg_fix` 副本。但生成的 DEX 直接 copy 到输出目录，**没有经过 `DexObfuscator` 混淆处理**。

导致 `_jugg_fix` 类内部引用的匿名内部类（如 `LogUtil$1`）、成员内部类、字段类型、方法参数类型等仍保留原始类名，在设备上无法找到（APK 中已被 R8 混淆为其他名称）。

### Crash 堆栈

```
java.lang.NoClassDefFoundError: Failed resolution of: Lcom/tencent/component/utils/LogUtil$1;
    at com.tencent.component.utils.LogUtil_jugg_fix.<clinit>(LogUtil.java:6)
Caused by: java.lang.ClassNotFoundException: Didn't find class "com.tencent.component.utils.LogUtil$1"
```

### 根因链路

1. `LogUtil` 被 minify 检测标记为受影响类
2. `generateJuggFixClasses()` 从 `LogUtil.class` 生成 `LogUtil_jugg_fix.class` → DEX
3. `_jugg_fix` DEX **直接 copy 到 output**（L302-304），未经 `obfuscator.obfuscate()`
4. `LogUtil_jugg_fix` 内部引用 `LogUtil$1`（原始名），设备上只有混淆后的 `La/b/c;`
5. 运行时 `NoClassDefFoundError`

## 修改点

### 1. `generateJuggFixClasses` 对 `_jugg_fix` DEX 应用混淆

- 文件: `DexMinifyCompiler.kt` L294-310
- 将 `_jugg_fix` DEX 直接 copy 改为先通过 `obfuscator.obfuscate()` 处理
- 混淆会将 `LogUtil$1` → `La/b/c;`（设备上的实际类名）
- 混淆不会改变 `LogUtil_jugg_fix` 本身（因为 mapping 中没有这个类名的条目）

#### Before (broken):
```kotlin
// _jugg_fix classes are not in original mapping, so copy DEX files directly
val outputFile = File(outputDir, dexFile.name)
dexFile.copyTo(outputFile, overwrite = true)
outputs.add(CompileOutput(CompileOutput.Type.Dex, outputFile, outputDir))
```

#### After (fixed):
```kotlin
val inputBytes = dexFile.readBytes()
val obfuscatedBytes = obfuscator.obfuscate(inputBytes)
val outputFile = File(outputDir, dexFile.name)
outputFile.parentFile?.mkdirs()
if (obfuscatedBytes != null) {
    outputFile.writeBytes(obfuscatedBytes)
} else {
    // No mapping entries found in this DEX, copy as-is
    dexFile.copyTo(outputFile, overwrite = true)
}
outputs.add(CompileOutput(CompileOutput.Type.Dex, outputFile, outputDir))
```

### 为什么 obfuscate() 足够而不需要 obfuscateWithInlineRedirect()

`_jugg_fix` DEX 本身就是重定向的目标。不需要再对其内部的类引用做 inline redirect。
只需要做普通混淆：将 `_jugg_fix` 类内部引用的原始类名映射为混淆后的名称即可。

### 为什么不会破坏 `_jugg_fix` 类本身

`LogUtil_jugg_fix` 这个类名在 `classNameMap` 中不存在（mapping.txt 中只有 `LogUtil` → 混淆名），
所以 `obfuscator.obfuscate()` 不会修改类名本身，只会修改其内部引用的其他类名。

## 影响范围

| 下游分支 | 影响 |
|---------|------|
| `_jugg_fix` DEX 内部类引用 | 从原始名映射为混淆名 → 修复 NoClassDefFoundError |
| `_jugg_fix` 类名本身 | 不变（mapping 中无对应条目） |
| `_jugg_fix` 类的方法/字段名 | mapping 基于 `LogUtil` 原始名查找，方法/字段名也会被正确映射 |
| 非 `_jugg_fix` 的增量 DEX | 不受影响（原有混淆逻辑不变） |

## 执行状态

- [x] TDD 测试编写完成（`DexMinifyCompilerJuggFixObfuscateTest.kt`）
- [x] 业务代码实现（`DexMinifyCompiler.kt` generateJuggFixClasses 中 obfuscate）
- [x] 测试全部通过（obfuscation 包下所有测试通过，deploy 包下 3 个预存失败与本改动无关）
- [x] ai_knowledge 文档同步（`02_compile_manifest_obfuscation.md` 常见问题定位新增条目）
