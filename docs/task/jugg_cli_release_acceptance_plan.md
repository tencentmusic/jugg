# Jugg CLI 推广前验收计划

## 1. 目标

在推广 Jugg CLI 前，确认以下三件事：

1. 所有 CLI 子命令在目标环境中可执行，并且失败时有明确、可行动的错误信息。
2. CLI 参数与 MCP tool schema 严格一致，文档、示例、测试和运行时行为不漂移。
3. `jugg-android-dev-loop` skill 及其 references 能正确指导 Agent 使用 CLI，不引入过时命令、错误参数或不安全流程。

## 2. 验收原则

| 原则 | 要求 |
|------|------|
| 以实现为准 | 文档与代码冲突时，以 CLI parser、MCP schema 和 runtime 行为为准。 |
| 先静态对账，再真实执行 | 先用脚本生成命令/参数清单，再跑 live smoke，避免漏命令。 |
| CLI 不创造语义 | CLI flag 必须能机械映射到 MCP 参数；不得新增 CLI-only 默认值、别名或反向 boolean。 |
| skill 按需加载 | `SKILL.md` 只保留路由、决策和护栏；复杂参数与示例放在 references。 |
| 禁止全量测试 | 只跑定向测试和编译验证，避免执行 `./gradlew :main:test` 或 `./gradlew :idea:test` 全量套件。 |

## 3. 验收范围

### 3.1 CLI 命令面

以 `docs/skills/jugg-android-dev-loop/scripts/jugg.py` 和 `scripts/py/cmd` 的真实 parser 为准，至少覆盖正式文档列出的命令：

| 命令 | 验收重点 |
|------|----------|
| `version` | 无 projectDir 时可运行；CLI/plugin 版本输出一致。 |
| `compile` | 自动解析 projectDir；异步 job 轮询到终态。 |
| `deploy` | `alwaysRestartApp` 透传正确；轮询到终态。 |
| `gradle-build` | fallback 语义清晰；轮询到终态。 |
| `clean-reinstall` | 仅用于 clean data 场景；错误信息可行动。 |
| `restart` | 重启后 runtime timestamp 可被后续日志工具使用。 |
| `instrument` | `sourcePath` 必填；`class`/`method`/`runner`/`extras` 透传正确。 |
| `status` | 输出部署状态与待编译摘要。 |
| `layout-dump` | `rootLayout`/`includeGone`/`allWindows` 参数可用。 |
| `view-locate` | `text`/`resourceId`/`contentDesc` selector 规则准确。 |
| `view-inspect` | selector + expressions 映射准确。 |
| `tap` | 元素、坐标、百分比、long-press、swipe 均覆盖。 |
| `devices` | 无设备/有设备输出都可解释。 |
| `activity-stack` | 当前 Activity 栈输出稳定。 |
| `ssh-info` | `reason` 必填，并保留用户同意前置要求。 |
| `wait-logs` | `marker` 必填；`tags`/`timeoutMs` 与 MCP schema 一致。 |

如果 parser 中发现额外命令，必须补入本表并决定：正式支持、隐藏内部命令、或删除。

### 3.2 文档与 skill

| 文件 | 验收重点 |
|------|----------|
| `docs/ai_knowledge/08_cli_tools_list.md` | 子命令、参数、默认值、输出模式与实现一致。 |
| `docs/ai_knowledge/08_mcp_tools_list.md` | MCP 参数 schema 与 CLI 透传关系一致。 |
| `docs/skills/jugg-android-dev-loop/SKILL.md` | 只保留路由、决策、护栏和高频 quick reference。 |
| `docs/skills/jugg-android-dev-loop/references/cli_manual.md` | 参数示例完整且与 parser 一致。 |
| `docs/skills/jugg-android-dev-loop/references/flow_no_auto_run.md` | 无 auto-run entry 场景不会强行要求用户提供 entry。 |
| `docs/skills/jugg-android-dev-loop/references/flow_with_auto_run.md` | deploy、restart、wait-logs 闭环顺序正确。 |
| `docs/skills/jugg-android-dev-loop/references/error_patterns.md` | 失败处理不会绕过 Jugg runtime debug 手册。 |
| `docs/skills/jugg-android-dev-loop/references/guide_install_cli.md` | 安装路径、PATH、wrapper 说明准确。 |

## 4. 验收阶段

### 阶段 A：静态清单生成

目标：得到一份不可手写的真实命令/参数基线。

步骤：

1. 从 CLI parser 生成子命令、flags、required、choices、nargs、默认值清单。
2. 从 MCP `tools/list` 或 registry schema 生成 tool 参数清单。
3. 对比 CLI flag 与 MCP key 的 kebab-case/camelCase 映射。
4. 对比 `08_cli_tools_list.md`、`cli_manual.md`、`SKILL.md` 中出现的命令和参数。

通过标准：

- parser 中每个公开命令都有文档和测试覆盖。
- 文档中每个命令都能在 parser 中找到。
- 除 `-e` 这种明确映射到 `extras` 的兼容入口外，不存在无法机械映射到 MCP 参数的 CLI flag。
- boolean 参数没有 `--no-*` 这类 CLI-only 反向语义。

建议产物：

- `build/reports/jugg-cli-acceptance/cli_inventory.json`
- `build/reports/jugg-cli-acceptance/schema_inventory.json`
- `build/reports/jugg-cli-acceptance/doc_diff.md`

### 阶段 B：CLI 单元测试与参数契约测试

目标：不依赖设备，先证明 parser、参数构造、输出模式和错误处理正确。

定向测试建议：

```bash
python3 -m unittest docs/skills/jugg-android-dev-loop/scripts/py/test_jugglib.py
python3 -m unittest discover docs/skills/jugg-android-dev-loop/tests
python3 -m unittest discover docs/skills/hooks/tests
```

补充覆盖点：

| 类别 | 用例 |
|------|------|
| 全局参数 | `--console=json` 必须放在子命令前；plain/rich/json 输出差异符合文档。 |
| kebab/camel | `--always-restart-app` 与 `--alwaysRestartApp` 等价。 |
| required | 缺 `instrument --source-path`、`ssh-info --reason`、`wait-logs --marker` 时错误明确。 |
| extras | `instrument -e a=b -e c=d` 与 `--extras a=b;c=d` 最终参数一致。 |
| selector | `view-locate`/`view-inspect` 至少需要一个 selector。 |
| tap mode | 坐标、百分比、元素模式互斥或优先级符合文档。 |
| json output | 每个命令在 `--console=json` 下都能输出可解析 JSON，错误也保持结构化。 |

通过标准：

- 所有定向测试通过。
- 每个公开命令至少有一个 parser/build_params 测试。
- 每个 required 参数至少有一个缺参失败测试。

### 阶段 C：无设备 live smoke

目标：验证 CLI 可以连到插件/MCP，并且无设备场景不会给出误导性成功。

前置条件：

- Android Studio/IDE 已打开一个 Jugg 项目。
- Jugg MCP server 已启动。
- 当前 shell 位于 Jugg 项目目录或其子目录。

建议命令：

```bash
python3 docs/skills/jugg-android-dev-loop/scripts/jugg.py version
python3 docs/skills/jugg-android-dev-loop/scripts/jugg.py --console=json version
python3 docs/skills/jugg-android-dev-loop/scripts/jugg.py devices
python3 docs/skills/jugg-android-dev-loop/scripts/jugg.py status
```

通过标准：

- `version` 不依赖 projectDir 也能返回 CLI/plugin 版本。
- `devices` 在无设备时返回可解释状态，不伪装成可部署。
- `status` 能正确反映当前 projectDir 匹配结果。
- 端口缓存缺失、端口错误、projectDir 不匹配时，错误信息指出下一步行动。

### 阶段 D：有设备端到端 smoke

目标：证明推广时最常用的 build/deploy/runtime 闭环可用。

前置条件：

- 至少一台在线设备或模拟器。
- demo app 可启动。
- 有一个可安全执行的轻量修改或已知可编译基线。

建议命令：

```bash
python3 docs/skills/jugg-android-dev-loop/scripts/jugg.py compile
python3 docs/skills/jugg-android-dev-loop/scripts/jugg.py deploy
python3 docs/skills/jugg-android-dev-loop/scripts/jugg.py restart
python3 docs/skills/jugg-android-dev-loop/scripts/jugg.py activity-stack
python3 docs/skills/jugg-android-dev-loop/scripts/jugg.py wait-logs --marker '<known-marker>' --timeout-ms 30000
```

通过标准：

- `compile`/`deploy` 阻塞到终态，不需要用户手动轮询。
- 成功、失败、timeout 三类结果都能被 Agent 从输出中区分。
- `restart` 后 `wait-logs` 的日志起点符合最近 deploy/restart 时间。
- 失败时能指向 `build/jugg/log/compile_latest.log`，不只返回泛化错误。

### 阶段 E：androidTest / instrument 验收

目标：确认 `instrument` 契约适合 Agent 使用，且不会回退成宽泛猜测接口。

建议命令：

```bash
python3 docs/skills/jugg-android-dev-loop/scripts/jugg.py instrument --source-path app/src/androidTest/java/.../FooTest.java
python3 docs/skills/jugg-android-dev-loop/scripts/jugg.py instrument --source-path app/src/androidTest/java/.../FooTest.java --class com.example.FooTest
python3 docs/skills/jugg-android-dev-loop/scripts/jugg.py instrument --source-path app/src/androidTest/java/.../FooTest.java --class com.example.FooTest --method testSomething
python3 docs/skills/jugg-android-dev-loop/scripts/jugg.py instrument --source-path app/src/androidTest/java/.../FooTest.java -e size=large -e clearPackageData=true
```

通过标准：

- `sourcePath` 是主锚点；不引入 package/regex/testPackage 这类需要猜测 target 的参数。
- class/method 推导失败时错误明确，并建议显式传 `--class`/`--method`。
- extras 被正确转成 instrumentation extras。
- 输出包含测试结果或可诊断失败信息。

### 阶段 F：UI observe / interaction 验收

目标：验证 UI 工具不只是 parser 可用，runtime 也能完成一次真实交互。

建议命令：

```bash
python3 docs/skills/jugg-android-dev-loop/scripts/jugg.py layout-dump
python3 docs/skills/jugg-android-dev-loop/scripts/jugg.py layout-dump --include-gone
python3 docs/skills/jugg-android-dev-loop/scripts/jugg.py view-locate --text '<visible-text>'
python3 docs/skills/jugg-android-dev-loop/scripts/jugg.py view-inspect --text '<visible-text>' text visibility width height
python3 docs/skills/jugg-android-dev-loop/scripts/jugg.py tap --text '<visible-text>'
python3 docs/skills/jugg-android-dev-loop/scripts/jugg.py tap --x-percent 50 --y-percent 80 --action swipe --end-x-percent 50 --end-y-percent 20
```

通过标准：

- `layout-dump` 输出文件路径可打开，内容对应当前页面。
- `view-locate` 的 bounds/position/size 单位与文档一致。
- `view-inspect` 对常用表达式返回 value/type。
- `tap` 的元素、坐标、百分比模式行为符合文档优先级。
- runtime 不支持的字段必须从 skill 和文档中删除，不能用历史说明兜底。

### 阶段 G：skill 内容验收

目标：确认 Agent 读 skill 后会做正确的事。

检查项：

| 项目 | 通过标准 |
|------|----------|
| 触发条件 | 只在 Android/Jugg 开发循环场景触发，不误用于普通 Kotlin/Java 项目。 |
| Phase 0 | `projectDir`、`hasAutoRunEntry` 的收集逻辑清晰；不会通过搜索代码猜 auto-run entry。 |
| 路由 | install、no deploy、无 auto-run、有 auto-run 四类路径互斥。 |
| 默认动作 | 默认 `deploy`，只有用户明确 no deploy 才 `compile`。 |
| fallback | deploy 最多重试 3 次，再 `gradle-build`，`ssh-info` 需要用户同意。 |
| 参数说明 | 高频命令在 `SKILL.md` 可快速找到；低频/复杂参数在 `cli_manual.md`。 |
| 内容预算 | `SKILL.md` 正文 ≤ 200 行；单个 reference ≤ 150 行；峰值加载 ≤ 500 行。 |
| 禁止漂移 | skill 中的每个命令示例都能被 parser 接受。 |

建议验证：

```bash
wc -l docs/skills/jugg-android-dev-loop/SKILL.md docs/skills/jugg-android-dev-loop/references/*.md
rg -n "jugg.py|--[a-zA-Z0-9-]+|--[a-zA-Z0-9]+| -e " docs/skills/jugg-android-dev-loop
```

### 阶段 H：安装包与分发验收

目标：确认用户通过插件安装 skill/CLI 后拿到的是同一份已验收内容。

检查项：

| 项目 | 通过标准 |
|------|----------|
| skill zip | 资源 zip 包含最新 `SKILL.md`、references、scripts。 |
| CLI wrapper | `jugg`、`jugg.cmd`、`jugg.py` 都指向同一实现。 |
| auto updater | 更新逻辑能覆盖旧版本 CLI，不残留旧脚本。 |
| hooks | hooks-only 安装不会错误创建 agent 配置；hook 命令参数与测试一致。 |
| setup doc | `agent_setup.md` 的安装说明与实际安装路径一致。 |

建议定向测试：

```bash
./gradlew :idea:test --tests '*JuggCliAutoUpdaterTest*'
./gradlew :idea:test --tests '*JuggHookInstallerTest*'
./gradlew :idea:test --tests '*InstallJuggSkillsDialogTest*'
```

## 5. 发布门禁

| 门禁 | 要求 |
|------|------|
| G1 静态对账 | parser、MCP schema、AI 文档、skill 文档无未解释 diff。 |
| G2 单元测试 | CLI/scripts/hooks/installer 定向测试全通过。 |
| G3 live smoke | 无设备、有设备、UI、androidTest 至少各跑通一轮。 |
| G4 失败体验 | 缺参、无设备、无项目、端口错误、运行失败都有可行动错误。 |
| G5 文档同步 | `08_cli_tools_list.md`、`08_mcp_tools_list.md`、skill references 与实现一致。 |
| G6 分发一致 | 插件安装出的 CLI/skill 与仓库验收版本一致。 |

任一门禁失败，不建议推广。

## 6. 建议执行顺序

1. 先做阶段 A/B，修掉 parser/schema/docs/skill 的静态漂移。
2. 再做阶段 C/D，确认基础 CLI 与设备闭环。
3. 然后做阶段 E/F，确认 androidTest 和 UI 工具这两类高风险命令。
4. 最后做阶段 G/H，确保 Agent 读取的 skill 与用户安装到的包没有漂移。

## 7. 最终验收报告格式

```markdown
# Jugg CLI 推广验收报告

## 结论
- 是否建议推广：
- 阻塞问题：
- 非阻塞问题：

## 证据
- 静态 inventory：
- 单元测试：
- live smoke：
- skill 审核：
- 分发审核：

## 命令矩阵
| 命令 | parser | MCP schema | docs | skill | unit test | live smoke | 结论 |
|------|--------|------------|------|-------|-----------|------------|------|

## 待办
| 优先级 | 问题 | 责任文件 | 建议处理 |
```
