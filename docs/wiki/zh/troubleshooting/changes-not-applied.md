---
title: 改动没有生效
description: 处理文件变化未识别、代码或资源仍为旧结果、依赖更新未生效和初始化逻辑未重新执行。
status: active
tags:
  - troubleshooting
  - runtime
  - changes
---

# 改动没有生效

如果 Jugg 显示运行成功，但页面或代码行为仍是旧结果，先判断改动是没有被识别、已经部署但需要重启，还是必须由完整 Gradle 构建更新。

## Q：修改文件后提示 `No file changes` 怎么办？

1. 确认文件已经保存，然后重新运行一次。
2. 如果是修改后立即运行，先取消当前操作，再重新运行，让 IDE 完成文件变化通知。
3. 仍然没有变化时执行 Gradle Sync，再重新运行。
4. Sync 后仍未识别时，执行一次完整 Gradle 构建刷新工程信息和增量基线。

## Q：修改 static、companion、object 或 Kotlin 顶层声明后没有生效怎么办？

Hot Reload 可以替换代码，但不会重新执行已经完成的进程初始化。

使用 Jugg 的 Restart 入口重启 App。终端或 Agent 场景可以执行：

```bash
jugg restart
```

如果当前开发内容频繁依赖启动初始化，可以临时开启 `Always restart app after deployment`。

## Q：修改启动流程或单例初始化后仍是旧状态怎么办？

先重启 App，不需要重新编译。重启会创建新进程并重新执行 Application、路由、登录态、单例和其他一次性初始化逻辑。

如果重启后仍没有生效，再执行一次完整 Gradle 构建对照。

## Q：修改依赖版本后仍然运行旧代码怎么办？

1. 执行 Gradle Sync，让 IDE 和 Jugg 读取新的依赖关系。
2. 只修改了依赖版本时，可以在确认窗中选择 `Find out changed Libraries`，核对变化列表后继续。
3. 修改了插件、variant、source set 或不确定依赖影响时，选择 `Fallback to Gradle`。
4. Gradle 安装完成后，再进行一次小范围源码修改确认增量恢复。

不要在无法确认影响时选择 `Ignore build changes`。

## Q：删除类、资源或 Manifest 节点后旧内容仍然存在怎么办？

删除操作不会在当前增量部署中移除设备上的旧类、资源 entry 或已合并的 Manifest 内容。

执行一次完整 Gradle 构建和安装，让删除真正进入新的 APK。设备状态仍不一致时，再使用 [Clean Reinstall](../guide/clean-data.md)。

## Q：增加或修改注解后生成代码没有更新怎么办？

先检查该 processor 是否在[注解器支持范围](../capabilities/compile/annotation-processors.md)内。未支持的 processor、KSP2 或生成规则变化需要通过 Gradle 重新生成源码。

修改 processor 依赖或配置后，按下面的顺序处理：

1. Gradle Sync。
2. 执行对应 Gradle 构建。
3. 再继续普通源码增量编译。

## Q：修改 XML 或资源后页面仍然是旧结果怎么办？

1. 先离开并重新进入当前页面，或重启 App，排除旧 Activity 和内存状态。
2. 首次启用 DataBinding/ViewBinding、修改相关 Gradle 配置或升级 AGP 时，先 Sync 并完成一次 Gradle 构建。
3. 普通资源修改在 Gradle 后能生效、但 Jugg 仍稳定不生效时，使用[报告问题](../guide/report-issue.md)。

DataBinding/ViewBinding 当前支持增量生成相关源码，不应再按旧资料直接判断为“不支持”。

## 相关页面

- [重启 App](../guide/restart-app.md)
- [降级 Gradle 编译](../guide/downgrade-gradle.md)
- [DataBinding/ViewBinding](../capabilities/compile/databinding-viewbinding.md)
- [资源编译](../capabilities/compile/resource-compile.md)
