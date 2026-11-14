# interaction 屏幕外后代折叠

## 已确认范围

保留屏幕外分支的首个节点，将后代替换为仅含 `jugg_details` 的伪子节点；提示为 `View out of bounds; descendants omitted in interaction mode.`。保留 className 和其余原字段，不增加 scroll，不叠加视觉字段省略或更激进的语义裁剪。

## 依据

本会话实际读取的知识库文档：

- [00_overview](../../ai_knowledge/00_overview.md)、[99_index](../../ai_knowledge/99_index.md)、[98_code_map](../../ai_knowledge/98_code_map.md)
- [06_testing](../../ai_knowledge/06_testing.md)
- [08_cli_tools_list](../../ai_knowledge/08_cli_tools_list.md)、[08_mcp_tools_list](../../ai_knowledge/08_mcp_tools_list.md)
- [08_mcp_design](../../ai_knowledge/08_mcp_design.md)、[08_mcp_layout_verify_design](../../ai_knowledge/08_mcp_layout_verify_design.md)、[08_mcp_ui_verify_checklist](../../ai_knowledge/08_mcp_ui_verify_checklist.md)
- [10_wiki_authoring](../../ai_knowledge/10_wiki_authoring.md)、[10_wiki_architecture](../../ai_knowledge/10_wiki_architecture.md)

行为 owner 为 `LayoutJsonConverter`；摘要统计由 `LayoutDumpHelper` 负责。核对了 `ViewNode`、`DragonflyHierarchySource`、`ElementFinder`、`ViewTapper`、CLI 入口及现有 converter/action 回归。子树 payload 仅包含子树根，不带所属窗口视口，因此 rootLayout 请求不进行几何折叠。

## 实现边界

- 普通窗口以各自根节点有效 bounds 作为视口；仅正面积、完全不相交的非根节点触发折叠。窗口根始终保留。
- 越界判断先于普通语义裁剪，以保留无真实 ID 的首个越界节点；越界叶节点不添加空占位。
- 后代仍与窗口相交、几何未知或存在 message 时，不折叠该分支。零面积节点本身不触发折叠。
- 伪节点不代表控件，不计入节点/clickable 数量。主动折叠不修改 truncated、warnings 或单位。
- ui 模式与内部完整 JSON 不变；可用 `--mode ui --root-layout <id>` 展开后代。
- 不基于子树自身 bounds 猜测屏幕视口；缺失/无效窗口 bounds 时只执行原有语义裁剪。

## 验证

优化前已有回归通过。新增行为具有独立、稳定的输出契约，沿用 L1 `LayoutJsonConverterTest`、`LayoutDumpMcpToolActionTest`，没有新增生产测试 seam。

- 旧实现上新增四项测试失败：分支首节点/占位、可见或未知几何或诊断后代保护、窗口及子树边界、摘要与内部完整产物。
- 修改后定向测试：converter 7 条、action 20 条、内部 Figma 布局验证 5 条，共 32 条通过；`:idea:compileKotlin` 通过。
- 生产转换器离线回放 6 份样例；每份快照分别生成旧 interaction 与新 interaction，避免跨时间快照比较。原有三个 interaction 文件与旧规则输出一致。
- 所有输入与生产序列化格式逐字节一致；新输出真实节点的非 children 字段均来自原快照；旧输出与窗口相交的节点、诊断及元数据均保留。
- 原始完整 ui 快照回放：首页 60,947 → 31,924 bytes（减少 47.62%）；播放器 26,942 → 14,767（45.19%）；歌单 10,517 → 10,517（0%）。
- 历史 interaction 回放：首页 60,976 → 33,307 bytes（45.38%）；播放器 29,016 → 16,494（43.16%）；歌单 10,517 → 10,517（0%）。历史摘要已提升过孩子，不能恢复此前删掉的越界容器，因此与完整树回放结果不同。
- Review 文件位于用户示例目录的 `review-offscreen/`：before/after JSON、README 和包含输入 SHA-256 的 comparison.json。原始样例未修改，也未加入仓库。
- 未做真机滑动/Compose 验收；未测 Token 降幅。

复现定向回归：

```bash
./gradlew :main:test --tests 'com.sickworm.intellij.jugg.ai.mcp.actions.LayoutJsonConverterTest' --tests 'com.sickworm.intellij.jugg.ai.mcp.actions.LayoutDumpMcpToolActionTest' --tests 'com.sickworm.intellij.jugg.ai.mcp.actions.FigmaLayoutVerifyMcpToolActionTest' :idea:compileKotlin
```

## 文档和交付

同步 MCP/CLI 行为文档、布局设计、代码地图、CLI manual 与 Skill 提示；Skill 1.0.36、CLI 1.0.21，安装 bundle 已检查包含新内容。

Wiki 直接受影响的是 concepts/layout-dump-and-ui-evidence、guide/ui-inspection 和 reference/mcp-tools：原先只有语义裁剪说明，缺少屏幕外折叠、伪节点识别和展开方式。只补充这些页面的中英文镜像，不扩展其他能力页或导航。镜像/链接校验、production build 和六份生成 HTML 内容核查通过。
