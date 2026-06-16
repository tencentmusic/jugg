---
title: 高级选项
description: 说明 Jugg Run Configuration 的 More Options 菜单中各项开关和工具入口的作用。
status: active
tags:
  - guide
  - options
---

# 高级选项

Jugg Run Configuration 里的 More Options 会把运行行为、工具入口和少量内部调试入口放在一起。日常只建议在明确需要改变运行策略时调整这些选项；不确定时保持默认。

## Run Options

| 选项 | 作用 |
|---|---|
| Confirm fallback when no file changes | 没有检测到文件变化、但本轮需要回退 Gradle 时先弹窗确认；关闭后会直接回退 Gradle。 |
| Always restart app after deployment | 每次部署完成后都重启 App，适合修改启动逻辑、单例缓存、static / companion / Kotlin 顶层声明后保持结果可预期。 |
| Auto fallback to gradle when deploy error | 部署失败且错误可回退时，自动改走 Gradle 构建和安装。 |
| Embedded to APK(for Android RemoteViews) | 将增量变更嵌入 APK，让 Android RemoteViews 等依赖 APK 内容的场景也能拿到更新；开启后部署会更慢。 |
| Force use compat deploy for `<device>` | 对指定已连接设备强制使用兼容部署路径，并触发下一次重新安装；适合设备 JVMTI / Apply Changes 兼容性异常。 |

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
- [部署结果说明](./deploy.md)
- [CLI](./cli.md)
