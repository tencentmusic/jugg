# File change manager（Issue #76）

## 已批准行为

Changed files 标题右侧增加 Manage...，空态 No pending changes 下一行增加 Manual add...，打开同一管理弹窗。候选为当前 DeployFileManager 会话接收过且未删除的文件，重复加入刷新顺序，按加入时间倒序；搜索仅过滤显示，复用现有文件行样式。默认选中当前 Changed files，确认同步勾选状态，取消不写入。手动勾选标记在本次处理生命周期内免于 rollback、可重新编译相同快照。Refresh 后台调用已有 GitFileChangesDetector.updateChangedFiles，保留未确认选择。

## 实现范围

- ChangedFile：手动标记。
- DeployFileStateTracker / DeployFileManager：候选历史、选择应用、标记继承、删除和成功清理；取消已编译文件时丢弃本轮暂存、重建编译器释放 aapt2 有状态资源表，并将剩余已编译输入重新入队，避免旧产物随下轮部署。
- GitFileChangesDetector：rollback 忽略手动输入，普通检测保持原行为。
- JuggControlPanel / Controller / JuggManager：入口、样式复用、刷新接线。
- 新增 FileChangeManagerDialog：搜索、多选、刷新及确认。只在空闲时应用选择，避免编译/部署中改写输入。
- 文档：04_engineering_ide、02_compile_core、98_code_map；中英文 control-panel、changes-not-applied。

## 验证

先在 DeployFileStateTrackerTest 和 GitFileChangesDetectorTest 补失败测试，保护候选顺序/删除、相同快照手动入队、勾选与取消、事件后保留标记、rollback 普通路径和手动路径。复用 ControlPanelControllerTest 验证领域状态；编译 :idea:compileKotlin。弹窗以实际可用 IDE 或 headless Swing 行为验证，不新增源码字符串测试。完成后子代理只读审查。

## 验证结果

- 失败证据：新增文件选择回归在实现前因缺少候选/选择接口和手动标记而编译失败，日志 `/tmp/jugg-file-manager-red.log`。
- 测试价值：候选顺序、重新入队、取消后的暂存一致性、rollback 保护和搜索/刷新中的选择保留都是稳定用户行为。复用已有 owner，无测试专用生产 seam。
- L1：`:main:test --tests com.sickworm.intellij.jugg.deploy.DeployFileStateTrackerTest`，19 个通过。
- L2：`:idea:test --tests com.sickworm.intellij.jugg.project.GitFileChangesDetectorTest`，10 个通过；`:idea:test --tests com.sickworm.intellij.jugg.ide.ui.JuggControlPanelControllerTest`，6 个通过。后者使用真实 Swing 组件和标准确认动作，隔离 IDE 文件系统依赖，覆盖确认前不写入、搜索过滤、跨搜索勾选、刷新保留编辑和接纳新文件。
- 审查修正后，四组定向 owner 在同一命令再次通过：19 + 10 + 6 + 3 = 38 个测试，日志 `/tmp/jugg-file-manager-all-final.log`。
- `:idea:compileKotlin`、`validate_wiki.py --wiki-root docs/wiki`、Wiki production `npm run build`、四个目标 HTML 内容检查和 `git diff --check` 通过。
- 审查发现取消已编译资源还需要释放 aapt2 表：新增 `JuggManagerFullBuildFlowTest.deselectingCompiledInputReleasesCompilerStateButOrdinarySelectionKeepsIt`，实现前以“Canceled compiled inputs must not remain in the cached resource table”失败，随后在已批准的 JuggManager 内复用编译器替换/Disposer 释放链路。
- 未进行真实 Android Studio 点击或设备部署；本次未改编译/部署执行编排，使用领域状态与 Swing 行为验证。候选不持久化；取消勾选仅管理本次待处理内容。

## 审查

首次只读子代理审查发现两项：取消已编译资源时需释放 aapt2 表，以及复选框包装行需要保留完整路径 tooltip。已在原批准实现范围内修正；资源生命周期回归落在已有 `JuggManagerFullBuildFlowTest`，以真实 Disposer 子资源释放为断言，构造新的编译器时隔离外部编译器依赖，不改编译器 owner。

## 状态

用户已批准范围，已完成实现和验证。两项审查问题修正后，子代理二次只读复核通过，未发现新问题。不涉及 Issue 的多仓库 bug 和 CLI 文案修复，不改其他任务工作区内容。


## 交互调整（用户现场反馈）

- 复选框与行选中分离，列表仅允许单行选中；复用一个完整 renderer 面板，每次绘制明确设置当前行的勾选状态。
- 单击文字只选中，只有复选框命中区域切换勾选；双击文字打开对应文件，弹窗及未确认的勾选继续保留。
- 搜索严格匹配可见的“模块名 / 文件名”，不匹配源路径中的 src/main 等隐藏片段。
- 文件名后以灰色显示文件最后修改距现在的时间，小于一小时为 0 min / 5 min 等，之后为 1h / 2h 等。打开或手动刷新时计算，不使用定时更新。
- 空态 Manual add... 改为原生 ActionLink，沿用其他链接的鼠标悬停下划线效果。
- 失败证据：搜索 main 时旧实现因路径片段匹配返回 2 个文件，期望 0；已有 UI owner 新增真实鼠标点击、独立复选框绘制、相对修改时间和双击导航回归。

- 本轮验证：旧路径搜索回归在实现前失败（expected 0, actual 2），调整后 `JuggControlPanelControllerTest` 7 个测试通过，包含独立勾选绘制、单行选中、文字点击不勾选、复选框点击、搜索/刷新保留、0 min/5 min/1h 和双击导航；`:idea:compileKotlin` 随定向测试通过。Wiki 中英镜像/链接校验及 production build 通过。未进行真实 IDE 鼠标验收。
- 本轮只读子代理审查通过，未发现确定问题；最终定向验证日志 `/tmp/jugg-file-manager-adjust-final.log`，7 个 UI 测试再次通过。

## 第二轮现场问题

- 连片高亮：ColoredListCellRenderer 未选中背景为空，放入复用面板后继承上一行的选中背景。此前仅检查单选索引未覆盖绘制结果；现在逐行显式设置面板与复选框背景。回归使用真实 renderer 离屏绘制像素，修复前未选中行绘成蓝色而非白色。
- 双击保留弹窗：移除关闭调用，使用非模态弹窗，使导航后继续操作文件与勾选。现有导航回归补充未销毁、确认前不写入、导航后确认保留勾选；修复前在 Navigation must leave the dialog available 失败。
- 完整构建后残留：Run 收尾刷新可能先于异步增量初始化，后者清空文件状态后没有通知 Panel。沿已有 FullBuildFlow owner 验证真实文件状态 tracker、Manager 初始化与 Controller model 协作；隔离 APK 数据库和 Swing 面板构造，初始化后模型仍残留文件的断言在修复前失败。初始化完成后补刷新，不改变构建期间新文件保留规则。当前工程 compile_latest 日志只有插件初始化，不作为用户所报构建现场；这里由源码调用顺序和定向复现证明窗口。
- 空态灰色：No pending changes 复用 secondaryLabel 的主题辅助文字色。此项为直接样式修复，不为普通 UI 属性新增测试；采用代码对照及编译验证。
- 失败证据日志：`/tmp/jugg-file-manager-remaining-red.log`、`/tmp/jugg-file-manager-build-refresh-red.log`。
- 最终验证：`JuggControlPanelControllerTest` 7 个、`JuggManagerFullBuildFlowTest` 3 个通过（`/tmp/jugg-file-manager-remaining-green.log`），包含编译验证。沿用已有 FullBuildFlow 覆盖构建后的初始化与面板状态同步，不改编译/部署执行编排。Wiki 镜像与链接检查、production build、中英文目标 HTML 内容检查、`git diff --check` 通过。只读子代理复核无确定问题。未进行真实 IDE 点击和设备部署验收。

## 手动刷新清理内容回退

- 现场：android_demo_project 的 2026-11-04 09:32:29 日志明确执行 Git 扫描，CRC 比较选出两个源码文件，build.gradle 已匹配基线而不在结果中。旧 Refresh 仅添加扫描出的变化、清理缺失文件，没有移除已登记但内容恢复的普通文件。
- 最小修复：保留原 updateChangedFiles 补漏语义；为显式刷新组合补漏和现有 rollbackUnchangedFiles，不依赖 HEAD 变化。Manager 在 fileChangeLock 内执行并同步剩余 build file 的依赖状态。手动标记、历史候选和已编译暂存语义保持原约定。
- 测试价值：复用 GitFileChangesDetectorTest，以真实 Git 仓库、DeployHistoryDb 和 DeployFileManager 验证 build.gradle 修改后恢复、HEAD 未变、普通输入移除、手动输入保留和历史候选仍在。旧刷新实现已出现预期列表不匹配的行为失败，日志 /tmp/jugg-manual-refresh-red.log。未修改 demo 文件。
- Wiki 判断：现有 Refresh 操作说明仍准确，候选范围和手动保护规则未变，无需调整用户指南。
- 验证结果：GitFileChangesDetectorTest 11 个、JuggControlPanelControllerTest 7 个定向测试全部通过，包含 Kotlin 编译；git diff --check 通过，日志 /tmp/jugg-manual-refresh-green.log。尚未在运行中的 IDE 重验点击。
- 只读子代理复核通过，未发现确定问题。
