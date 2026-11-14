# layout-dump JSON 双模式

## 已确认需求

公开产物统一为稀疏 JSON，新增 `mode=interaction|ui`，默认 `interaction`。用户已确认评估方案并授权实施。

- `interaction` 沿用 HTML 的节点裁剪与子节点提升规则，保留窗口根节点、真实 ID、文本、描述、可点击及诊断节点；保留输出节点的虚拟 ID 与已有非默认属性。
- `ui` 保留当前采集 JSON 的节点和父子关系，用于 UI 验证、状态排查和辅助还原。
- 两模式共享字段类型及缺省语义，文件包含 `mode`、`units`（bounds/padding 为 dp）、窗口、设备信息、预算 `truncated` 和可选 `warnings`。裁剪不修改 `truncated`。
- `rootLayout`、`includeGone`、`allWindows` 和采集限制保持现有语义。还原仅针对采集结构、文案和几何信息，不承诺完整视觉或源码还原。
- 摘要统计实际输出节点。外层继续返回 `data.file/contentBytes` 和 artifact，不内联大树。

## 改动范围

- `LayoutDumpMcpToolAction.kt`：参数 schema、校验和工具描述。
- `LayoutDumpHelper.kt`：复用采集与单位转换，按模式输出 JSON；内部布局验证继续消费完整采集 JSON。
- 将 `LayoutHtmlConverter.kt` 替换为 `LayoutJsonConverter.kt`：保留现有裁剪谓词，输出共享 JSON 字段；移除 HTML 序列化。
- 更新 converter/action 现有测试；CLI 参数、help、版本和参数测试。
- 更新 `docs/skills/jugg-android-dev-loop/SKILL.md` 和 `references/cli_manual.md`，同时递增 Skill/CLI 版本。
- 同步 AI 知识库工具清单、设计、代码表、CLI 参数；更新直接描述 HTML 产物的 Wiki 中英文镜像页（guide/ui-inspection、capabilities/tools/ui-automation、capabilities/tools/layout-verify、concepts/layout-dump-and-ui-evidence、reference/mcp-tools、reference/cli-commands）。

不更改 App 采集、点击或属性查询逻辑，不新增格式参数或采集策略。

## 验证策略

新接口的 JSON 产物、模式选择、裁剪和保留规则具有稳定可观察结果，通过测试价值门禁。先在现有 action owner 增加失败测试，再修改生产代码。

- L1 converter：根节点保护、无语义节点裁剪和提升、图片节点差异、虚拟 ID、非默认状态、诊断、元数据和输入不变。
- action：默认/显式模式、JSON 路径和字节数、dp 换算、摘要数量、warnings/truncated、非法模式；保留现有 socket/设备错误回归。
- CLI：模式选择及缺值/非法值失败，定向运行参数测试和 Python 兼容检查。
- 定向 Gradle 测试及 `:idea:compileKotlin`；Wiki 镜像/链接检查、production build 和生成页面核查。
- feature-development 要求的只读子 Agent 实现 review，处理有效发现后提交本次改动。

## 执行记录

- 生产实现、CLI/Skill 与文档已同步；额外同步 UI 验证清单以移除旧 HTML 指引。
- 失败证据：新 action 两条模式/校验测试在旧实现失败；CLI 合法 mode 解析测试在旧实现失败。
- 最终定向回归：LayoutJsonConverterTest 3 条、LayoutDumpMcpToolActionTest 18 条、FigmaLayoutVerifyMcpToolActionTest 5 条，全部通过；`:idea:compileKotlin` 通过。
- CLI 模式参数 7 条测试通过；Python 兼容静态检查通过，环境没有 python3.7，未执行严格 runtime 验证。
- Wiki 镜像/链接 validator 和 production build 通过；12 个受影响中英文页面的生成 HTML 均包含模式指引。
- 构建生成的 docs-skills.zip 已检查，含 Skill 1.0.33、CLI 1.0.19 与 --mode 参数。
- 无效 density 继续保留原有 px 数据；units 如实标记 px，避免把未转换数据误报为 dp。
- 未执行真实设备 UI/Compose 验收；App 采集与交互代码未修改，已有定向回归覆盖 artifact 变换与内部消费者。
- feature-development 要求的只读实现 review 已完成，未发现有实际影响、需要修改的问题。
