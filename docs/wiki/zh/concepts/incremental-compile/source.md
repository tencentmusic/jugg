---
title: 源码增量编译
description: 解释 Jugg 只编译变化源码时，如何恢复 Gradle 提供的编译上下文、调用方检查和 DEX 结构一致性。
status: active
tags:
  - concept
  - compile
  - source
---

# 源码增量编译

Gradle 编译一个模块时，不只是把 Java、Kotlin 转换成 class。它还会建立完整的 classpath，识别 Kotlin 模块边界，连接生成源码，检查没有修改的调用方，并用统一规则生成 DEX。

Jugg 为了缩短日常 Run，只处理本轮变化源码。文件少了，Gradle 原本隐式提供的这些保障也随之消失。源码增量编译要解决的问题因此不是怎样调用 `javac` 和 `kotlinc`，而是怎样在局部编译中恢复完整构建的语义，并在无法恢复时回到 Gradle。

## 只编译变化源码，会失去什么

一次可信的 Gradle 构建为后续增量编译留下 APK、class、依赖和编译参数。最朴素的增量方案可以直接复用这些 class 作为依赖，只把变化文件交给编译器：

```text
最近一次 Gradle 产物
  -> 作为本轮编译的 classpath
  -> 编译变化的 Java / Kotlin
  -> 生成新的 class
```

这条路径省掉了完整 task 的调度和大量未变化输入，但也缩小了编译器能看到的范围。

| Gradle 原本提供的保障 | 朴素增量编译的问题 | Jugg 的处理方式 |
|---|---|---|
| 完整编译上下文 | classpath、模块身份或工具版本不一致 | 从 Gradle 基线恢复当前模块的编译上下文 |
| Java、Kotlin 与生成源码同轮可见 | 编译器读取到上一轮的旧符号 | 按产物依赖安排编译顺序，并传入本轮源码 |
| 全量调用方检查 | 未修改的调用方不会参与编译 | 对比 class 结构，继续编译受影响源码 |
| 统一的 DEX 转换 | 新 DEX 与基线 APK 的脱糖结构不同 | 读取基线状态，补充受影响 classpath 并采用相同转换策略 |

后面的几节分别解释这些缺口为什么会导致编译错误或运行时崩溃，以及 Jugg 如何收口。

## 复用 Gradle 基线，而不是重新实现 Gradle

Java 编译相对直接。Jugg 从项目快照中恢复 classpath、源码和目标字节码版本等参数，再通过 JDK 编译器生成 class。部分 Android Studio 运行环境无法从标准入口取得 Java 编译器时，Jugg 会改用 JDK 自带的另一套工具入口，避免因为 IDE 使用的 Runtime 不同而中断增量编译。

Kotlin 对编译上下文更敏感。编译器看到的模块名、输出目录和同模块 classpath，不只是命令行参数，它们会改变源码可见性和最终字节码。

| 上下文不一致 | 用户看到的结果 |
|---|---|
| 同模块 class 被识别成外部依赖 | Gradle 可以通过的 smart cast 在增量编译时报错 |
| 模块名或同模块 classpath 不一致 | `internal` 成员无法访问，或者运行时出现 `NoSuchMethodError` |
| 新的模块描述没有合并 | 后续 Kotlin 编译找不到新增的顶层声明或扩展函数 |

Jugg 把 Kotlin 输出写回当前模块的 class 目录，并传入与 Gradle 一致的模块身份。这样，编译器会把旧 class 和本轮源码视为同一个模块。单文件编译生成的 `.kotlin_module` 也会与原模块描述合并，新增的顶层声明才能继续被其他 Kotlin 文件引用。

还有一层问题来自 Android Studio 本身。IDE 与 Kotlin 编译器可能包含包名相同、版本不同的类，直接在 IDE 的类加载环境中运行编译器会混用两套实现。Jugg 使用独立的类加载环境装载 Kotlin 编译器及其依赖，让编译器运行时只看到同一版本的实现。

Kotlin Multiplatform 又增加了 common source、platform source 和 source set 继承关系。Jugg 优先读取 Gradle Kotlin task 提供的 source set 与 fragment 信息；缓存缺失或关系无法确认时，不根据目录名猜测模块结构。辅助信息失败只影响对应能力，普通 Kotlin 编译仍沿用原有路径。

## 混编工程需要看到本轮最新符号

Java 和 Kotlin 经常互相引用。假设同一轮同时修改一个 Java 接口和它的 Kotlin 实现，如果 Kotlin 编译器只能看到基线中的旧 Java class，它会基于过期的方法签名检查 Kotlin 文件，产生 `reference not found`、类型不匹配或未实现抽象方法等错误。

Jugg 先让 Kotlin 编译器读取本轮 Java 源码。Kotlin 生成的新 class 随后进入 Java 编译的 classpath，Java 阶段便能读取到最新 Kotlin 符号。

```text
注解处理、DataBinding 等生成源码
  -> Kotlin 读取本轮 Java 源码
  -> Java 读取本轮 Kotlin class
  -> 汇总所有新 class
```

生成源码必须排在语言编译之前。DataBinding、ViewBinding、注解处理器或 KSP/KAPT 产出的源码如果在语言编译之后才出现，本轮就无法形成完整 class。Jugg 会把已经产生的生成源码登记为本轮输入；生成上下文无法确认时，则通过 Gradle 重新建立基线。

## 编译成功不代表修改可以安全运行

全量编译会检查模块内所有源码。局部编译只检查直接变化文件，因此可能把原本应该在编译期发现的问题推迟到运行时。

例如，类 A 删除了一个方法，调用它的类 B 没有修改：

```text
A 删除方法
  -> A 自己编译成功
  -> B 没有进入本轮编译，仍保留旧调用
  -> 部署后调用旧方法
  -> 运行时出现 NoSuchMethodError
```

新增抽象方法、修改字段签名或改变 inline 方法也有类似问题。Jugg 在首轮源码编译后比较新旧 class 结构，再通过基线和部署历史中的引用关系查找调用方、子类及对应源码，把它们加入下一轮编译。

```text
变化 class
  -> 与当前有效 class 做结构对比
  -> 找出方法、字段、继承或 inline 影响
  -> 查询调用方、子类和对应源码
  -> 追加下一轮编译，直到影响范围收敛
```

这里比较的是设备当前可能使用的 class，不只是最近一次 Gradle APK 中的 class。已经增量部署过的修改也要参与分析，否则连续多轮修改会从错误的旧结构开始计算影响范围。传播规则见[重编译 / 扩散编译](./recompile-propagation.md)，编译期常量的处理见[常量引用分析](./const-ref.md)。

## 新 DEX 必须与基线 APK 保持相同结构

Java 和 Kotlin 先生成 class，随后由 D8 转换成 DEX。D8 还可能执行脱糖，把接口 default method 或较新的 JDK API 改写成低版本 Android 可以运行的结构。

增量编译不能只根据当前模块的 `minSdk` 决定是否脱糖。设备中运行的是最近一次 Gradle 构建生成的 APK；如果基线 APK 已经脱糖，而本轮 DEX 没有脱糖，一个重新编译的子类可能不再包含脱糖阶段生成的方法，旧接口中也没有原始 default method，运行时便会出现 `AbstractMethodError`。反过来，基线没有脱糖时，本轮也不应凭空引入另一套结构。

Jugg 会从基线 APK 的类结构判断当前应用是否启用了脱糖，再让本轮 D8 使用相同策略。启用脱糖时，D8 还需要读取 default method 相关接口。把整个模块 classpath 重新交给 D8 会扩大处理范围，因此 Jugg 先通过引用索引找出相关接口、子类和调用方，只把需要的 class 放入本轮临时 classpath：

```text
新的 class
  -> 查询基线 APK 的脱糖状态
  -> 找出 default method 相关接口和 core library 重写信息
  -> 组装本轮需要的最小 classpath
  -> 用相同脱糖策略生成单类粒度 DEX
```

Jugg 还会优先使用当前项目 Android Gradle Plugin 配套的 D8，减少工具版本不同造成的字节码差异。项目工具无法安全加载或执行失败时，才回退到 Jugg 内置版本，并保留原始失败信息用于排查。

## 一轮源码增量编译如何结束

前面的处理最终汇合为一条固定流程：

```text
可信 Gradle 基线
  -> 收集直接变化源码和本轮生成源码
  -> Kotlin / Java 编译
  -> 对比 class 结构，追加受影响源码
  -> D8 生成 DEX，补处理脱糖结构差异
  -> release 变体按原 mapping 处理类名
  -> 将局部产物交给部署阶段
```

这条链路依赖可信基线。首次运行、构建脚本或依赖上下文变化、生成代码无法确认、增量影响范围过大，或者编译失败且无法恢复时，Jugg 会执行 Gradle 构建并刷新 APK、class 与本地索引。回到 Gradle 是增量编译的边界处理，不会把不完整产物当成成功结果。

## 相关页面

- [增量编译总览](./index.md)
- [重编译 / 扩散编译](./recompile-propagation.md)
- [常量引用分析](./const-ref.md)
- [DataBinding / ViewBinding](./databinding-viewbinding.md)
- [release 增量编译](./release-compile.md)
- [源码编译能力](../../capabilities/compile/source-compile.md)
- [重试、重装与 Gradle 构建](../fallback-and-limits.md)
