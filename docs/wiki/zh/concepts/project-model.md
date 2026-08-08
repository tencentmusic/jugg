---
title: 工程上下文获取
description: 解释 Jugg 为什么不能信任单一信息源，如何把 IDE 与 Gradle 两份工程信息合并成项目快照，以及这份快照的边界。
status: active
tags:
  - concept
  - project
  - context
---

# 工程上下文获取

增量编译不能只看文件后缀。一个源码文件要用哪份 classpath、写到哪个输出目录、最终归属哪个 APK，都依赖一份准确的工程上下文。Jugg 的解法是同时读取 IDE 与 Gradle 两个来源，再合并成一份项目快照，供编译、部署、依赖变化检测和 Android Test 复用。

## 两个信息源各有盲区

要安全地“只编译变化部分”，必须先有一份可信的工程描述：模块结构、source set、resource 与 assets 路径、当前 variant、classpath、依赖关系、应用包名和 APK 归属。问题在于，能提供这份描述的两个来源各自都有盲区。

- 只读 IDE 工程模型，速度快，能立刻拿到模块、source set、运行配置和设备交互信息。但 Gradle 执行阶段才确认的运行时依赖不会完整体现在 IDE 模型里，高版本 AGP 调整输出布局后也需要 Gradle 信息校正产物路径。
- 只读 Gradle 信息，准确，能在真实构建环境里确认依赖、classpath、variant 和 APK 输出。但它必须等一次 Gradle 执行之后才能刷新，无法随 IDE 操作即时更新。

任何一边单独作为增量编译的唯一输入，都无法同时满足即时性和 Gradle 真实构建上下文，容易给出错误的 classpath 或产物归属，进而让增量结果与全量构建不一致。

## 双源合并为一份项目快照

两个来源都不完整，Jugg 不偏信任何一边：以 IDE 信息为基础，叠加 Gradle 信息和 include build 信息，合并出一份统一的项目快照：

```text
IDE sync 或 Gradle fetch 完成
  -> 读取 IDE 工程信息
  -> 读取 Gradle 工程信息与 include build 信息
  -> 对齐两边的模块名
  -> 合并 source/res/assets、classpath、依赖、variant 与 androidTest 信息
  -> 输出统一的项目快照
```

合并遵循“以准确来源补全快速来源”的优先级：

- 缺少 IDE 工程信息时不生成快照，避免在不可信的基础上做增量。
- 关闭 Gradle 信息读取，或暂时没有 Gradle 信息时，退回到 IDE 信息单独使用。
- IDE 信息比 Gradle 信息更新时，不强制刷新依赖，避免用过时的 Gradle 结果覆盖更准确的当前状态。
- 某个模块只出现在其中一边时，是否保留由合并策略结合当前构建目标判断。

合并后的快照再叠加 include build 信息，作为后续每一轮增量编译的输入。

## 模块名对齐

双源合并的一个隐形难点是模块标识不一致。Gradle 侧读到的模块名有时与 IDE 侧不完全相同，如果直接按名字合并，同一个模块会被当成两个，依赖关系也会跟着错位。

合并时会把 Gradle 模块名对齐到 IDE 模块名，并同步修正依赖关系里引用的名字，保证两份信息描述的是同一套模块。

## androidTest 的合并顺序

Android Test 需要一个额外的合成模块来承载测试代码。只有当用户把构建目标设为 `BuildTarget=ANDROID_TEST` 时，Gradle 信息读取才会包含 androidTest 的 source set，并生成对应的测试模块。

```text
构建目标 = ANDROID_TEST
  -> Gradle 侧读取 androidTest source set
  -> 生成对应的 androidTest 合成模块
  -> 标记为测试模块，记录被测目标
```

IDE 侧也会根据 Android 模型生成 androidTest 模块。两边合并时有一条顺序约束：当已经存在 Gradle 侧的 androidTest 信息时，只在 IDE 侧出现、却不在 Gradle androidTest 集合中的测试模块会被丢弃，避免引入 Gradle 无法证实的测试模块。对应的用户视角行为见 [Android Test 流程](./android-test-flow.md)。

## 上下文更新后的重绑

工程上下文更新不只是替换一份数据。一旦快照刷新，依赖它的运行组件必须一起重新对齐，否则编译会继续使用旧的 classpath 与归属关系。

```text
新的项目快照
  -> 刷新编译上下文
  -> 重新绑定文件变化过滤、classpath、模块到 APK 的归属
  -> 重新绑定自定义编译器与部署历史恢复
```

这一步会影响 classpath、模块到 APK 的归属、文件变化过滤、自定义编译流程和部署历史恢复，是快照变化能否被后续增量正确消费的关键。

## 快照的新鲜度与一致性约束

双源合并换来了准确性，也留下几条必须接受的约束：

- 快照的可信度依赖 Gradle 基线的新鲜度。当工程结构在 IDE 之外被改动时，快照需要随基线一起重建。
- 合并优先级保证“准确来源补全快速来源”，代价是 Gradle 信息刷新有滞后，结构性变化要等下一次 Gradle 执行才能反映。
- 模块名对齐与 androidTest 合并顺序都是为保证一致性而设的约束，会让只在单边出现的模块在特定条件下被丢弃。

## 相关页面

- [编译调度流程](./compile-pipeline.md)
- [增量编译](./incremental-compile/)
- [Android Test 流程](./android-test-flow.md)
- [重试、重装与 Gradle 构建](./fallback-and-limits.md)
