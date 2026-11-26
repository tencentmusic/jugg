# Benchmark Prompt Pack Runner

Purpose: Export prompt-only case packs from the benchmark master documents for the agent under test.

The master documents remain in:

- `docs/skills/benchmark/benchmark-cli`
- `docs/skills/benchmark/benchmark-hooks`
- `docs/skills/benchmark/benchmark-instrument`
- `docs/skills/benchmark/benchmark-ui-verify`

The default export locations are:

- `android_demo_project/build/benchmark-packs/cli`
- `android_demo_project/build/benchmark-packs/hooks`
- `android_demo_project/build/benchmark-packs/instrument`
- `android_demo_project/build/benchmark-packs/ui-verify`

## Export

```bash
tools/export_benchmark_prompt_packs.sh all
```

To export one pack:

```bash
tools/export_benchmark_prompt_packs.sh cli
tools/export_benchmark_prompt_packs.sh hooks
tools/export_benchmark_prompt_packs.sh instrument
tools/export_benchmark_prompt_packs.sh ui-verify
```

## Starting the Agent Under Test

Start the agent for the CLI benchmark inside `android_demo_project` and give it only the prompt pack:

```text
Run the cases in build/benchmark-packs/cli/cases.md.
Write the results to build/benchmark-packs/cli/report.md using the format in build/benchmark-packs/cli/README.md.
Do not read docs/skills/benchmark.
```

For the instrument or UI benchmark, substitute `build/benchmark-packs/instrument` or `build/benchmark-packs/ui-verify`.

For the hooks benchmark, start the agent in the current CWD and give it only the prompt pack:

```text
Run the cases in android_demo_project/build/benchmark-packs/hooks/cases.md.
Write the results to android_demo_project/build/benchmark-packs/hooks/report.md using the format in android_demo_project/build/benchmark-packs/hooks/README.md.
Do not read docs/skills/benchmark.
```

The hooks benchmark checks the actual configuration and trigger path. The tested agent must trigger hooks through its own edits, shell commands, raw Gradle commands, and session-ending action. To create Jugg pending changes, cases require isolated source files named like `Hook*Trigger.kt` under `app/src/main/java/com/example/myapplication/`; only the non-sourceset false-block case uses `hook_benchmark_scratch/`. Do not modify existing business code or hook source.

## Acceptance

After the tested agent finishes, a separate evaluating agent reads the benchmark master documents and the tested agent's `report.md`. Score the report against each master's Expected section and scoring rules.
