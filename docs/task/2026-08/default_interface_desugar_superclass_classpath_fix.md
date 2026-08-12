# 默认接口脱糖缺失父类层级修复

## 背景

Jugg report `088ddab7` 反馈增量编译后运行时 crash：

```text
java.lang.IllegalArgumentException: Cannot add a null child view to a ViewGroup
```

业务类的关系可简化为：

```text
IBasePlayerHost
  default getLeftDetailPage() = null
        ↑
IPlayerHost

AbstractPlayerFragment
  override getLeftDetailPage() = real view
        ↑
PlayerFragment implements IPlayerHost
```

完整 Gradle 构建行为正常，但 Jugg 重新编译 `PlayerFragment` 后，运行时调用到了接口默认实现并返回 `null`，没有沿父类层级调用 `AbstractPlayerFragment` 的 override。

报告使用的插件版本为 `3.0.33-release`，但排查确认问题在当前主线仍然存在，因此不是单纯的旧版本 Jugg 使用问题。提交 `ab8df690848e3d99b45acdaed98c7390b7adc8a2` 修复的是 AGP 升级后旧 javac 输出目录遮蔽，与本次 D8 脱糖 classpath 缺失父类层级无关。

## 根因

### 增量编译链路

相关职责原先分为两部分：

- `DeployDataGenerator.getDesugarInfo()` 解析本轮 class 引用的接口，通过 APK/deploy database 找到包含默认方法的接口及其接口继承链。
- `CompileEffectAnalyzer.getDesugarInfo()` 根据这些接口名查找 class 文件，并复制到临时目录作为 D8 desugar classpath。

这条链路只补齐了默认接口，没有补齐本轮 program class 的父类层级。

### D8 的错误决策

当 D8 同时看到以下输入时：

- program input：`PlayerFragment.class`
- classpath：默认接口及其父接口
- 缺失：`AbstractPlayerFragment.class` 及其父类层级

D8 无法确认父类已经提供 `getLeftDetailPage()` 的具体实现，可能在子类中生成 synthetic bridge，直接调用接口 companion 中的默认实现。生成后的子类方法遮蔽了父类 override，因此运行时得到 `null`。

失败用例的 DEX 证据表现为子类新增 synthetic `getPage()`，并调用：

```text
ParentOverrideDefaultInterface$-CC.$default$getPage
```

把父类 class 加入 D8 classpath 后，该 synthetic bridge 不再生成，方法分派继续使用父类实现。

## 既有测试为何没有覆盖

`DeployDataGeneratorTest.testGetDesugarClasspath` 已经覆盖默认接口脱糖，但只断言 `DesugarInfo.allInterfacesWithDefaultMethod`：

- 能识别直接默认接口。
- 能补齐接口继承链。
- 能识别静态调用或 lambda 关联的默认接口。

它没有构造“子类继承父类 override，同时实现带默认方法接口”的类层级，也没有验证以下两个独立行为：

1. `CompileEffectAnalyzer` 最终复制到 D8 classpath 的物理 class 文件是否包含父类层级。
2. D8 最终生成的子类 DEX 是否仍保持父类 override 的方法分派。

因此，即使 `DeployDataGeneratorTest` 全部通过，也只能证明默认接口集合正确，不能证明 D8 拥有完成方法分派分析所需的完整 classpath。

## 方案比较

### 方案一：只修改 `CompileEffectAnalyzer.getDesugarInfo()`

在 analyzer 内单独使用 ASM 读取 `superName`，再查找父类文件。

该方案能修复问题，但会在 `CompileEffectAnalyzer` 中重复实现 class 文件解析逻辑。接口依赖由 `ClassFileParser` 解析，父类依赖却由 analyzer 自行解析，职责分裂且难以复用同一批 program input 的过滤语义，因此不采用。

### 方案二：扩展 `DesugarInfo`

让 `DeployDataGenerator.getDesugarInfo()` 同时返回默认接口和父类集合。

`DesugarInfo` 当前表达 D8 脱糖配置与默认接口信息，父类文件查找属于构造物理 classpath 的职责。扩展该契约会把 module/library 文件定位问题带入 deploy data 层，并扩大序列化和调用方影响面，因此不采用。

### 方案三：`ClassFileParser` 提取父类，`CompileEffectAnalyzer` 递归补齐

采用该方案：

- `ClassFileParser` 统一负责从 class/jar 中提取直接父类引用。
- 解析完整批次后，排除同一 program input 已包含的 class，避免解析顺序影响结果。
- 过滤 Android/JDK boot classpath 类型，无需从工程依赖中查找。
- `CompileEffectAnalyzer` 复用现有 `ClassFileLookupHelper`，按 module dependency、library dependency 和全工程兜底顺序查找父类文件。
- 对找到的父类继续解析其外部父类，直到层级闭合或已无法找到更多 class。
- 仅当本轮命中默认接口脱糖时补齐父类，保持未命中场景的原有路径和开销不变。
- 不修改 `DesugarInfo` 公共契约。

这个边界使 `ClassFileParser` 负责“字节码声明了哪些外部类型”，`CompileEffectAnalyzer` 负责“这些类型的 class 文件在哪里以及复制哪些文件”，与现有职责一致。

## 实现范围

### `ClassFileParser`

新增 `externalSuperClasses`：

- 在 ASM `visit()` 中收集每个 class 的 `superName`。
- getter 在全部 class 解析完成后再排除 `classes`，保证父类和子类在同一批输入时不受解析顺序影响。
- 使用 `isBootClasspathClass` 排除 `java.*`、`javax.*`、Android framework 和 Dalvik 类型。

### `CompileEffectAnalyzer`

`getDesugarInfo()` 保留原有默认接口查找，并增加父类文件集合：

```text
本轮 class
  -> externalSuperClasses
  -> getClassFilesByName
  -> 对找到的父类再次解析 externalSuperClasses
  -> visited 去重并递归直到闭合
```

默认接口文件和父类文件按相对 class path 去重后，一起复制到 D8 classpath。找不到的父类沿用现有 class lookup 的 best-effort 行为，不阻断其他已找到依赖的使用。

## TDD 与测试设计

### Demo 类层级

在 `android_demo_project` 增加以下最小模型：

```text
ParentOverrideDefaultInterface
  default getPage() = null
        ↑
ParentOverrideChildInterface

ParentOverrideRootClass
        ↑
ParentOverrideBaseClass implements ParentOverrideDefaultInterface
  override getPage() = "parent-implementation"
        ↑
ParentOverrideChildClass implements ParentOverrideChildInterface
```

额外增加 `ParentOverrideRootClass`，用于证明实现会递归补齐完整父类层级，而不是只补直接父类。

### 失败证据

首先在 `DexTest.dexSubclassKeepsInheritedDefaultInterfaceOverride` 中只向 D8 提供两个默认接口，不提供 `ParentOverrideBaseClass`，并断言子类不应调用默认接口 companion。

修改前测试失败：期望 `false`，实际 `true`。DEX 解析确认子类生成了调用 `$default$getPage` 的 synthetic bridge。

### 回归 owner

| 层级 | 测试 owner | 保护行为 |
|---|---|---|
| L1 | `ClassFileParserTest.testExternalSuperClasses` | 提取外部直接父类；同一 program input 内父类不重复返回；继续暴露下一层外部父类 |
| L1 | `DeployDataGeneratorTest.testGetDesugarClasspath` | 新类层级仍能识别子接口和默认接口继承链 |
| L1 | `CompileEffectAnalyzerTest` | 默认接口、直接父类和根父类均被复制到 D8 classpath |
| L1 | `DexTest.dexSubclassKeepsInheritedDefaultInterfaceOverride` | 完整 classpath 下 D8 不生成绕过父类 override 的默认方法调用 |
| L3/Flow | `JuggCompilerTest.testInheritedDefaultInterfaceOverrideIsKept` | 真实 Jugg Java 增量编译链路产出的子类 DEX 保持父类方法分派 |

## 验证结果

失败用例建立后，完成以下验证：

```text
./gradlew :main:test \
  --tests 'com.sickworm.intellij.jugg.deploy.data.ClassFileParserTest' \
  --tests 'com.sickworm.intellij.jugg.deploy.CompileEffectAnalyzerTest' \
  --tests 'com.sickworm.intellij.jugg.deploy.data.DeployDataGeneratorTest.testGetDesugarClasspath'

./gradlew :idea:test \
  --tests 'com.sickworm.intellij.jugg.compile.DexTest.dexSubclassKeepsInheritedDefaultInterfaceOverride' \
  --tests 'com.sickworm.intellij.jugg.manager.JuggCompilerTest.testInheritedDefaultInterfaceOverrideIsKept' \
  --tests 'com.sickworm.intellij.jugg.manager.JuggCompilerTest.testJavaMethodChangeContent'
```

结果：

- main 定向测试全部通过。
- D8 语义回归通过，子类 DEX 不再调用接口默认实现 companion。
- Jugg 增量编译 Flow 通过。
- 普通 Java 方法体修改回归通过，未命中默认接口场景保持原有行为。
- 使用 Jugg CLI 增量编译 demo 成功，未降级为 Gradle 编译。
- `git diff --check` 通过。

## 变更边界

- 不修改默认接口发现算法和 database 数据结构。
- 不修改 `DesugarInfo` 契约。
- 不把父类 class 作为 program input，只补充 D8 classpath。
- 不扫描或复制 Android boot classpath。
- 不在未发现默认接口时增加父类扫描开销。
- 不处理与本次方法分派无关的 javac 输出目录或旧构建产物兼容问题。

## 提交

- `b20948835 [test] reproduce inherited default interface dispatch regression`
- `[bugfix] preserve inherited overrides during interface desugaring`
