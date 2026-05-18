# Jugg Benchmark - instrument 命令

用途：交给不同 Agent，用同一套步骤自动化测试 `jugg instrument` 命令的正确使用。

本目录是 Agent 行为 benchmark，不是操作手册。每条用例用自然语言描述任务，评估 Agent 是否能根据 `jugg-android-dev-loop` skill 正确选择 `instrument`、组装参数、处理前置条件不足并记录证据。

## 真相源

- Skill 入口：`docs/skills/jugg-android-dev-loop/SKILL.md`
- CLI 参数清单：`docs/ai_knowledge/08_cli_tools_list.md`（§2 `instrument`）
- androidTest 支持指南：`docs/ai_knowledge/06_android_test.md`
- Android 测试工程：`android_demo_project`
- 已有 androidTest 源文件：
  - `app/src/androidTest/java/com/example/myapplication/AppLogicInstrumentedTest.kt`
  - `app/src/androidTest/java/com/example/myapplication/AppUiInstrumentedTest.kt`
  - `library1/src/androidTest/java/com/example/library1/Library1LogicInstrumentedTest.kt`
  - `library1/src/androidTest/java/com/example/library1/Library1UiInstrumentedTest.kt`

## 执行前提

Agent 必须在 `android_demo_project` 或其子目录中执行 CLI。仓库根目录只用于读取 skill 与 benchmark 文档，不是 Android projectDir。

禁止在 benchmark 文档和报告中写入本机绝对路径；路径一律使用相对路径，例如 `android_demo_project`、`docs/skills/jugg-android-dev-loop`。

每条 case 默认是独立任务，不得依赖上一条 case 的测试结果、日志或临时文件。case 自己负责准备前置、执行验证、清理副作用。

## instrument 命令参数

```
jugg instrument --source-path <src/androidTest/.../FooTest.kt>
                [--class <Fqcn>] [--method <method>] [--runner <runnerFqn>]
                [--extras <k=v;k2=v2>]
```

| 参数 | 必填 | 说明 |
|------|------|------|
| `--source-path` | 是 | androidTest 源文件路径，解析 module 与 test APK 的锚点 |
| `--class` | 否 | 测试类 FQCN；单 class 文件可省略 |
| `--method` | 否 | 测试方法；需已唯一确定 class |
| `--runner` | 否 | instrumentation runner override |
| `--extras` | 否 | 批量 extras（`k=v;k2=v2` 格式） |

## 前置条件

`instrument` 需要以下前置全部满足才能成功执行：

1. **AndroidTest baseline 已建立**：`jugg status` 返回 `enabledAndroidTest=true`
2. **设备已连接**：`jugg devices` 返回非空设备列表
3. **`--source-path` 指向有效的 androidTest 源文件**：文件存在且位于 `src/androidTest/` 下

前置不满足时，Agent 应如实记录 blocker 并 SKIP 或标记预期失败，不得绕过条件。

`jugg status` 返回 `enabledAndroidTest=false` 时的正确处理：
- 停止执行 `instrument`，并提示用户：打开 Jugg App Run Configuration，开启 Android Test / `enableAndroidTest`，执行一次 full build / `gradle-build` 建立 baseline，确认 `status.data.enabledAndroidTest=true` 后再继续。

## Agent 规则

- 只通过 `jugg-android-dev-loop` skill 提供的 Jugg CLI 完成任务。
- 不直接调用 MCP，不调试 CLI 内部实现，不修改 benchmark 用例。
- 需要结构化证据时，可使用 `--console=json`，且全局参数必须放在子命令前。
- 失败时记录现象和输出，不为通过用例而临时修复代码或改环境。
- 条件不足必须明确写 `SKIP` 原因，例如 `no device`、`enabledAndroidTest=false`、`no test APK`、`source file not found`。
- 环境性 `SKIP` 不计入有效总分分母。
- `--source-path` 必须指向 `src/androidTest/` 下的真实文件；不得臆造路径。
- 不允许使用 `adb shell am instrument` 替代 `jugg instrument`，除非 case 明确允许。

## 评分标准

| 分 | 判定 |
|----|------|
| 5 | instrument 参数选择、顺序、前置检查和结论完全正确 |
| 4 | instrument 选择正确，非关键证据或表述有小偏差 |
| 3 | 调用了 instrument，但参数、前置检查或条件判断存在明显瑕疵 |
| 2 | 使用了 instrument 但方向错误（缺 source-path、用 adb 替代 jugg instrument） |
| 1 | 错误 projectDir、跳过关键前置、臆造 source path |
| 0 | 未调用 jugg instrument、直接调用 MCP、报告缺失，或完全跑偏 |

### 扣分规则

- 未检查 `enabledAndroidTest` 前置就直接执行 instrument：最高 3 分。
- `--source-path` 指向非 `src/androidTest/` 路径：最高 2 分。
- 缺少 `--source-path` 仍执行 instrument：最高 2 分。
- 臆造不存在的 source path：最高 1 分。
- 用 `adb shell am instrument` 替代 `jugg instrument`（非允许场景）：最高 2 分。

## 结果模板

每条用例完成后追加：

```markdown
### CASE-ID: 用例标题
- Prompt: 用例中的自然语言任务
- Working dir: `android_demo_project` 或其子目录
- Precondition: 前置是否满足，或 SKIP 原因
- CLI sequence:
  1. `subcommand [args]`
- Evidence: 关键 stdout/stderr 摘要或报告文件相对路径
- Cleanup: 临时文件删除、恢复验证结果；无清理动作时填 N/A
- Verdict: PASS / FAIL / SKIP
- Score: N / 5
- Notes:
```

完整评测末尾追加：

```markdown
## Summary

| File | Case | Verdict | Score | Notes |
|------|------|---------|-------|-------|

Total: XX / YY
Skipped: Z
Effective Total: XX / YY（排除环境性 SKIP）
Blockers:
```

## 可用测试源文件

### app 模块

| 文件 | FQCN | 方法 |
|------|------|------|
| `app/src/androidTest/java/com/example/myapplication/AppLogicInstrumentedTest.kt` | `com.example.myapplication.AppLogicInstrumentedTest` | `targetContextUsesAppPackage`, `appNameComesFromTargetResources`, `extrasReceivesBenchmarkModeAndTimeout`, `extrasHandlesSpecialCharacters` |
| `app/src/androidTest/java/com/example/myapplication/AppUiInstrumentedTest.kt` | `com.example.myapplication.AppUiInstrumentedTest` | `mainActivityShowsTitle`, `mainActivityShowsNavigationButton`, `mainActivityOpensMcpTestPage` |

### library1 模块

| 文件 | FQCN | 方法 |
|------|------|------|
| `library1/src/androidTest/java/com/example/library1/Library1LogicInstrumentedTest.kt` | `com.example.library1.Library1LogicInstrumentedTest` | `targetContextUsesHostAppPackage`, `demoUsersKeepExpectedValues` |
| `library1/src/androidTest/java/com/example/library1/Library1UiInstrumentedTest.kt` | `com.example.library1.Library1UiInstrumentedTest` | `javaDataBindingActivityShowsUserName`, `kotlinDataBindingActivityShowsUserAge` |

## 文件分层

| 文件 | 覆盖点 |
|------|--------|
| `l2_instrument_basic.md` | `--source-path`、`--class`、`--method`、单/多 class 选择 |
| `l2_instrument_advanced.md` | `--runner`、`--extras`、前置条件判断、参数缺失负向验证 |
| `l3_instrument_no_device.md` | 无设备时的 instrument 行为与 skip 判断 |
| `l4_instrument_e2e.md` | 端到端组合：前置检查→编译部署→instrument→结果解析 |
