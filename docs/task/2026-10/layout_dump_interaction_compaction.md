# interaction 布局摘要继续精简

## 已确认范围

在[屏幕外后代折叠](layout_dump_offscreen_folding.md)的基础上，落实 2026-10-16 确认的精简规则：

- 公开虚拟 ID 由 `_vir_id_<hash>` 改为 `_vid_<hash>`。
- interaction 删除 `padding`、`textColor`，保留 `className`。
- interaction 删除 invisible/gone 节点及整个子树，不提升其后代；`includeGone=true` 也不例外。
- `jugg_details` 伪子节点行内输出，文案固定为 `View is off-screen; descendants omitted in interaction mode.`。
- children 至少含一个真实节点时，在 `[` 和 `]` 前换行，末尾 `]}` 单独成行。
- 同步 Skill、CLI 手册、MCP 参数说明；基于指定的完整 UI 快照生成模拟结果和体积对比。

ui 保留已采集的树和视觉字段，仅同步公开 ID 格式与序列化格式。原有语义裁剪、屏幕外保留条件、诊断保护、预算及摘要统计规则不变。不增加 scroll 信息。

## 依据

本会话实际读取的知识库文档：

- [00_overview](../../ai_knowledge/00_overview.md)、[99_index](../../ai_knowledge/99_index.md)、[98_code_map](../../ai_knowledge/98_code_map.md)
- [06_testing](../../ai_knowledge/06_testing.md)
- [08_cli_tools_list](../../ai_knowledge/08_cli_tools_list.md)、[08_mcp_tools_list](../../ai_knowledge/08_mcp_tools_list.md)
- [08_mcp_design](../../ai_knowledge/08_mcp_design.md)、[08_mcp_layout_verify_design](../../ai_knowledge/08_mcp_layout_verify_design.md)、[08_mcp_ui_verify_checklist](../../ai_knowledge/08_mcp_ui_verify_checklist.md)
- [10_wiki_authoring](../../ai_knowledge/10_wiki_authoring.md)、[10_wiki_architecture](../../ai_knowledge/10_wiki_architecture.md)

行为 owner 为 `LayoutJsonConverter` 与 `ViewHierarchyClient`；核对了协议、App 侧虚拟 ID 生成及 selector 使用、`LayoutDumpHelper`、内部 Figma 布局验证和现有回归。

## 实现边界

- 在语义裁剪及屏幕外折叠前删除隐藏子树，避免隐藏孩子被提升或隐藏几何影响折叠；隐藏窗口根对应的整个窗口也删除。
- 公开输出边界统一简写虚拟 ID。App 运行时继续使用 `_vir_id_`，由 `ViewHierarchyClient` 在请求边界仅转换 selector 的 resourceId/rootLayout（包括 target/target2），不替换文本或 getter 表达式。
- dump 的 id/rootLayout、定位候选和命中元素、属性查询目标均使用公开格式；旧 selector 保持可用。无需升级 App 协议或重新生成 APK。
- 转换过程继续深拷贝输入。ui 保留原树及字段；内部 Figma 验证继续消费完整数据。
- 序列化只区分真实节点与仅包含 `jugg_details` 的伪节点；坐标和节点字段继续紧凑输出。

## 验证

优化前 converter/action/Figma/client 四个回归 owner 已通过。新增断言保护稳定的输出和 selector 往返契约，具有独立测试价值；沿用 L1 owner，不增加生产测试 seam。

先在旧实现运行三项新增测试，均失败：隐藏子树与视觉字段、精确换行格式、虚拟 ID selector 往返。修改后定向执行共 45 条通过：converter 9、action 20、Figma 5、client 11；`:idea:compileKotlin` 通过。

```bash
./gradlew :main:test --tests 'com.sickworm.intellij.jugg.ai.mcp.actions.LayoutJsonConverterTest' --tests 'com.sickworm.intellij.jugg.ai.mcp.actions.LayoutDumpMcpToolActionTest' --tests 'com.sickworm.intellij.jugg.ai.mcp.actions.FigmaLayoutVerifyMcpToolActionTest' --tests 'com.sickworm.intellij.jugg.ai.mcp.viewhierarchy.ViewHierarchyClientTest' :idea:compileKotlin --console=plain
```

使用编译后的生产转换器离线回放 `/Users/wormchen/Downloads/test-layout-dump/layout_1792061509131.json`：

| 阶段 | UTF-8 字节 | 真实节点 | 伪节点 |
|---|---:|---:|---:|
| 原始完整 UI | 80,694 | 745 | 0 |
| 最初 interaction | 60,947 | 545 | 0 |
| 上一版 interaction | 31,924 | 272 | 20 |
| 本次 interaction | 28,186 | 259 | 20 |

相对上一版再减少 3,738 字节（11.71%），相对最初 interaction 减少 53.75%。UI 与 interaction 是不同模式，完整 UI 输入仅作为范围参考。未测 Token 降幅或执行真机验收。

- 原文件 SHA-256：`df2d3049408062309f6a2986daa48975d31fce67e873639163923f1fea2dfc12`。
- 最终 interaction SHA-256：`dcd37e5516289b29e4a3d66cdf2a5462186e7ebfa97a50fa742bd3f1caccef12`。
- 独立检查 JSON 往返、所有隐藏子树不被提升、样例无 padding/textColor/旧格式 id、保留字段来自原始可见节点、ui 树及字段仅变化 ID 格式、伪节点文案及换行。原文件及上一轮产物均未修改。
- Review 产物位于示例目录 `review-compact/`：最终 interaction、上一版副本、ui 核对文件、README 和含精确大小及 SHA-256 的 comparison.json；不提交现场快照。

## 文档与交付

同步直接受影响的 AI 知识库、代码地图、MCP 参数、Skill 与 CLI 手册；Skill 1.0.38、CLI 1.0.23，安装 bundle 内容检查通过。

Wiki 的 concepts/layout-dump-and-ui-evidence、guide/ui-inspection、reference/mcp-tools、capabilities/tools/ui-automation 原先将两模式描述为保留输出节点全部非默认字段，且缺少隐藏子树规则与 ID 格式变化，会使现有使用说明失准；已最小范围更新中英文镜像。Wiki 链接/镜像校验、production build 通过，八份生成 HTML 的规则核查通过。

## 顶层元信息顺序补充

按后续 review 要求，公开 interaction/ui 均优先输出 `truncated`、`deviceInfo`，其余字段保持原顺序；保留原值，缺失字段不补造。排序在公共转换边界完成，内联对象和文件一致，内部完整采集文件不调整。

调整前样例从 windows 开始、预算与设备信息在树之后；调整后生产转换器回放的两种输出均以 `{"truncated":false,"deviceInfo":` 开始，解析后的 JSON 内容和 UTF-8 字节数完全不变，comparison.json 已同步新哈希。该改动只涉及简单字段排序，不新增自动化 testcase，沿用 converter/action 两个 L1 回归 owner，共 29 条通过，并以生产回放核对顺序及内容一致性。同步 MCP 知识库；现有用户文档没有承诺字段顺序，操作说明不受影响，不修改 Wiki 或 Skill。
