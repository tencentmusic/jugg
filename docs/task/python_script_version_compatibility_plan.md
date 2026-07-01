# Python 脚本版本兼容性治理方案

> 创建日期：2026-07-01

---

## 1. 背景

Jugg 当前存在两类 Python 脚本：

| 脚本面 | 路径 | 用途 | 当前风险 |
|--------|------|------|----------|
| Jugg CLI | `docs/skills/jugg-android-dev-loop/scripts/` | Agent 和用户调用 `jugg` 子命令 | 已统一启用延迟注解并完成 Python 3.7 runtime 回归，后续需用兼容性检查防回归 |
| Agent hooks | `docs/skills/hooks/` | Agent start/edit/command/stop hook | 已使用 `from __future__ import annotations`，语法层可兼容 Python 3.7，但仍需要明确声明和回归 |

典型用户报错：

```text
TypeError: unsupported operand type(s) for |: 'type' and 'NoneType'
```

该错误发生在 Python 3.10 之前运行未启用延迟注解的 `bool | None` 时。它不是业务逻辑错误，而是解释器版本不满足脚本最低要求。

---

## 2. 目标

1. 明确每类脚本当前支持的最低 Python 版本，并让代码、文档、安装输出使用同一口径。
2. 在后续改动时自动检测是否破坏最低版本兼容性。
3. 当用户环境 Python 版本过低时，在脚本入口提前给出可读错误，避免 import 阶段抛出晦涩 traceback。

---

## 3. 当前最低版本口径

### 3.1 Jugg CLI

当前声明为 **Python 3.7+**。

依据：

- `scripts/py/*.py` 与 `scripts/py/cmd/*.py` 已统一添加 `from __future__ import annotations`。
- 真实 Python 3.7 runtime 已通过 `py_compile`、`jugg.py --help` 与现有 CLI 脚本测试。
- `tools/check_python_compat.py --target jugg_cli --strict-runtime` 会同时执行静态检查和真实解释器 smoke test。
- 若未来新增 CLI 脚本，必须继续满足 Python 3.7 语法与 import-time 注解兼容。

### 3.2 Agent hooks

当前可按 **Python 3.7+** 口径维护。

依据：

- `docs/skills/hooks/*.py` 均使用 `from __future__ import annotations`。
- hook 脚本依赖的标准库能力以 Python 3.7 为下限可覆盖，例如 `subprocess.run(capture_output=True, text=True)`。
- 仍需用真实 Python 3.7 解释器执行最小 smoke test；单纯 AST 语法解析不足以证明运行时兼容。

---

## 4. 防回归机制

### 4.1 增加兼容性事实来源

新增一个小型 manifest，例如：

```text
docs/skills/python_compat.json
```

建议内容：

```json
{
  "jugg_cli": {
    "path": "docs/skills/jugg-android-dev-loop/scripts",
    "min_python": "3.7"
  },
  "agent_hooks": {
    "path": "docs/skills/hooks",
    "min_python": "3.7"
  }
}
```

后续安装文档、错误提示、CI 检查均从该文件或同等常量生成，避免多处手写漂移。

### 4.2 增加兼容性检查脚本

新增仓库级检查脚本，例如：

```text
tools/check_python_compat.py
```

检查分两层：

1. **静态检查**
   - 扫描目标目录所有 `.py`。
   - 若目标最低版本低于 3.10，禁止在未启用 `from __future__ import annotations` 的文件中使用 `| None`、`list[...]`、`dict[...]`、`tuple[...]` 等会在老版本 import 阶段求值的注解。
   - 使用 `ast.parse(feature_version=(major, minor))` 做语法下限检查，但明确它只覆盖语法，不覆盖注解运行时求值。

2. **真实解释器检查**
   - 如果本机或 CI 存在目标解释器，执行：

```bash
python3.7 -m py_compile docs/skills/jugg-android-dev-loop/scripts/jugg.py docs/skills/jugg-android-dev-loop/scripts/py/*.py docs/skills/jugg-android-dev-loop/scripts/py/cmd/*.py
python3.7 docs/skills/jugg-android-dev-loop/scripts/jugg.py --help
python3.7 docs/skills/jugg-android-dev-loop/scripts/py/test_cmd_status.py
python3.7 docs/skills/jugg-android-dev-loop/scripts/py/test_jugglib.py

python3.7 -m py_compile docs/skills/hooks/*.py
python3.7 -m unittest docs/skills/hooks/tests/test_hook_common_logging.py
python3.7 -m unittest docs/skills/hooks/tests/test_hooks_guard.py
python3.7 -m unittest docs/skills/hooks/tests/test_hook_reminders.py
```

解释器不存在时，本地检查可以降级为静态检查并输出 skip reason；CI 必须提供矩阵解释器。

### 4.3 CI 矩阵

在 CI 增加一个轻量 job：

| 目标 | Python 版本 | 命令 |
|------|-------------|------|
| CLI 最低版本 | 3.7 | `tools/check_python_compat.py --target jugg_cli --strict-runtime` |
| hooks 最低版本 | 3.7 | `tools/check_python_compat.py --target agent_hooks --strict-runtime` |
| 当前主流版本 | 3.12 或 3.13 | 全部脚本 smoke test |

这样可以保证：

- 新增脚本未纳入 manifest 会失败。
- 新增 3.11/3.12 语法会在最低版本 job 中失败。
- 新增 import-time 不兼容注解会在真实解释器 job 中失败。

---

## 5. 用户环境提前报错

### 5.1 CLI 入口前置版本检查

在 `docs/skills/jugg-android-dev-loop/scripts/jugg.py` 顶部、任何 `help_registry` / `jugglib` import 之前加入版本检查：

```python
MIN_PYTHON = (3, 7)

def _ensure_python_version() -> None:
    if sys.version_info >= MIN_PYTHON:
        return
    current = ".".join(str(part) for part in sys.version_info[:3])
    required = ".".join(str(part) for part in MIN_PYTHON)
    print(
        f"jugg: Python {required}+ is required, current Python is {current}. "
        "Please upgrade python3 or adjust PATH so `python3` points to a supported interpreter.",
        file=sys.stderr,
    )
    sys.exit(2)
```

要求：

- 该检查必须放在所有可能触发低版本注解求值的 import 之前。
- 错误信息必须包含 current / required / 修复建议。
- `--console=json` 场景也允许先输出 stderr，因为版本不满足时还无法进入正常 CLI 协议层。

### 5.2 hooks 调用 CLI 时保留原因

`docs/skills/hooks/hook_common.py::read_status_snapshot()` 当前会捕获 `jugg status` 非 0 退出并写 debug log。后续应增强：

- 当 stderr 包含 `Python X.Y+ is required` 时，debug log 保留完整首行。
- stop/command hook 不应因为 CLI 版本不满足而阻塞 Agent。
- 如需用户可见提示，只在不会打断正常编辑流程的 stop retry/system message 场景输出简短原因。

### 5.3 安装与 setup 文档提示

同步更新：

- `docs/skills/install/agent_setup.md`
- `docs/skills/jugg-android-dev-loop/references/guide_install_cli.md`
- `docs/ai_knowledge/08_cli_tools_list.md` 的 CLI 环境要求小节

明确写出：

```text
Jugg CLI requires Python 3.7+.
Agent hooks require Python 3.7+.
The installed command uses `python3`; ensure `python3 --version` satisfies the required version.
```

---

## 6. 已落地：把 CLI 降到 Python 3.7+

本次已按兼容更老系统 Python 的目标完成 CLI 兼容改造：

1. 在所有 CLI 脚本顶部加入 `from __future__ import annotations`。
2. 将 CLI 测试中的 Python 3.10 括号式多 context manager 改为 Python 3.7 可解析的续行写法。
3. 将 `unittest.mock.call.args` 测试断言改为 Python 3.7 兼容的 `call[0]` 取参。
4. 将兼容性 manifest 中 CLI 最低版本声明为 3.7。
5. 用真实 Python 3.7 runtime 完成严格校验。

后续不能在 CLI 脚本中直接使用 Python 3.8+ 语法；类型注解可以继续使用新式写法，但必须保留 postponed annotations。

---

## 7. 落地步骤

### Step 1：声明当前事实

- 新增兼容性 manifest 或常量。
- 文档声明：
  - Jugg CLI：Python 3.7+
  - Agent hooks：Python 3.7+

### Step 2：CLI 3.7 兼容改造与前置诊断

- 在 CLI 脚本统一加入 postponed annotations。
- 修复 CLI 测试中的 Python 3.10 语法和 `unittest.mock.call.args` 使用。
- 在 `jugg.py` 导入 `help_registry` 前检查 Python 版本，低于 3.7 时输出 current / required / 修复建议。

### Step 3：兼容性检查脚本

- 新增 `tools/check_python_compat.py`。
- 覆盖静态检查和真实解释器检查。
- 本地缺少解释器时允许非 strict 模式 skip，CI 使用 strict。

### Step 4：CI 接入

- Python 3.7 跑 CLI compat。
- Python 3.7 跑 hooks compat。
- 当前主流 Python 跑完整脚本 smoke test。

### Step 5：安装与排障文档同步

- setup 文档写清 `python3 --version` 要求。
- runtime debug / CLI 文档增加 “Python 版本过低” 排障项。

---

## 8. 验收标准

| 目标 | 验收方式 |
|------|----------|
| 能确认当前最低版本 | `tools/check_python_compat.py --list` 输出脚本面与 min Python |
| CLI 3.7 可运行 | 在 Python 3.7 中执行 `jugg.py --help`、`py_compile` 与 CLI 脚本测试 |
| hooks 低版本不被 CLI 问题误判 | Python 3.7 job 能 py_compile hooks 并跑 hooks 单测 |
| 后续改动不会悄悄破坏兼容性 | CI 矩阵最低版本 job 必须通过 |
| 用户报错可定位 | debug log 或 stderr 中包含 current Python、required Python、修复建议 |
