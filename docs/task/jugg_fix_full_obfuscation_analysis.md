# _jugg_fix 完整修改链路分析与混淆缺陷

## 1. _jugg_fix 生成与使用的完整链路

### 1.1 生成链路（`DexMinifyCompiler.generateJuggFixClasses`）

```
原始 .class 文件 (LogUtil.class)
  │  方法名: d, e, f（原始名）
  │  引用: LogUtil$1, LogUtil$InnerInterface（原始名）
  │
  ▼ Step 1: renameClassWithSuffix (ASM Remapper)
LogUtil_jugg_fix.class
  │  类名: LogUtil_jugg_fix（仅精确匹配外部类名做 rename）
  │  方法名: d, e, f（不变）
  │  引用: LogUtil$1, LogUtil$InnerInterface（不变，ASM Remapper 只 map 精确匹配的类名）
  │  自引用: LogUtil → LogUtil_jugg_fix（被 Remapper 处理）
  │
  ▼ Step 2: DexFileMaker.dex (D8)
LogUtil_jugg_fix.dex
  │  同上，只是格式从 .class 变成 .dex
  │
  ▼ Step 3: obfuscator.obfuscate() (DexObfuscator)
LogUtil_jugg_fix.dex (混淆后)
  │  类名: LogUtil_jugg_fix（不变，classNameMap 中无此条目）
  │  方法名: d, e, f（❌ 不变！因为 mapMethodForCurrentClass 用 "LogUtil_jugg_fix.d(...)" 查 methodNameMap，无匹配）
  │  引用: LogUtil$1 → LogUtil$b（✅ mapType 正确映射了类名）
  │  方法调用: LogUtil$1.d() → LogUtil$b.d()（❌ 方法名查找用 "LogUtil$1.d(...)" 可以匹配，但实际看起来方法名也有问题...需进一步确认）
  │  字段名: 类似问题，"LogUtil_jugg_fix.fieldName" 在 fieldNameMap 中无匹配
```

### 1.2 使用链路（增量 DEX 经过 `obfuscateWithInlineRedirect`）

```
增量 .dex 文件
  │  调用: LogUtil.d(String, String)
  │
  ▼ obfuscateWithInlineRedirect (DexObfuscator with minifyInfo)
增量 .dex (混淆后)
  │  mapMethod 处理 LogUtil.d(String, String):
  │    1. mapType("LogUtil") → 查 redirectClassMap → "LogUtil_jugg_fix"
  │    2. methodNameMap key = "com...LogUtil.d(java.lang.String,java.lang.String)" → 找到 → "a"
  │    3. mapProto 映射参数/返回类型
  │    最终: LogUtil_jugg_fix.a(String, String)
```

### 1.3 运行时不匹配

| 调用方（增量 DEX） | 被调方（_jugg_fix DEX） | 匹配 |
|---|---|---|
| `LogUtil_jugg_fix.a(String,String)` | `LogUtil_jugg_fix.d(String,String)` | ❌ 方法名不匹配 → **NoSuchMethodError** |

## 2. 根因

### 2.1 _jugg_fix 类自身的方法名/字段名无法被混淆

`obfuscate()` 中 `mapMethodForCurrentClass()` 和 `mapMethod()` 用**当前类名**作为 key 的一部分去查 `methodNameMap`。

对于 `LogUtil_jugg_fix`：
- key = `"com.tencent.component.utils.LogUtil_jugg_fix.d(java.lang.String,java.lang.String)"`
- `methodNameMap` 中只有 `"com.tencent.component.utils.LogUtil.d(java.lang.String,java.lang.String)"` → `"a"`
- **查不到**，方法名保持原始值 `d`

对于增量 DEX 调用方：
- key = `"com.tencent.component.utils.LogUtil.d(java.lang.String,java.lang.String)"` → **查得到** → `"a"`
- 调用变成 `LogUtil_jugg_fix.a()`

两边方法名不一致，导致 `NoSuchMethodError`。

### 2.2 同样的问题存在于字段名

`mapField()` 用 `field.owner` 的原始名构建 key：`"$ownerDot.$fieldName"`。
`_jugg_fix` 类自身声明的字段，owner 是 `LogUtil_jugg_fix`，在 `fieldNameMap` 中无匹配。

## 3. 上一次 NoClassDefFoundError 与本次 NoSuchMethodError 的关系

| 错误 | 根因 | 上次修复状态 |
|---|---|---|
| `NoClassDefFoundError: LogUtil$1` | _jugg_fix DEX 未经 obfuscate()，类引用是原始名 | ✅ 已修复（加了 obfuscate()） |
| `NoSuchMethodError: d(String,String)V in LogUtil$b` | _jugg_fix 类自身方法名未被混淆，与调用方用混淆名不一致 | ❌ 当前问题 |

## 4. 可选修复方案

### 方案 A：先混淆再重命名类声明（obfuscate-then-rename）✅ 已选定并实现

**思路**：改变处理顺序为"原始 .class → D8 → obfuscate() → renameDexClassDeclaration()"。

**核心设计**：`_jugg_fix` 类是桥接/代理类，其内部方法调用仍指向**原始混淆类**（如 `La/b/c;`），而非自身（`La/b/c_jugg_fix;`）。当 ClassA 增量更新时，通过 `_jugg_fix` 的调用方仍能到达 ClassA 的新实现。

```
LogUtil.class (原始)
  ▼ Step 1: D8 转 DEX（保持原始名）
LogUtil.dex
  ▼ Step 2: obfuscate() — 类名是 LogUtil，能正确匹配 mapping
a/b/c.dex (完全混淆)
  │  类名: a.b.c, 方法: a/b, 字段: c
  │  内部引用: a.b.d (原 LogUtil$1)
  ▼ Step 3: renameDexClassDeclaration(La/b/c;, La/b/c_jugg_fix;)
a/b/c_jugg_fix.dex
  │  类声明: La/b/c_jugg_fix; （✅ 已重命名）
  │  方法声明 owner: La/b/c_jugg_fix; （✅ 已重命名）
  │  代码体内方法调用 owner: La/b/c; （✅ 保持原始，指向原类）
  │  字段引用 owner: La/b/c; （✅ 保持原始）
```

**配套修改**：
- `redirectClassMap` 的 redirect 目标改为 `classNameMap[originalInternal] ?: originalInternal` + `_jugg_fix`，确保增量 DEX 调用 `a/b/c_jugg_fix.a(...)` 而非 `LogUtil_jugg_fix.a(...)`
- `DexObfuscator.renameDexClassDeclaration()` 仅重命名类声明、方法声明 owner、字段声明 owner，不拦截代码体 visitor
- 删除旧的 ASM `renameClassWithSuffix()` 方法及相关 import

**结论**：✅ 已实现并测试通过。

### 方案 B：对 _jugg_fix DEX 做「按原始类名查找的混淆」（❌ 未采用，不满足桥接约束）

**思路**：在 `obfuscate()` 处理 `_jugg_fix` DEX 时，让方法名/字段名的查找 key 使用**去掉 `_jugg_fix` 后缀的类名**。

实现：
- 新增 `obfuscateJuggFix(dexBytes)` 方法或为 `obfuscate()` 添加参数
- 在 `mapMethodForCurrentClass()` 和 `mapField()` 中，如果当前类名以 `_jugg_fix` 结尾，则 strip 后缀后再查 mapping
- 同理 `mapMethod()` 中，如果 `method.owner` 以 `_jugg_fix` 结尾也 strip

**额外注意**：`_jugg_fix` 类的方法名需要被混淆（匹配调用方），但类名本身不能被混淆（`LogUtil_jugg_fix` 在 classNameMap 中无条目，这是正确的——它不应该被映射成 `a.b.c`）。

**结论**：✅ 可行，改动相对集中。

### 方案 C：在增量 DEX 的 redirect 中不做方法名混淆

**思路**：`obfuscateWithInlineRedirect` 时，对 redirect 到 `_jugg_fix` 的方法调用，保持原始方法名不做混淆。

**问题**：`mapMethod()` 是同时处理 owner 映射和 name 映射的。要在 redirect 场景跳过方法名混淆，需要能识别"这个方法调用的 owner 经过了 redirect"。`mapType()` 做了 redirect 但 `mapMethod()` 不知道。

**结论**：⚠️ 可行但侵入性大，需要修改 `mapMethod` 的返回信息或增加状态传递。

### 方案 D：_jugg_fix 不重命名方法名，让两边都保持原始名

**思路**：既然 `_jugg_fix` 类的方法名是原始的，那调用方（增量 DEX）在 redirect 到 `_jugg_fix` 时也保持原始方法名。

实现：`mapMethod()` 检测到 owner 被 redirect 后，方法名直接保持原始，不查 `methodNameMap`。

**注意**：这要求 `_jugg_fix` DEX 中的方法名也保持原始。但 `_jugg_fix` 类内部调用其他（非 `_jugg_fix`）类的方法仍需混淆。

**结论**：✅ 可行，需要在 `mapMethod` 中区分"redirect 场景"和"普通混淆场景"。

### 推荐方案

**方案 A**（先混淆再重命名类声明）已选定并实现。相比方案 B，方案 A 满足了核心设计约束：`_jugg_fix` 类内部调用仍指向原始混淆类，在 ClassA 增量更新时不会出现调用旧方法实现的问题。

## 5. 方案 B 详细设计（❌ 未采用，保留以供参考）

### 5.1 核心改动

在 `DexObfuscator` 中新增 `obfuscateAsOriginalClass(dexBytes, suffixToStrip)` 方法：
- 遍历 DEX 时，对每个类名，如果以 `suffixToStrip` 结尾，则在查 mapping 时 strip 掉后缀
- 类名本身不做映射（保持 `_jugg_fix`）
- 方法名、字段名按 strip 后的原始类名查 mapping
- 其余引用（其他类、方法参数类型等）正常映射

或者更简单：提供一个 `classNameAlias: Map<String, String>` 参数，key 是 `_jugg_fix` 类名，value 是原始类名。在 `mapMethodForCurrentClass`、`mapField` 中用 alias 替代 owner 查找 mapping。

### 5.2 流程变化

```
Step 3（修改后）: obfuscator.obfuscateAsOriginalClass(dexBytes, "_jugg_fix")
  │  类名: LogUtil_jugg_fix（不变，classNameMap 无此条目，且不做类名映射）
  │  方法名查找 key: strip 后缀 → "com...LogUtil.d(...)" → 匹配 → "a"
  │  方法名: d → a（✅ 被正确混淆）
  │  字段名: 同理被正确混淆
  │  引用: LogUtil$1 → LogUtil$b（✅ 与之前一样）
  │  方法调用引用: LogUtil$1.d() → LogUtil$b.a()（✅ 正确混淆）
```

### 5.3 注意事项

1. **类名不能被混淆**：`LogUtil_jugg_fix` 不在 classNameMap 中，所以 `mapType` 不会映射它。这是正确的。
2. **`_jugg_fix` 类内部调用自身方法**：方法调用中 owner 是 `LogUtil_jugg_fix`，`mapMethod` 也需要 strip 后缀再查。
3. **`_jugg_fix` 类内部调用其他类方法**：owner 不以 `_jugg_fix` 结尾，正常走原有逻辑。
4. **增量 DEX 调用方不受影响**：`obfuscateWithInlineRedirect` 中用原始类名查 mapping，方法名能正确映射为混淆名。

## 6. 方案 A 实现记录

### 6.1 实现日期

2026-03-30

### 6.2 修改文件

| 文件 | 修改内容 |
|------|---------|
| `DexObfuscator.kt` | 新增 `renameDexClassDeclaration()` 方法；修改 `redirectClassMap` 使用混淆后类名 + 后缀 |
| `DexMinifyCompiler.kt` | 重构 `generateJuggFixClasses()` 为 D8→obfuscate→rename 流程；删除 `renameClassWithSuffix()` 及 ASM imports |

### 6.3 测试文件

`main/src/test/java/com/sickworm/intellij/jugg/compiler/obfuscation/DexObfuscatorJuggFixFullObfuscationTest.kt`

10 个测试用例覆盖：
1. `renameDexClassDeclaration` 仅重命名类声明，不修改自引用
2. `renameDexClassDeclaration` 剥离字段声明，但保留代码体字段引用指向原始类
3. `renameDexClassDeclaration` 不影响对其他类的引用
4. 完整流水线（D8→obfuscate→rename）产出的 `_jugg_fix` 自引用指向原始混淆类
5. `obfuscateWithInlineRedirect` 的 redirect 目标为混淆类名 + 后缀
6. 无 mapping 条目的类使用原始名 + 后缀
7. 增量 DEX 调用签名与 `_jugg_fix` 声明方法签名一致性验证
8. `renameDexClassDeclaration` 剥离所有 field 声明（桥接类无自有 field）
9. `renameDexClassDeclaration` 剥离 `<clinit>` 方法（防止写入原始类 final field）
10. 完整流水线（keep 类场景）产出的 `_jugg_fix` 无 field、无 `<clinit>`、方法正确混淆

### 6.4 Bug Fix: _jugg_fix 产物输出路径修复（2026-03-30）

**问题现象**：
1. `checkMaybeMinifiedRemoveClass` 误识别 `_jugg_fix` 类为 removed class
2. 产物路径 `deployed/classes/LogUtil.dex`，名字和路径都不对（应为 `a/b/c_jugg_fix.dex`）
3. 运行时 `ClassNotFoundException: com.tencent.component.utils.LogUtil_jugg_fix`

**根因（两层 bug）**：

1. **className 匹配方向反了**：`dexFile.nameWithoutExtension.contains(className)` 中，D8 对单个 .class 文件输入时输出的 DEX 文件名只是简单名（如 `LogUtil.dex`），而 className 是全限定名（如 `com.tencent.component.utils.LogUtil`）。`"LogUtil".contains("com/tencent/component/utils/LogUtil")` 为 false，导致 className 始终匹配失败。
2. **输出文件名用了 D8 原始文件名**：`File(outputDir, dexFile.name)` 使用 D8 生成的原始文件名（如 `LogUtil.dex`），但 DEX 内部类经过 obfuscate + renameDexClassDeclaration 后已变为 `La/b/c_jugg_fix;`，导致文件名与 DEX 内容不匹配。

由于 className 匹配失败，obfuscate/rename 分支全部跳过，走到 else 兜底分支，输出原始未混淆的 DEX 文件。

**修复**：
1. className 匹配改为：先用 DEX 文件相对路径精确匹配全限定名，再用简单名回退匹配（`className.substringAfterLast('.') == dexSimpleName`）
2. 输出文件名改为从实际混淆后的类名推算：
   - 有 mapping：`obfuscatedInternal + SUFFIX + ".dex"`，如 `a/b/c_jugg_fix.dex`
   - 无 mapping：`internalName + SUFFIX + ".dex"`，如 `com/example/KeepClass_jugg_fix.dex`

**三个问题均由此修复解决**：
- 问题 2&3：文件路径正确后，`deployed/classes/` 存储正确，设备上 ClassLoader 可找到类
- 问题 1：修复后 `_jugg_fix` DEX 内部引用指向 `La/b/c;`（DB 中存在），不再被误识别为 removed class

### 6.5 Bug Fix: _jugg_fix 桥接类 field/clinit 剥离（2026-03-30）

**问题现象**：
1. `_jugg_fix` 类名未混淆（如 `com/tencent/component/utils/LogUtil_jugg_fix` 而非 `xxx/fjz_jugg_fix`）——这在 keep 类场景下是**正确行为**，因为原始类名本身未被混淆
2. 方法调用引用了未混淆的类名 `com.tencent.component.utils.LogUtil`——桥接类代码体中引用原始类是**设计意图**
3. **成员变量声明使用了 `com.tencent.component.utils.LogUtil.a`**（原始类的 final field），`_jugg_fix` 的 `<clinit>` 试图写入该 field → `IllegalAccessError`

**运行时 crash**：
```
java.lang.IllegalAccessError: Final field 'com.tencent.component.utils.LogUtil.a' 
cannot be written to by method 'void com.tencent.component.utils.LogUtil_jugg_fix.<clinit>()'
```

**根因**：

`renameDexClassDeclaration()` 的设计是将类声明和方法/字段声明 owner 重命名为 `_jugg_fix`，但保留代码体中的引用指向原始类。对于**混淆了类名的场景**（如 `LogUtil -> a.b.c`），这是正确的：

- field 声明 owner: `La/b/c_jugg_fix;`
- `<clinit>` 中 sput: `La/b/c;.a` — 写入 APK 中真实存在的 `a.b.c` 类的 field（可能仍有问题）

但对于 **keep 住的类**（`LogUtil -> LogUtil`），问题更严重：

- field 声明 owner: `LogUtil_jugg_fix`
- `<clinit>` 中 sput: `LogUtil.a` — 从 `LogUtil_jugg_fix` 类写入 `LogUtil` 的 `final` field → **IllegalAccessError**

根本原因：`_jugg_fix` 是**桥接类**，不应该有自己的 field 声明和 `<clinit>`：
- 桥接类的方法通过代码体引用原始类的 field（owner 保持原始类名），这是正确的
- 桥接类不需要自己拥有 field，也不需要初始化它们
- `<clinit>` 写入其他类的 final field 在 Android 运行时是非法的

**修复**：修改 `renameDexClassDeclaration()` 在生成桥接类时：
1. **剥离所有 field 声明**（`visitField` 返回 null）
2. **剥离 `<clinit>` 方法**（`visitMethod` 对 `<clinit>` 返回 null）
3. 保留 `<init>` 和所有普通方法

修复后的 `_jugg_fix` DEX 结构：
```
class LogUtil_jugg_fix (声明)
  fields: (无)
  <clinit>: (无)
  <init>: 保留（构造器可能仍被需要）
  方法 a(String,String): 代码体调用 LogUtil.d() → 桥接到原始类
  方法 b(): 代码体调用 LogUtil.e() → 桥接到原始类
```
