---
title: 编译流水线
description: 说明 Jugg 如何把变化文件按类型编译为 DEX、资源、assets、Manifest 和其他部署产物。
status: active
tags:
  - concept
  - compile
---

# 编译流水线

Jugg 的编译流水线从变化文件开始，输出部署阶段需要的局部产物。它不会重新生成完整 APK，而是根据文件类型进入不同处理路径。

## 输入类型

原文中涉及的输入类型包括：

| 输入 | 处理方式 |
|---|---|
| Java 源码 | 调用 `javac` 编译为 class |
| Kotlin 源码 | 调用 `K2JVMCompiler` 编译为 class |
| class | 通过 D8 转成 dex |
| res 资源 | 通过 aapt2 compile 和 Jugg 定制 `inclink` 编译 |
| assets | 不需要编译，直接作为增量部署文件 |
| native lib | 作为 APK 更新文件处理 |
| Manifest | 编译为二进制 Manifest 后写回 APK |
| 依赖库变化 | 对 jar、资源、assets 做差分后复用现有编译流程 |

## 阶段顺序

典型顺序如下：

```text
变化文件
  -> assets / native lib 处理
  -> res / Manifest 编译
  -> 必要时生成 R.java
  -> Kotlin / Java 编译
  -> class 转 dex
  -> 扩散编译检查
  -> 交给部署阶段
```

资源阶段可能生成 `R.java`。如果资源 ID 有新增，`R.java` 还需要进入 Java 编译；如果没有新增 ID，`inclink` 可以跳过 `R.java` 生成，减少后续编译耗时。

## 为什么会有多轮编译

第一轮只处理直接变化文件并不总是足够。删除方法、修改字段签名、给抽象父类新增抽象方法等场景，会影响调用方或子类。

Jugg 会通过 APK 解析数据库查询引用关系和子类关系，把受影响源码加入下一轮编译。这就是原文中提到的扩散编译。

## 与 Gradle 的区别

Gradle task 通常以模块为单位处理输入，并由完整任务图决定构建顺序。Jugg 复用最近一次 Gradle 构建结果，只处理变化文件和必要的受影响文件。

这能减少日常小改动耗时；代价是当构建脚本、依赖、注解处理器或其他 Gradle 上下文不可信时，需要回到 Gradle 重新建立基线。

## 相关页面

- [增量编译](./incremental-compile/)
- [部署数据与影响分析](./deploy-data-and-impact.md)
- [回退与限制](./fallback-and-limits.md)
