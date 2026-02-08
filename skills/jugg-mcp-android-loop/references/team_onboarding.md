# Team Onboarding（Jugg MCP Android Loop）

> 目标：让同事在 **5 分钟内**完成 Skill 安装、MCP 客户端配置、闭环验证。

## 1) 前置条件

- 本机已安装并可运行：`python3`
- IDE 已打开目标 Android 工程，且 Jugg 已初始化
- 至少有一个可用设备（真机或模拟器）

---

## 2) 安装 Skill（一次）

在仓库根目录执行：

```bash
bash skills/jugg-mcp-android-loop/scripts/install_skill.sh --force
```

---

## 3) 配置 MCP 客户端（Codex / Claude Code / Gemini）

```bash
python3 skills/jugg-mcp-android-loop/scripts/setup_mcp_clients.py --all --endpoint http://localhost:12320/mcp --server-name jugg
```

如果 MCP 端口不是 `12320`，改成实际端口。

---

## 4) 快速闭环验证（推荐，MCP-only）

```bash
python3 skills/jugg-mcp-android-loop/scripts/jugg_mcp_loop.py \
  --project-dir /ABS/PROJECT/PATH \
  --fallback-clean-reinstall \
  --start-activity .MainActivity \
  --tap-x 540 --tap-y 530 \
  --pre-tap-delay-sec 2.0 --tap-repeat 2 --tap-interval-sec 1.5
```

可选参数：

- 指定设备：`--serial emulator-5554`
- 强制全量链路：`--mode clean_reinstall`
- 增加录屏产物：`--with-record --record-duration 12`

成功标准：

- 输出 JSON 中 `ok=true`
- `artifacts[]` 非空（至少有截图或布局 dump）

---

## 5) 三端验证命令

### Codex

```bash
codex mcp list
```

应看到 `jugg`。

### Claude Code

```bash
claude mcp list
```

应看到 `jugg`。

### Gemini

检查配置文件：`~/.gemini/settings.json` 包含：

```json
{
  "mcpServers": {
    "jugg": {
      "httpUrl": "http://localhost:12320/mcp"
    }
  }
}
```

---

## 6) 常见问题

### `MCP_PROJECT_NOT_INITIALIZED`

原因：目标工程未被 IDE/Jugg 初始化。

处理：
1. 在 IDE 打开工程
2. 等待 Jugg 初始化完成
3. 重跑闭环脚本

### `MCP_NO_DEVICE`

原因：无在线设备或 selected device 不可用。

处理：
1. 连接真机/启动模拟器
2. 先调用 `device_list`（或重跑闭环脚本）确认在线

### 端口探测失败（12320..12329）

原因：MCP 服务未启动。

处理：
1. 确认 IDE 工程已初始化 Jugg
2. 用已有脚本探测：`tools/mcp_e2e.sh --project-dir /ABS/PROJECT/PATH`
3. 明确端口后传 `--port`

### 增量不稳定

处理：使用强一致模式：

```bash
python3 skills/jugg-mcp-android-loop/scripts/jugg_mcp_loop.py \
  --project-dir /ABS/PROJECT/PATH \
  --mode clean_reinstall \
  --start-activity .MainActivity \
  --tap-x 540 --tap-y 530
```

---

## 7) 日常推荐模板

日常开发（快）：

```bash
python3 skills/jugg-mcp-android-loop/scripts/jugg_mcp_loop.py \
  --project-dir /ABS/PROJECT/PATH \
  --fallback-clean-reinstall \
  --start-activity .MainActivity \
  --tap-x 540 --tap-y 530 \
  --pre-tap-delay-sec 2.0 --tap-repeat 2 --tap-interval-sec 1.5
```

回归验证（稳）：

```bash
python3 skills/jugg-mcp-android-loop/scripts/jugg_mcp_loop.py \
  --project-dir /ABS/PROJECT/PATH \
  --mode clean_reinstall \
  --with-record --record-duration 12 \
  --start-activity .MainActivity \
  --tap-x 540 --tap-y 530 \
  --pre-tap-delay-sec 2.0 --tap-repeat 2 --tap-interval-sec 1.5
```

---

## 8) 交付给 Agent 的一句话

“Use `jugg-mcp-android-loop` skill and run a full MCP-only compile/deploy/verify loop on project `<projectDir>` (use `app_start` + `tap`), then return pass/fail and artifact paths.”

---

## 9) 编译失败处理策略（团队统一）

当闭环脚本出现编译/部署失败时，按以下顺序处理：

1. 先看 MCP 返回错误信息：重点看 `steps[]` 对应失败步骤的 `message`。
2. 先做原因排查：
   - 工程未初始化
   - 设备不可用
   - 源码编译错误（未解析符号、语法、导入）
   - Manifest/资源合并问题
   - deploy 阶段失败
3. 如果原因不明确，且没有明确允许自动降级：**停止自动执行并与提需求人确认**。
4. 如果已明确允许自动降级（比如命令带 `--fallback-clean-reinstall`）：优先执行 `force_gradle_compile`（若工具可用），重试 `compile`/`deploy`，仍失败再执行 `clean_reinstall`。

说明：后续会增强两项能力（MCP 错误透传、降级 tools），使上述策略可完全自动化执行。
