# layout-dump 小结果内联与节点换行

## 已确认范围

用户确认落地小结果自动内联、始终保留文件，以及节点级紧凑换行。后续明确本轮只验收 CLI，不要求直接连接 Codex 或 Claude Code 等 MCP 客户端。

- 两种模式共用 16 KiB（16,384 字节）文件阈值，按最终文件 UTF-8 大小判断，包含节点换行。该阈值不代表响应 envelope 或 Token 上限。
- 小结果在 `structuredContent.data.layout` 中返回完整对象；大结果不包含该字段，不输出局部预览。
- `data.file/contentBytes` 与 JSON artifact 始终保留。MCP 文本 content 继续只返回摘要，避免重复传输整棵树。直接 MCP 客户端是否把 structuredContent 提供给模型不在本轮验收范围。
- 文件在 root/children 节点开始前换行，字段和坐标数组紧凑，不增加深层缩进。文件仍是嵌套 JSON，不是 JSONL。
- CLI plain/json 保留 Unicode，布局内联采用紧凑序列化，避免 Python 默认空格重新扩大上下文。
- 不改变节点筛选、模式、ID、字段名、单位换算或采集逻辑，不增加压缩格式、类名字典、连续编号及新的工具参数。

## 依据与行为 owner

已读 `docs/ai_knowledge/00_overview.md`、`99_index.md`、`98_code_map.md`、`08_mcp_layout_verify_design.md`、`08_mcp_tools_list.md`、`08_cli_tools_list.md`、`08_mcp_design.md`、`06_testing.md`、`10_wiki_authoring.md`、`10_wiki_architecture.md`。核对 Skill/CLI manual、既有 JSON 双模式方案、相关 Wiki 指南/参考/原理页及其目录索引。

实现 owner：

- `LayoutJsonConverter`：JSON 结构序列化，只在节点边界增加空白。
- `LayoutDumpHelper`：按最终公开文件大小决定是否附带 layout；内部消费者仍获得未裁剪树。
- `LayoutDumpMcpToolAction`：工具描述说明内联及大文件局部读取。
- `jugglib.print_kv/simple_call`：CLI 可见对象、Unicode 与紧凑输出。
- `McpResultMapper`：沿用已有 structuredContent 与摘要分工，不更改通用协议模型。

复用回归 owner 为 `LayoutJsonConverterTest`（L1 序列化往返）、`LayoutDumpMcpToolActionTest`（公开产物、边界及 MCP 序列化）、`FigmaLayoutVerifyMcpToolActionTest`（内部消费者）和 CLI 现有 `PrintKvTest`。新增断言保护可观察格式、完整性和字节边界，不增加测试专用生产 seam。

## 真实快照与阈值

使用用户指定的 JOOX `build/jugg/mcp_fetch/layout-dump/` 六份历史快照，仅只读；不向 JOOX 增加测试或修改 App。使用编译后的生产 serializer 和 MCP mapper 生成回放响应，经本地 HTTP 服务交给真实 `jugg.py` 子进程，分别运行 plain/json。回放不代表运行中插件或设备的端到端验收。

| 文件 | 原始字节 | 节点换行后字节 | 文件行数 | 是否内联 | CLI JSON stdout Token |
|---|---:|---:|---:|---|---:|
| `layout_1792061509131.json` | 79,949 | 80,694 | 746 | 否 | 114 |
| `layout_1792061513604_interaction.json` | 60,431 | 60,976 | 546 | 否 | 118 |
| `layout_1792061540442.json` | 35,544 | 35,870 | 327 | 否 | 114 |
| `layout_1792061533922_interaction.json` | 28,767 | 29,016 | 250 | 否 | 118 |
| `layout_1792061558926.json` | 13,690 | 13,818 | 129 | 是 | 4,356 |
| `layout_1792061560723_interaction.json` | 10,425 | 10,517 | 93 | 是 | 3,264 |

Token 使用 `o200k_base`，测量最终 CLI stdout。路径来自临时回放目录，摘要固定为 `Layout snapshot replay`，因此精确响应 Token 不代表真实运行中的完整摘要或其他客户端。六份文件换行前后解析结果完全相等；整份文件 Token 增加约 1.6%～2.3%，换行用于局部检索而不是压缩。

初次回放发现 CLI 默认 JSON 空格使小页面 JSON stdout 达到 5,652 / 4,274 Token；布局紧凑输出后分别为 4,356 / 3,264。六份文件的中、大布局仍走文件路径，支持暂缓字段及 ID 压缩的决策。

## 验证记录

- 改动前 CLI 48 条存量测试通过；布局 converter/action 和内部验证 owner 共 26 条通过。同批额外检查的 `McpInvokerToolSuccessTest` 中 version/list-projects 两项在生产修改前已因初始化异常失败，未扩大本任务修复范围。
- 失败证据：新增的内联、边界、节点换行断言共 3 条在旧行为下失败；CLI plain/json 两个分支无法直接看到中文和 emoji。serializer 测试先以原有 `toString()` 行为确认失败。
- 最终 converter/action/内部消费者定向测试共 28 条通过，`:idea:compileKotlin` 通过。
- CLI 测试：主测试目录 `test_jugglib.py` 49 条、`test_cmd.py` 34 条，以及脚本目录 `test_jugglib.py` 23 条通过。
- 16 KiB 精确边界内联；追加一个三字节中文字符后只返回文件；两者都保留完整产物和准确 `contentBytes`。小结果的 JSON 对象通过 MCP 序列化后仍与文件一致，文本 content 不复制树。
- 12 次真实 CLI 子进程回放，每次恰好一次 layout-dump HTTP 请求。小结果校验期间临时移开回放产物文件，CLI 仍能显示与原快照相同的完整对象，且只出现一次；随后恢复文件。
- Python 兼容静态检查通过；环境无 Python 3.7，未执行严格 runtime 校验。
- Wiki 镜像/链接 validator 与 production build 通过，六个对应生成页面均包含新说明。
- 通用 skill-creator validator 不接受本仓库既有 frontmatter `version/date`。保留项目用于自动更新的必需字段，改用版本一致性及产物检查验证，不为通过通用校验删除字段。

## 文档和交付

同步 MCP/CLI 参数与设计、布局验证的数据入口、Skill 主文件和 CLI manual；仅更新 Wiki 的 UI 检查指南、MCP 参考、布局证据原理页及英文镜像。页面路由和标题不变。

Skill 递增至 1.0.35，CLI 递增至 1.0.20。保留文件、局部读取以及 data.layout 优先使用的规则共同写入工具描述和 Skill，避免 Agent 收到内联对象后再次读取整份文件。
