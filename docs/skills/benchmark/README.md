# Jugg MCP Benchmark（非 UI 验证工具）

本目录包含 Jugg MCP 工具集（UI 验证工具以外部分）的 LLM 能力评测用例。
UI 验证工具（`layout_verify`、`view-inspect`、`figma_layout_verify`）的评测用例位于 `../benchmark-ui-verify/`。

## 目录结构

```
benchmark/
├── README.md                  # 本文件：执行说明 + 评分说明
├── l1_smoke.md                # L1 冒烟用例（~5 条）
├── l2_ssh_conn.md             # L2 Unit: SSH / 连通性 / 设备（~8 条）
├── l2_media.md                # L2 Unit: 截图 / 录屏 / 布局导出 / 崩溃报告（~12 条）
├── l2_interact.md             # L2 Unit: 应用控制与交互（~14 条）
├── l2_build.md                # L2 Unit: 编译与部署（~15 条）
├── l3_nodev.md                # L3 无设备场景（~16 条）
└── l4_adversarial.md          # L4 对抗用例（~10 条）
```

## 覆盖工具一览

| 工具名称 | 需要设备 | 所在文件 |
|---------|---------|---------|
| `ssh-info` | 否 | `l2_ssh_conn.md` |
| `list_projects` | 否 | `l2_ssh_conn.md` |
| `devices` | 否 | `l2_ssh_conn.md` |
| `screenshot` | 是 | `l2_media.md` |
| `record-start` | 是 | `l2_media.md` |
| `record-stop` | 是 | `l2_media.md` |
| `layout-dump` | 是 | `l2_media.md` |
| `activity-stack` | 是 | `l2_media.md` |
| `crash-report` | 是 | `l2_media.md` |
| `restart` | 是 | `l2_interact.md` |
| `tap` | 是 | `l2_interact.md` |
| `compile` | 否 | `l2_build.md` |
| `deploy` | 否（编译）/是（部署） | `l2_build.md` |
| `gradle-build` | 否（编译）/是（部署） | `l2_build.md` |
| `get_compile_status` | 否 | `l2_build.md` |
| `reinstall` | 否（编译）/是（重装） | `l2_build.md` |

不在本 Benchmark 中的工具（另见 `benchmark-ui-verify/`）：`layout_verify`、`view-inspect`、`figma_layout_verify`（UI 验证专项）；`ui_find` 为内部工具，不在任何 Benchmark 中直接评测。

## 前置条件

所有用例执行前需满足：
1. Android 设备已连接并可通过 `devices` 确认
2. App 已部署（`android_demo_project`），可通过 `activity-stack` 确认
3. `McpTestActivity` 可通过 MainActivity → "MCP Test Page" 按钮进入
4. 无设备场景（`l3_nodev.md`）执行前需先通过 `adb emu kill` 关闭所有模拟器

## 评分说明

所有用例共用以下 5 分制评分模型：

| 分数 | 判定标准 |
|------|---------|
| 5 | 调用序列完全正确 + 关键参数正确 + 结论正确 |
| 4 | 调用序列正确，宽松参数有偏差（多余/缺失可选参数）+ 结论正确 |
| 3 | 调用了正确工具，但顺序/次数有偏差，结论基本正确 |
| 2 | 调用了非预期工具，但结论凑对 |
| 1 | 工具调用方向性错误（调用已废弃工具，或关键参数完全错误） |
| 0 | 未调用任何工具，或崩溃，或完全跑偏 |

## 各级别说明

| 级别 | 文件 | 用例数 | 覆盖点 |
|------|------|--------|--------|
| L1 Smoke | `l1_smoke.md` | ~5 | 五类工具各通一次，基本返回正确 |
| L2 Unit | `l2_ssh_conn.md` | ~8 | SSH 同意/拒绝/参数校验，连通性，设备列表 |
| L2 Unit | `l2_media.md` | ~12 | 截图、录屏开始/停止、布局导出、崩溃报告各场景 |
| L2 Unit | `l2_interact.md` | ~14 | restart_app（含 tap_actions）、tap 三种模式及边界 |
| L2 Unit | `l2_build.md` | ~15 | 正常编译/部署、编译失败错误信息、build.gradle 降级、长耗时异步 |
| L3 | `l3_nodev.md` | ~16 | 无设备时所有工具的正确行为（应成功 or MCP_NO_DEVICE） |
| L4 Adversarial | `l4_adversarial.md` | ~10 | 设备选择策略、参数边界错误、端到端组合流程 |

总计：~80 条

## 执行说明

- `l2_ssh_conn.md`（SSH 用例）必须最先执行，因为 `ssh-info` 会触发 IDE 弹窗，需用户在场
- 所有用例严格串行执行（MCP 同时只支持一个客户端调用）
- `l3_nodev.md` 执行前需关闭所有设备，执行后需恢复
- 用例编号格式：`分类前缀-序号`（如 `SSH-1`、`BUILD-3`）
