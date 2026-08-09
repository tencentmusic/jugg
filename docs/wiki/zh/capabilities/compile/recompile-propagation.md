---
title: 重编译/扩散编译
description: 说明 class 结构和内联常量变化如何让 Jugg 追加编译未直接修改的使用方，以及对应的支持和回退边界。
status: active
tags:
  - capability
  - compile
  - recompile
  - const
---

# 重编译/扩散编译

Jugg 支持在直接变化的源码编译成功后，继续查找没有修改但需要适配本轮变化的源码，并把它们加入下一轮编译。影响来源既包括方法、字段和泛型等 class 结构变化，也包括 Java/Kotlin 内联常量变化。

本页用于判断哪些修改会触发追加源码编译，以及用户会看到什么结果。class 结构比较和逐轮传播见[重编译 / 扩散编译原理](../../concepts/incremental-compile/recompile-propagation.md)；常量为什么需要独立分析见[常量引用分析原理](../../concepts/incremental-compile/const-ref.md)。

## 支持范围

| 影响来源 | 当前支持情况 | 用户可见结果 |
|---|---|---|
| 方法删除或 JVM 签名变化 | 支持 | 直接调用方可能被追加编译；实例方法还会按虚方法分发检查子类中的旧调用 |
| 影响调用方式的方法访问修饰变化 | 支持可从 class 结构确认的变化 | 仍按旧调用方式编译的使用方可能被追加编译 |
| 字段删除或类型变化 | 支持 | 访问旧字段的源码可能被追加编译 |
| 抽象父类或接口新增抽象方法 | 支持 | 未实现新方法的子类或实现类可能被追加编译 |
| 类级泛型 signature 变化 | 支持部分确定场景 | 子类声明链，以及存在直接方法或字段引用的调用方可能被追加编译 |
| Java 可内联 `static final` 常量变化 | 支持 | 可能携带旧字面量的引用方会被追加编译 |
| Kotlin `const val` 变化 | 支持 | top-level、object、companion、嵌套 class/object 等常见形式的引用方会被追加编译 |
| 常量删除或 `const -> val` | 支持 | 使用旧值的引用方仍可能被追加编译，避免继续携带旧字面量 |
| Kotlin top-level 或扩展声明的 JVM 签名变化 | 支持已命中的 file facade 场景 | 必要时允许刚编译过的使用方再进入一轮编译，避免继续读取旧签名 |

> [!NOTE]
> 常量引用分析使用 syntax-only 引用候选做保守匹配，可能追加编译多个候选使用方。匹配不要求目标常量已经完成全量扫描。

## 不属于普通源码传播的变化

| 场景 | 处理方式 |
|---|---|
| 只修改方法体 | 当前文件正常编译和部署，但不会仅因此追加编译普通调用方 |
| 修改 `extends` / `implements` 关系 | 变化 class 会进入部署判断，但该结构差异本身不会直接用于查找受影响源码；需要未修改源码适配时使用 Gradle 编译 |
| release inline 或 R8/ProGuard 删除成员 | 由 [Release 编译](./release-compile.md)执行字节码补偿，不等同于源码扩散 |
| `R.java`、DataBinding/ViewBinding 等生成源码 | 由对应生成阶段直接交给[源码编译](./source-compile.md)，不是编译成功后的影响传播 |

## 触发与结果

```text
直接变化的源码编译成功
  -> 收集 class 结构变化
  -> 比较内联常量定义并匹配引用候选
  -> 将两类结果合并为受影响源码
  -> 把未处理的受影响源码加入下一轮编译
  -> 新产物产生新的影响时继续下一轮
  -> 没有新的受影响源码后进入部署
```

追加编译是首轮成功后的正常步骤，不代表第一次编译失败。相同文件只有在出现新的影响来源，或命中 Kotlin file facade 等已确认场景时，才会再次进入后续轮次。

## 常见用户可见现象

| 现象 | 含义 |
|---|---|
| 日志出现 `Detect effected sources` | 本轮正在编译影响分析发现的源码 |
| 日志出现 `found effected source files, continue compile` | 当前编译成功，并发现需要追加的下一批源码 |
| 一个小改动触发多个文件或多轮编译 | 方法、字段、抽象方法、泛型或内联常量变化影响了未修改源码 |
| 受影响文件或模块较多后回退 Gradle | 追加范围已经超过当前增量编译限制 |

## 使用边界

- 影响分析依赖最近一次可信 Gradle 构建，以及后续成功部署形成的 class 结构、引用关系和源码映射。
- 切换构建变体、修改构建配置或其他情况使基线失效后，需要执行 Gradle 编译重新建立索引。
- 受影响源码或模块超过增量编译限制时，Jugg 会停止继续扩大本轮工作并回退 Gradle。
- 无法从 class 还原源码文件时，本轮不能自动补编对应使用方；出现旧调用方异常时使用 Gradle 编译刷新基线。
- Java 常量只覆盖可参与内联的 `static final` 字段；Kotlin 只把 `const val` 作为内联常量处理，普通 `val` 不进入该分析。
- `private const val` 和 `private static final` 不进入常量引用索引；它们只影响已经参与首轮编译的声明文件。
- 注释和字符串中的相似文本不会被识别为常量引用候选。
- 常量分析未就绪时会使用已完成缓存或空结果继续主流程，不会仅因此中断编译或回退 Gradle；降级时本轮可能无法补齐全部使用方。
- 同一仓库的多个 worktree 可以共享文件指纹和常量分析缓存；缓存只减少重复解析，不改变影响判断结果。

## 相关页面

- [源码编译](./source-compile.md)
- [Release 编译](./release-compile.md)
- [编译阶段说明](../../guide/compile.md)
- [重编译 / 扩散编译原理](../../concepts/incremental-compile/recompile-propagation.md)
- [常量引用分析原理](../../concepts/incremental-compile/const-ref.md)
