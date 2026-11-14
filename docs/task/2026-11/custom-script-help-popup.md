# 自定义脚本帮助入口与排版调整

## 问题与证据

用户反馈 3c70cdb0 新增帮助标题占用额外一行、帮助内容拥挤。后续明确要求删除脚本文件内容示例并压缩提示，避免大浮窗覆盖鼠标后因焦点变化跳变；参数外使用直接命令和脚本调用示例，最新要求宽度、字体、颜色及行高对齐 JetBrains 原生提示，允许超过 10 行。检查 `JuggRunSettingsComponent` 确认 `createScriptPanel` 在输入框上方新增标题和 `ContextHelpLabel`，说明依赖连续 `<br>`，只展示调用命令。

## 实施结果

- 问号放在现有 install/sign 开关文字右侧，开关关闭时仍可查看帮助；展开时仅显示输入框。
- 沿用原有 ContextHelpLabel 及 IDE 提示浮窗样式，仅将内部 label 内容设为 HTML，沿用 IDE 默认字体、字号、行高和段落间距；正文渲染时将连续空白折叠为不换行空格，保留作者显式 `<p>` / `<br>` 换行，按最长完整行自然撑宽，不修改全局 `HelpTooltip.maxWidth`；正文使用主题 `ToolTip.infoForeground`，以 `ContextHelp.FOREGROUND` 为缺省色，允许显式换行及超过 10 行，无固定高度、无滚动条。
- 删除脚本文件内容示例、大标题和自定义间距，仅保留参数说明及 macOS/Linux、Windows 调用示例，以原生段落区分；不修改用户 help 文案，仅在公共渲染入口处理自动折行，不增加粗体、等宽标签或字号设置。
- 同步知识库与中英文安装脚本 Wiki 的帮助入口说明。

## 验证

- 测试价值判断：排版和普通 UI 属性属于 L0，不新增锁定实现的自动化断言。现有配置持久化与显隐用例继续由 `JuggRunSettingsComponentTest` 保护，移除对已删除标题的定位依赖。
- `./gradlew :idea:compileKotlin :idea:test --tests 'com.sickworm.intellij.jugg.ide.logic.JuggRunSettingsComponentTest.custom APK*'`：通过，覆盖开关显隐和配置保存/回读。
- 前次整个 `JuggRunSettingsComponentTest`：33 项中 31 项通过；另两项失败为控制面板开关数量预期 9 实际 10，以及协程 mock 的 CoroutineContext 为空，不在本次修改范围。
- 已从实际 ContextHelpLabel 提取 IDE 原生 HelpTooltip.createTipPanel 渲染并检查两份 PNG，确认所有段落均应用主题信息色。正文与命令统一交给原生提示渲染；本次根据截图中的命令折行问题，在公共入口将空白转换为 `&nbsp;`，不改动用户文案，不覆写字体、字号或行高；颜色按原生帮助正文主题键逐段设置，不写死 RGB 值；临时渲染用例未保留。本次用户截图显示无标题提示前景色过亮；检查本机 HelpTooltip 确认无标题使用 `ToolTip.foreground`，帮助正文信息色为 `ToolTip.infoForeground`。段落使用原生 `HelpTooltip.verticalGap`，缺省 4 个逻辑像素；背景使用原生 ToolTip 背景。本次实际渲染检查：安装提示约 270×120、签名提示约 232×104，命令保持单行；尚未在实际 Android Studio 中手动悬停验证主题、缩放和定位效果。
- Wiki 中英文路径/链接校验通过；未改路由、链接和代码块结构。
