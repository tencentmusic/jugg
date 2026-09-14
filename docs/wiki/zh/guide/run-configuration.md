---
title: 运行配置与构建变体
description: 说明 Jugg Run Configuration 的生成、Build Variant 同步、CLI/MCP 选择规则和自定义命令边界。
status: active
tags:
  - guide
  - run-configuration
  - variant
---

# 运行配置与构建变体

Jugg Run Configuration 决定本轮使用哪个 App、Gradle 命令、APK 输出、构建目标和远端环境。多 App、多 variant 或自定义 Gradle 参数工程中，选择错误配置会让编译成功却部署到错误产物。

## 配置名相同不代表构建目标相同

同一模块可以同时存在 debug、release、flavor 和 Android Test 目标。Jugg 会根据 Android Studio 提供的可运行目标生成配置，并以实际 Gradle task 区分目标。

常见名称如下：

\`\`\`text
jugg:app
jugg:app:debug
jugg:app:paidRelease
\`\`\`

旧配置若仍显示 \`Unnamed...\`，Jugg 会在重新识别工程后补建可读配置。已有配置中的自定义 Gradle 参数会尽量保留，不会仅因 Build Variant 切换被重置。

Gradle 模块名可以包含点号，例如 \`:zxphone5.0\`。Jugg 自动生成 \`Compile command\` 时直接使用 Android Studio 提供的 Gradle project path：名称内的点号保持不变，多层模块继续使用冒号分隔，组合构建则保留 included build 名称。因此 \`:zxphone5.0\`、\`:feature:app\` 和 \`:SMCommon:app\` 会分别生成对应模块的 assemble task，不会根据 Jugg 配置名反向猜测路径。

## 配置从哪来

Jugg 以 Android Studio 报告的普通 Android Run Configuration 作为配置发现来源。项目打开时，已有 Jugg 配置立即生效；没有配置时，Jugg 按 Android Studio 报告的可运行目标创建，启动阶段不会用 Gradle 工程信息猜测目标。

创建时按 Gradle task 去重：

- 标准 \`assembleVariant\` 与带 \`--offline\` 等附加参数的同一 task 视为同一目标，不重复创建。
- \`deployVariant\`、\`uploadVariant\` 等自定义 task 不等于标准 \`assembleVariant\`，允许标准配置与自定义配置同时存在。
- 多 task 或格式不受支持的命令只按完整命令比较，Jugg 不做包含式推断。
- 已有配置的 \`Compile command\`、\`Output APK name\` 和远端字段不会被覆盖。
- 名称冲突时使用唯一名称，界面显示的名称与共享配置保存的名称一致。

只有能精确解析为单个 \`./gradlew :modulePath:assembleVariant\`、且命令中的 variant 与 Android Studio 报告一致的目标才会被创建；解析不出的目标会被跳过，不会伪造身份。Gradle 工程信息缺少 App 模块不会让已有配置失效，也不会阻止标准目标创建。

## 跟随 Active Build Variant

当 Android Studio 的 Active Build Variant 改变时，Jugg 会查找同一模块对应的新构建目标。

只有当前选中的配置本身是 Jugg 配置时，Jugg 才自动切换选择；如果当前选中的是原生 App、测试或其它配置，不会抢占用户选择。

\`\`\`text
切换 Android Studio Build Variant
  -> 重新读取可运行目标
  -> 为缺失目标创建 Jugg 配置
  -> 当前已选 Jugg 配置时切到同模块新 variant
\`\`\`

目标 variant 已经有自定义 target（例如 \`deployDebug\`）时，Jugg 优先保留当前选择：即使标准配置刚刚创建，也不会抢占用户的选择和共享配置指针。需要切换时手动选择目标配置即可。

切换后第一次运行通常需要 Gradle 构建，因为 APK、classpath、mapping 和工程信息都属于新的基线。

## 在 Jugg 与原生 Run 之间切换

Jugg 配置不会替换或改写原生 App Run Configuration。需要停止使用 Jugg 运行链路时，直接在 Android Studio 中选择原生 App 配置；本轮编译、安装和启动由原生配置负责，Jugg 不会接管。需要恢复增量编译时，再选择对应的 Jugg 配置即可。

原生 Run 可能更新本地构建产物或覆盖设备上已安装的 APK。切回 Jugg 后，Jugg 会重新检查 Gradle 基线和设备部署状态；两者不再匹配时，下一次 Jugg Run 会按检查结果执行 Gradle 构建、安装或状态恢复。这只用于重新对齐 Jugg 的增量起点，不会修改原生 Run Configuration 或工程配置。

## CLI 与 MCP 使用哪个配置

CLI/MCP 没有单独保存一套构建参数。调用时按以下顺序选择：

1. Android Studio 当前选中的 Jugg 配置。
2. 与最近一次完整构建命令和 BuildTarget 匹配的配置。
3. 与最近一次完整构建命令匹配的配置。
4. 第一个可用 Jugg 配置，并在日志中提示回退选择。

因此，在多 App 或多 variant 工程执行 \`jugg deploy\` 前，先在 Android Studio 选中目标 Jugg 配置最稳妥。

## 自定义 Gradle 命令与输出

手工编辑配置时，\`Compile command\` 和 \`Output APK name\` 必须描述同一个构建目标。Jugg 能识别命令中的 Gradle task，并允许附加常用参数；如果 task 或 BuildTarget 改变，会要求重新建立完整构建基线。

自定义 Gradle build directory 也受支持。APK、Kotlin/Java 输出、Manifest、mapping、Android Test 产物和远端同步会基于实际 build directory 解析，不要求固定在模块的 \`build/\` 下。

## 常见误判

| 现象 | 优先检查 |
|---|---|
| CLI 部署到错误 App | Android Studio 当前选中的 Jugg 配置 |
| 切 variant 后仍使用旧 APK | 是否已选择新 variant 对应配置并完成一次 Gradle 构建 |
| 自动生成重复配置 | 两个命令是否实际指向不同 Gradle task |
| 自定义参数消失 | 是否删除并重建了配置，而不是普通 variant 同步 |
| 找不到 APK | \`Compile command\` 与 \`Output APK name\` 是否对应同一产物 |

## 相关页面

- [运行 App](./run.md)
- [Jugg 运行面板](./control-panel.md)
- [工程信息刷新与恢复](../concepts/project-info-refresh.md)
- [运行上下文与无变化结果](../capabilities/tools/run-context-and-no-change.md)
