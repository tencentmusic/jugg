---
title: 高级选项
description: 说明 More Options 中日常场景不常用的体验开关、工具入口和测试排查入口。
status: active
tags:
  - guide
  - options
---

# 高级选项

More Options 里有一些低频开关和工具入口。本页只保留日常场景不常用的选项；常用动作放在对应的场景页。

## Run Options

| 选项 | 作用 |
|---|---|
| Confirm fallback when no file changes | 没有检测到文件变化、但本轮需要降级 Gradle 时先弹窗确认；关闭后会直接降级。 |
| Always restart app after deployment | 每次部署完成后都重启 App，适合修改启动逻辑、单例缓存、static / companion / Kotlin 顶层声明后保持结果可预期。 |
| Auto fallback to gradle when deploy error | 部署失败且错误可回退时，自动改走 Gradle 构建和安装。 |

## Tools

| 选项 | 作用 |
|---|---|
| Install Jugg Skills | 安装 Jugg CLI、Agent skill 和 hooks。 |
| Set custom server URL | 设置自定义 Jugg 后台地址，用于内部配置、更新或诊断服务。 |
| Check updates | 主动检查当前插件版本是否有可用更新。 |
| Clean and reset Jugg | 删除 Jugg 本地缓存并重新打开项目，适合缓存状态明显异常时使用。 |

## Function Switches

| 选项 | 作用 |
|---|---|
| Enable quick deploy(skip App startup) | 开启快速部署路径，部分恢复或部署场景可以跳过 App 启动等待；默认开启。 |
| Enable use project Kotlin compiler | 使用项目自身 Kotlin 编译器执行增量编译；默认开启，只有排查 Kotlin 编译器兼容问题时才建议关闭。 |
| Enable backup classpath | 使用备份 classpath 辅助编译稳定性；开启或关闭后会清理部署历史，部分平台或环境下不会显示。 |

## Test Mock Events

这些入口主要用于内部排查，不建议日常运行时使用。

| 选项 | 作用 |
|---|---|
| Mark as project synced and re-init compiler | 模拟项目已完成 Gradle Sync 并重新初始化编译器。 |
| Mark as gradle compiled and re-init compiler | 模拟已经完成 Gradle 构建并重新初始化编译器；可能让 Jugg 状态与真实构建产物不一致。 |

## 相关页面

- [运行 App](./run.md)
- [重启 App](./restart-app.md)
- [降级 Gradle 编译](./downgrade-gradle.md)
- [CLI](./cli.md)
