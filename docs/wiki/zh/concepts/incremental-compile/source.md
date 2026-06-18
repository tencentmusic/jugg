---
title: 源码增量编译
description: 解释 Jugg 如何只把变化的 Java、Kotlin 文件送入编译器，并绕开编译器进程内的类冲突、模块识别和脱糖一致性问题。
status: active
tags:
  - concept
  - compile
  - source
---

# 源码增量编译

源码改动是日常 Run 中最常见的触发源。Jugg 要避免每次小改动都启动完整 Gradle 编译，但又不能把 Java、Kotlin 和 DEX 当成普通文本转换：编译器看到的 classpath、模块身份、生成源码和旧 APK 结构都必须与全量构建对齐。

源码增量编译处理 Java、Kotlin、class 文件，以及由资源或注解处理产生的源码。它复用最近一次 Gradle 构建的 classpath，只把本轮变化文件和必要的受影响文件送入编译器。难点不只是“少编译几个文件”，还包括在 IDE 进程里调用编译器时绕开类冲突、模块识别错位、关闭脱糖后与旧产物结构不一致等问题。

## Kotlin 编译器的进程内类冲突与隔离

Kotlin 编译器（`kotlin-compiler-embeddable` 提供的 `K2JVMCompiler`）和 IntelliJ IDEA 内部存在包名相同、但实现版本不同的类时，直接在 IDE 进程的类加载环境里运行编译器会让两套同名类混用，编译行为变得不确定。

Jugg 用一个独立的 `ClassLoader` 加载 Kotlin 编译器及其依赖，把它与 IDE 进程的同名类隔离开。隔离后，编译器看到的是自己那套完整、版本一致的类，增量编译的 Kotlin 结果才能与全量构建对齐。Java 编译没有这层进程内冲突，接近直接调用 `javac`。

不过隔离加载只解决了"用哪套类"的问题；编译器还要正确判断"哪些 class 属于同一个模块"，否则会在 smart cast 上出错。

## 模块识别：输出目录与 smart cast 对齐

Kotlin 的 smart cast、`internal` 成员可见性等行为都依赖"编译器把哪些 class 看成同一个模块"。如果增量编译把同模块的 class 误判成外部依赖模块，编译器会拒绝本应成立的 smart cast，报出与全量构建不一致的错误。`internal` 成员还有一层字节码约束：它的命名包含模块名（module-name）。Gradle 全量编译用的是工程真实模块名；增量编译如果用了不一致的模块名，调用方在运行时会因为找不到对应符号而抛出 `NoSuchMethodError`。

Jugg 在两处对齐模块身份：把 Kotlin 输出目录指向模块自身的 class 目录，使编译器把同模块 class 识别为同一模块而非外部依赖，让 smart cast 与全量构建一致；同时在编译参数中传入与 Gradle 一致的模块名和同模块 classpath 标记，让 `internal` 成员的字节码命名和可见性对齐。

模块身份对齐之外还有一个收尾约束：Kotlin 顶层声明、扩展函数等信息无法直接表达在 JVM 字节码里，而是记录在模块描述（`.kotlin_module`）中。单文件编译新增顶层声明时，新的描述内容需要合并回原模块描述，否则后续 Kotlin 编译会缺少这些声明。

## 混编符号新鲜度与编译器获取兜底

混编工程里，Kotlin 和 Java 互相引用。如果 Kotlin 编译时只能看到上一轮的旧 Java classpath，就会拿到过期符号；部分 Android Studio 版本中，JDK 标准入口 `ToolProvider.getSystemJavaCompiler()` 会返回空，直接用它获取编译器会拿到 `null`。

Jugg 对这两点各有兜底：混编时先编译 Kotlin，并让 Kotlin 编译器直接读取同模块的 Java 源文件，避免只看到旧 classpath；当 `ToolProvider.getSystemJavaCompiler()` 返回空时，改用 JDK 内置的 javac 工具 API 获取编译器实例，绕开该环境下的缺陷。

## 关闭脱糖后的 default method 补处理

Java / Kotlin 编译产出 class 后，Jugg 用 D8 转成 DEX。D8 默认会做脱糖（desugaring），把接口的 default method 等高版本特性改写成低版本兼容形态。脱糖对全量构建无害，但在增量场景下耗时可观，因此 Jugg 可以关闭脱糖以提速。代价是结构一致性：基线 APK 里的接口和实现类已经脱糖、本轮新增 DEX 不脱糖时，子类或调用方会和旧结构对不上，运行时出现 `AbstractMethodError` 或找不到方法。

Jugg 在关闭脱糖的同时做定向补处理：通过对基线产物的引用索引查找与 default method 相关的类，并重新生成对应 DEX。查询范围覆盖含默认方法的接口、它的子类、`invoke-static` 形式的调用，以及覆盖了接口默认方法又被删除的方法，让新旧结构在脱糖维度上保持一致。DEX 还按"每个 class 输出独立单元"的方式生成，以满足在线替换对单类粒度的要求。

## 扩散编译：只编直接改动并不安全

只编译本轮直接变化的文件会漏掉调用方检查。删除方法、修改字段签名或给抽象父类新增抽象方法时，改动文件本身能编译通过，但旧调用方或子类仍留在 APK 中，直到运行时才抛出 `NoSuchMethodError` 或 `AbstractMethodError`。

首轮编译成功后，Jugg 会做一次影响分析：

```text
变化 class
  -> 与基线或已部署 class 做结构对比
  -> 判断方法、字段、抽象方法或 inline 影响
  -> 通过引用索引查出调用方、子类和对应源码
  -> 把受影响源码加入下一轮编译
```

已经增量部署过的类也会参与对比，不能只看 Gradle 基线里的旧 class。扩散编译的传播规则、收敛边界和 release 补偿见[重编译 / 扩散编译](./recompile-propagation.md)；编译期常量的特殊处理见[常量引用分析](./const-ref.md)。

## 阶段顺序约束

源码阶段内部的顺序不能随意调整，否则后续阶段会在缺少前置产物的情况下报出误导性错误：

```text
注解处理与 DataBinding 生成源
  -> Kotlin
  -> Java
  -> DEX
  -> minify
```

这些顺序来自产物依赖：生成源码必须早于语言编译，Kotlin 必须早于 Java（Java 阶段要消费 Kotlin 产出的 class），minify 必须在 DEX 之后。

## 相关页面

- [增量编译总览](./index.md)
- [DataBinding / ViewBinding](./databinding-viewbinding.md)
- [Android Manifest 编译与 release 增量编译](./manifest-minify.md)
- [重编译/扩散编译能力](../../capabilities/compile/recompile-propagation.md)
- [常量引用分析](../../capabilities/compile/const-ref.md)
