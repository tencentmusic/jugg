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

Hooks benchmark 在仓库根目录启动被测 Agent，只给它 prompt pack：

```text
请执行 android_demo_project/build/benchmark-packs/hooks/cases.md 中的用例。
按 android_demo_project/build/benchmark-packs/hooks/README.md 的格式把结果写入 android_demo_project/build/benchmark-packs/hooks/report.md。
不要读取 docs/skills/benchmark。
```

## 验收方式

被测 Agent 跑完后，另一个验收 Agent 读取：

- 母版 benchmark
- 被测 Agent 写出的 `report.md`

再按母版里的 `期望` 和评分标准打分。
