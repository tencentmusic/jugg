# Benchmark Prompt Pack Runner

用途：从 benchmark 母版导出被测 Agent 可见的 prompt-only 题目包。

母版仍保留在：

- `docs/skills/benchmark/benchmark-cli`
- `docs/skills/benchmark/benchmark-hooks`
- `docs/skills/benchmark/benchmark-ui-verify`

导出物默认写入：

- `android_demo_project/build/benchmark-packs/cli`
- `android_demo_project/build/benchmark-packs/hooks`
- `android_demo_project/build/benchmark-packs/ui-verify`

## 导出

```bash
tools/export_benchmark_prompt_packs.sh all
```

也可以只导出一份：

```bash
tools/export_benchmark_prompt_packs.sh cli
tools/export_benchmark_prompt_packs.sh hooks
tools/export_benchmark_prompt_packs.sh ui-verify
```

## 被测 Agent 启动方式

CLI benchmark 在 `android_demo_project` 中启动被测 Agent，只给它 prompt pack：

```text
请执行 build/benchmark-packs/cli/cases.md 中的用例。
按 build/benchmark-packs/cli/README.md 的格式把结果写入 build/benchmark-packs/cli/report.md。
不要读取 docs/skills/benchmark。
```

UI benchmark 同理，把路径换成 `build/benchmark-packs/ui-verify`。

Hooks benchmark 在当前 CWD 启动被测 Agent，只给它 prompt pack：

```text
请执行 android_demo_project/build/benchmark-packs/hooks/cases.md 中的用例。
按 android_demo_project/build/benchmark-packs/hooks/README.md 的格式把结果写入 android_demo_project/build/benchmark-packs/hooks/report.md。
不要读取 docs/skills/benchmark。
```

Hooks benchmark 用于验证 Agent hooks 配置和真实触发链路。被测 Agent 必须通过自己的文件编辑、shell 命令、raw Gradle 命令和结束会话动作触发 hooks。需要触发 Jugg pending changes 的源码触发文件必须按 case 要求放在 `app/src/main/java/com/example/myapplication/`，只能新增或修改 `Hook*Trigger.kt` 这类隔离文件；非 sourceset 误阻断验证才使用 `hook_benchmark_scratch/`。不得修改现有业务代码或 hook 源码。

## 验收方式

被测 Agent 跑完后，另一个验收 Agent 读取：

- 母版 benchmark
- 被测 Agent 写出的 `report.md`

再按母版里的 `期望` 和评分标准打分。
