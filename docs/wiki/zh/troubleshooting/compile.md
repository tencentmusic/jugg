---
title: 编译问题排查
description: 按现象定位 Jugg 编译失败、回退和资源/源码异常的第一步。
status: active
tags:
  - troubleshooting
  - compile
---

# 编译问题排查

当 Jugg 编译没有按预期工作时，先不要急着清理工程。优先保留现场日志，再根据现象判断是增量编译失败、主动回退 Gradle，还是资源、Manifest、release 等子链路问题。

## 先看哪里

优先查看项目下的 Jugg 日志：

```bash
build/jugg/log/compile_latest.log
```

如果这个快捷文件不存在，可以查看同目录下最新的 `compile_*.log`。

> [!TIP]
> 提交问题前，建议先备份 `build/jugg/log/` 和 `build/jugg/database/`。这些目录能用于判断 Jugg 的增量状态、部署历史和资源/类索引是否一致。

## 常见现象速查

| 现象 | 第一判断 | 优先查看 |
|---|---|---|
| 直接走 Gradle | Jugg 判断本轮不适合增量 | 日志中的 `fallback` / `Fallback` |
| 提示没有文件变化 | 文件变化未进入 Jugg 状态，或变化已被判定为无效 | `No file changes` 附近日志 |
| 编译失败后提示下次回退 | 增量编译失败，当前没有继续重试 | `Found incremental compile error` |
| 修改资源后运行异常 | 资源 overlay、`resources.arsc` 或 APK 归属异常 | aapt2 / `resources.arsc` / manifest 相关日志 |
| 修改 Kotlin/Java 后找不到符号 | 可能有漏检文件、依赖变化或影响源码未补编译 | `unresolved reference` / `cannot find symbol` |
| release 增量后运行 crash | 可能是混淆映射、注解、类型引用或 access flag 不一致 | crash 堆栈 + `Obfuscated:` 日志 |

## 直接回退 Gradle

回退通常不是错误。常见原因包括：

- 用户选择了强制 Gradle 构建。
- 设备或部署状态不可用。
- 文件变化太多，超过增量阈值。
- 切换了运行目标，例如 App 与 androidTest 之间切换。
- 修改了构建文件或依赖，并且不能安全增量。
- 没有发现文件变化，且当前不是可直接复用部署状态的场景。

你可以在日志中搜索：

```text
fallback
Force fallback
Too many changes
Build target changed
No file changes
```

如果你认为不应该回退，请先确认本轮确实保存了文件，并且 Android Studio 已完成文件处理。

## 提示没有文件变化

如果看到类似：

```text
No file changes. will fallback to gradle compile.
```

通常表示 Jugg 没有发现可用于增量编译的变化。可能原因：

| 可能原因 | 建议处理 |
|---|---|
| 文件没有保存 | 保存后重新 Run |
| 文件变化被回滚检测判定为未变化 | 确认文件内容真的改变 |
| 刚切换工程或首次运行 | 允许 Jugg 执行一次部署或 Gradle 构建建立基线 |
| Jugg 状态异常 | 保留日志后再尝试清理 Jugg 数据目录 |

> [!WARNING]
> 不建议一上来删除整个 `build/`。如果需要排查，应先备份 `build/jugg/`，否则会丢失最有价值的现场信息。

## Java / Kotlin 编译失败

如果是源码编译失败，日志里通常能看到编译器原始错误，例如：

```text
unresolved reference
cannot find symbol
kotlin compile result
```

优先判断：

1. 代码本身是否能通过 Gradle 编译。
2. 是否新增、删除或重命名了文件，但 Jugg 没有完整捕获。
3. 是否修改了依赖或构建脚本。
4. 是否是受影响源码需要进入重编译/扩散编译。

Jugg 会尝试对部分漏检文件和依赖缺失场景做一次自动修复并重试。如果重试后仍失败，建议执行一次 Gradle 构建重新建立基线。

## 增量编译失败

如果看到：

```text
Found incremental compile error.
Run again directly will fall back to gradle compile.
```

先尝试 Sync 一次项目。如果确认 Gradle 编译成功、但 Jugg 增量编译失败，请保留 `compile_latest.log` 和本轮修改文件列表。

## 依赖库增量编译

当检测到 build 文件修改时，Jugg 会弹出确认窗。参考文档中列出的选项包括：

| 选项 | 含义 |
|---|---|
| `Fallback to Gradle` | 本轮直接降级为 Gradle 编译 |
| `Find out changed Libraries` | 执行 Gradle 读取依赖变化，再二次确认 |
| `Ignore build changes` | 忽略 build 文件变化，继续按增量路径处理 |
| 关闭弹窗 | 取消本轮操作 |

如果选择找出依赖库变化，Jugg 会先读取依赖差异，再要求确认变化是否符合预期。参考文档中说明整体耗时通常约 `40-80s`。

## 资源 / AndroidManifest 编译失败

资源相关问题通常会出现 aapt2 或 Manifest 相关信息。

优先搜索：

```text
aapt2
inclink
AndroidManifest.xml
resources.arsc
R.java
```

常见情况：

| 现象 | 可能原因 | 建议处理 |
|---|---|---|
| aapt2 compile 失败 | XML 或资源文件本身不合法 | 先按 aapt2 报错修资源 |
| aapt2 link 失败 | 当前资源表无法继续增量 link | 执行一次 Gradle 构建 |
| 修改 Manifest 后异常 | Manifest 变化依赖完整合并逻辑 | 回退 Gradle 验证 |
| 修改 layout 后源码也编译 | ViewBinding/DataBinding 或 `R.java` 触发 | 正常现象 |
| dynamic feature 资源异常 | base 与 feature 资源表不一致 | 优先 Gradle 重建基线 |

更多资源链路说明见[资源编译](../capabilities/compile/resource-compile.md)。

## release 增量后运行 crash

release 或 minified 构建下，Jugg 需要让增量产物和已安装 APK 的混淆结果保持一致。如果运行时出现以下异常，需要优先怀疑混淆映射一致性：

- 注解查找失败。
- `NoClassDefFoundError`。
- `NoSuchMethodError`。
- `IllegalAccessError`。
- `AbstractMethodError`。
- `IncompatibleClassChangeError`。

排查建议：

1. 保存 crash log 和 `compile_latest.log`。
2. 在日志中搜索 `Obfuscated:`，确认本轮是否执行重混淆。
3. 对比 crash 中的类名、方法名和 mapping 文件。
4. 如果问题只在 release 增量出现，先执行一次 Gradle release 构建验证是否恢复。

## 什么时候可以清理状态

只有在你已经保存现场后，才建议尝试清理 Jugg 状态。

可优先尝试：

```text
build/jugg/database/deploy_history.db/
```

这会让 Jugg 重新建立部署历史。不要在没有备份的情况下删除整个 `build/jugg/`，否则会丢失日志、数据库和现场产物。

## 提交问题时附带什么

请尽量附带：

- `build/jugg/log/compile_latest.log` 或最新 `compile_*.log`。
- 复现步骤和本轮修改了哪些文件。
- 是否是 debug / release / androidTest。
- 是否多 APK、dynamic feature 或资源混淆场景。
- 如果有运行时 crash，附带完整 crash 堆栈。

## 相关页面

- [增量编译](../concepts/incremental-compile/)
- [编译阶段说明](../guide/compile.md)
- [资源编译](../capabilities/compile/resource-compile.md)
- [限制](../reference/limits.md)
