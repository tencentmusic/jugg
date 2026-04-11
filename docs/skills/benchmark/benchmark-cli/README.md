# Jugg Benchmark - CLI 命令

- 本 benchmark 包含 `jugg-android-dev-loop` skill 全部 CLI 工具的 LLM 能力评测用例。

## 目录结构

```
benchmark-cli/
├── README.md                         # 本文件：执行说明 + 评分说明
├── l2_ssh_device_connectivity.md     # L2 SSH / 连通性 / 设备（~7条）
├── l2_media_observe.md               # L2 截图 / 录屏 / 布局 / 崩溃（~12条）
├── l2_app_interaction.md             # L2 应用控制与交互（~14条）
├── l2_build_deploy.md                # L2 编译与部署（~12条）
├── l3_no_device.md                   # L3 无设备场景（~14条）
└── l4_adversarial_e2e.md             # L4 对抗 + 端到端组合（~10条）
```

## 可用子命令（16 个）

| 类别 | 子命令 |
|------|--------|
| 构建与部署 | `compile`, `deploy`, `gradle-build`, `reinstall` |
| 运行时与观察 | `restart`, `tap`, `screenshot`, `record-start`, `record-stop`, `activity-stack` |
| UI 检查 | `layout-dump`, `view-locate`, `view-inspect` |
| 诊断 | `devices`, `crash-report`, `ssh-info` |

- ⚠️注意：用例中的命令皆为子命令，agent 自行拼接 `python3 scripts/jugg.py $COMMAND`

## CLI 输出格式

所有命令输出 JSON 到 stdout：

```json
{"status": "OK|ERROR", "message": "...", "isFinal": true|false}
```

- `status: OK` + `isFinal: true` → 成功，终态结果。
- `status: OK` + `isFinal: false` → 中间结果；重新执行同一命令继续。
- `status: ERROR` → 失败；读取 `message` 获取原因。

## 评分标准（5 分制，所有用例通用）

| 分 | 判定 |
|----|------|
| 5 | 调用序列完全正确 + 关键参数正确 + 结论正确 |
| 4 | 调用序列正确，宽松参数有偏差 + 结论正确 |
| 3 | 调用了正确命令，但顺序/次数有偏差，结论基本正确 |
| 2 | 调用了非预期命令，但结论凑对 |
| 1 | 命令调用方向性错误（调用已废弃命令，或关键参数完全错误） |
| 0 | 未调用任何命令，或崩溃，或完全跑偏 |

## 各级别说明

| 级别 | 文件 | 用例数 | 覆盖点 |
|------|------|--------|--------|
| L2 SSH/设备 | `l2_ssh_device_connectivity.md` | ~7 | SSH 信息请求、设备列表、参数缺失错误 |
| L2 媒体/观察 | `l2_media_observe.md` | ~12 | 截图、录屏、布局导出、Activity 栈、崩溃报告 |
| L2 交互 | `l2_app_interaction.md` | ~11 | restart、tap 三模式、元素匹配边界 |
| L2 编译部署 | `l2_build_deploy.md` | ~12 | 编译/部署正常与失败、Gradle 回退、长耗时 |
| L3 无设备 | `l3_no_device.md` | ~14 | 无设备下各命令行为验证 |
| L4 对抗+E2E | `l4_adversarial_e2e.md` | ~10 | 错误处理、设备选择、端到端组合 |

总计：~71 条

## 执行顺序说明

- 测试用例需要顺序执行
- `l2_ssh_device_connectivity.md` 中 SSH 用例需用户在场响应 IDE 弹窗，建议优先执行
- `l3_no_device.md` 执行前关闭所有设备，执行后恢复
- 单个文件内的用例串行执行

## ⚠️ 评测边界（AI 必读）

- Agent 扮演**观察者 + 记录者**，不是问题解决者
- 命令成功 → 记录返回值，打分，继续；命令失败 → 直接记录错误，打分，继续
- **禁止对失败命令做任何补救**
- **禁止查找 `jugg` 可执行文件路径**
- **禁止读取或调试 CLI 内部实现**
- **禁止直接调用 MCP**

违反以上任一条，本次用例视为无效（不计分）。

## 测试输出结果模板

每条用例执行完毕后，**必须**按以下格式输出结果，不得省略任何字段。

### 单条用例结果

```
### [用例ID]: [用例标题]
- 执行命令：`python3 scripts/jugg.py <subcommand> [args]`
- CLI 输出：`{"status": "...", "message": "...", "isFinal": ...}`
- 调用序列：[按执行顺序列出所有命令，如多次调用则逐条列出]
- 结论：[通过 / 失败 / 跳过] — [一句话说明实际结果与预期的吻合情况]
- 得分：[0-5] / 5
- 备注：[可选，仅在结果有歧义或需补充说明时填写]
```

### 完整评测汇总

所有用例执行完毕后，输出以下汇总表：

```
## 评测汇总

| 文件 | 用例ID | 标题 | 得分 | 备注 |
|------|--------|------|------|------|
| l2_ssh_device_connectivity.md | SSH-1 | 请求 SSH 信息 - 用户同意 | 5/5 | |
| l2_ssh_device_connectivity.md | SSH-2 | 请求 SSH 信息 - 用户拒绝 | 4/5 | message 字段未核验 |
| ...  | ...    | ...  | ...  | ... |

**总分：XX / YY**（YY = 执行条数 × 5）
**跳过：Z 条**（原因：[简述]）
```

### 字段填写规范

| 字段 | 规范 |
|------|------|
| 执行命令 | 完整命令行，含所有参数，以反引号包裹 |
| CLI 输出 | 直接粘贴命令的原始 JSON 输出；多次调用则各贴一行 |
| 调用序列 | 按执行先后排列，每条单独一行；若仅一次则填单命令 |
| 结论 | "通过" / "失败" / "跳过"三选一；后接短句说明原因 |
| 得分 | 整数 0–5，依照"评分标准（5 分制）"章节判定 |
| 备注 | 仅在有歧义或特殊情况时填写，其他留空 |

### 示例

```
### SSH-1: 请求 SSH 信息 - 用户同意
- 执行命令：`python3 scripts/jugg.py ssh-info --reason "testing ssh tool"`
- CLI 输出：`{"status": "OK", "message": "host=localhost port=2222 username=jugg", "isFinal": true}`
- 调用序列：`ssh-info --reason "testing ssh tool"`
- 结论：通过 — status=OK，输出含 host/port/username，与预期一致
- 得分：5 / 5

### BUILD-3: deploy 中间态重运行
- 执行命令：
  1. `python3 scripts/jugg.py deploy`
  2. `python3 scripts/jugg.py deploy`
- CLI 输出：
  1. `{"status": "OK", "message": "building...", "isFinal": false}`
  2. `{"status": "OK", "message": "deploy success", "isFinal": true}`
- 调用序列：`deploy` × 2
- 结论：通过 — 首次返回 isFinal=false，重运行后 isFinal=true，符合预期
- 得分：5 / 5
```
