---
title: 源码增量编译
description: 说明 Jugg 如何编译 Java、Kotlin 和 class 文件，并通过扩散编译补齐受影响源码。
status: active
tags:
  - concept
  - compile
  - source
---

# 源码增量编译

源码增量编译处理 Java、Kotlin、class 和由资源或注解处理产生的源码。Jugg 复用最近一次 Gradle 构建的 classpath，只把变化文件和必要的受影响文件送入编译链。

## Java 编译

Java 文件通过 JDK 的 `JavaCompiler` 编译。Jugg 使用 Gradle 基线中的 classpath、源码版本、目标版本和输出目录，只编译本轮变化的 Java 文件。

常用参数包括：

| 参数 | 作用 |
|---|---|
| `-cp` | 依赖 classpath，包括目录和 jar。 |
| `-g` | 保留调试符号，支持调试时查看变量名。 |
| `-source` | Java 源码版本。 |
| `-target` | class 文件目标版本。 |
| `-d` | class 输出目录。 |

部分 Android Studio 版本中，`ToolProvider.getSystemJavaCompiler()` 可能返回空。Jugg 的实现会通过 `com.sun.tools.javac.api.JavacTool` 获取编译器。

## Kotlin 编译

Kotlin 文件通过 `org.jetbrains.kotlin:kotlin-compiler-embeddable` 提供的 `K2JVMCompiler` 编译。由于 Kotlin 编译器和 IntelliJ IDEA 可能包含包名相同但实现不同的类，Jugg 使用独立 `ClassLoader` 加载 Kotlin 编译器及其依赖，避免和 IDE 类冲突。

常用参数包括：

| 参数 | 作用 |
|---|---|
| `-jvm-target` | 目标 JVM 版本。 |
| `-language-version` | Kotlin 语言版本。 |
| `-no-stdlib` / `-no-reflect` | 不自动加入标准库和反射库。 |
| `-module-name` | 模块名，影响 `internal` 成员命名和 `.kotlin_module`。 |
| `-Xfriend-paths` | 标记同模块 classpath，支持访问 `internal` 成员。 |
| `-Xjava-source-roots` | 让 Kotlin 编译器读取同模块 Java 源码。 |
| `-Xplugin` / `-P` | 传入 Kotlin 插件和插件参数。 |
| `-d` | 输出目录。 |

Java / Kotlin 混编时，Jugg 先编译 Kotlin。Kotlin 编译器可以通过 `-Xjava-source-roots` 读取 Java 源文件，因此不会只看到旧 classpath。

## Kotlin 特殊产物

`internal` 成员需要正确的 `module-name` 和 `friend-paths`。Gradle 编译会把 module name 纳入 `internal` 成员的字节码命名；Jugg 如果使用了错误的 module name，调用方可能在运行时遇到 `NoSuchMethodError`。

`.kotlin_module` 保存顶层声明、扩展函数等 JVM 字节码无法直接表达的信息。单文件编译新增顶层声明时，需要把新的 `.kotlin_module` 内容合并回原模块描述，否则后续 Kotlin 编译可能找不到这些声明。

Kotlin smart cast 还依赖模块识别。Jugg 会把输出目录设置为模块 class 目录，使编译器把同模块 class 识别为同一模块，而不是外部依赖模块。

## Dex 编译

Java 和 Kotlin 编译后得到 class 文件。Jugg 再通过 D8 把 class 转为 dex。

常用参数包括：

| 参数 | 作用 |
|---|---|
| `--output` | dex 输出路径。 |
| `--file-per-class` | 每个 class 输出一个 dex，满足 JVMTI 部署要求。 |
| `--lib` | 传入 `android.jar`。 |
| `--classpath` | 传入 classpath，供 D8 脱糖使用。 |
| `--min-api` | 工程 `minSdkVersion`。 |
| `--no-desugaring` | 关闭脱糖。 |

Jugg 可以关闭 D8 脱糖以减少部分耗时，但 default method 需要额外处理。旧 APK 中的接口和实现类可能已经被脱糖，新增 dex 如果不脱糖，子类或调用方可能和旧结构不匹配。

Jugg 使用 APK 解析数据库查找 default method 相关类，并重新生成对应 dex。查询范围包括含默认方法的接口、接口子类、`invoke-static` 调用和覆盖接口默认方法的删除方法。

## 扩散编译

只编译直接变化文件会漏掉调用方检查。例如删除方法、修改字段签名或给抽象父类新增抽象方法时，旧调用方可能仍能留在 APK 中，运行时才暴露 `NoSuchMethodError` 或抽象方法错误。

Jugg 会在首轮编译后做影响分析：

```text
变化 class
  -> 与基线或已部署 class 结构对比
  -> 判断方法、字段、抽象方法或 inline 影响
  -> 通过 APK 解析数据库查询引用类、子类和源码路径
  -> 把受影响源码加入下一轮编译
```

典型场景包括：

- 方法签名变化或删除时，编译引用类源码。
- 字段签名变化或删除时，编译引用类源码。
- 抽象父类新增抽象方法时，编译子类源码。
- Kotlin top-level、extension、inline、常量引用等场景需要按对应影响分析补编译。

已经增量部署过的类也会参与分析，不能只看 Gradle 基线里的旧 class。

## 与主流程的关系

源码阶段由 `SourceCompiler` 协调。JuggApt、DataBinding mapper、Kotlin、Java、Dex 和 Minify 的顺序不能随意调整：生成源码必须早于语言编译，Kotlin 必须早于 Java，minify 必须在 dex 之后。

```text
SourceCompiler
  -> JuggAptCompiler
  -> SourceDataBindingProcessor
  -> KotlinCompiler
  -> JavaCompiler
  -> DexCompiler
  -> DexMinifyCompiler
```

## 相关页面

- [增量编译总览](./index.md)
- [DataBinding / ViewBinding](./databinding-viewbinding.md)
- [Android Manifest 编译与 release 增量编译](./manifest-minify.md)
- [重编译/扩散编译能力](../../capabilities/compile/recompile-propagation.md)
- [常量引用分析](../../capabilities/compile/const-ref.md)
