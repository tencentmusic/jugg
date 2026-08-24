---
title: Agent Skills
description: Explains how Agent Skills organize editing, compilation, deployment, and verification into a Jugg workflow.
status: active
tags:
  - capability
  - tools
  - agent
---

# Agent Skills

Agent Skills are Jugg workflow entry points for AI coding assistants. They turn “change code -> compile -> deploy -> verify -> continue iterating” in an Android project into an executable process and use Jugg CLI to call compilation, deployment, testing, and runtime observation capabilities inside the IDE plugin.

## Supported tasks

| User task | Current support | Behavior |
|---|---|---|
| Verify regular source, resource, Manifest, or Gradle-related changes | Supported | The Skill requires the current edit to finish before invoking `jugg compile` or `jugg deploy` once |
| Observe UI or runtime state on a device | Supported | Uses CLI commands such as `deploy`, `restart`, `layout-dump`, `view-locate`, `view-inspect`, `tap`, and `wait-logs` |
| androidTest / instrumented test | Supported | Reads `status.data.enabledAndroidTest` first and uses `instrument` after the baseline is ready |
| Verify an automatic run entry point | Supported | After the user explicitly specifies the entry method, the Agent writes verification code and confirms the result with logs or UI tools |
| Install or update Jugg CLI | Supported | Uses the Skill's installation guide and script entry point without requiring the user to find script paths manually |

> [!IMPORTANT]
> An automatic run entry point is not inferred from the codebase. The user must provide the fully qualified method name in the task. Without an explicit entry point, the Agent should use regular compilation/deployment verification or ask the user.

## How the workflow is selected

The Skill first chooses a flow based on the task instead of mixing every capability together:

```text
Install CLI
  -> Installation guide
androidTest or src/androidTest task
  -> Android Test flow
User explicitly provides an auto-run entry
  -> Automatic run-entry verification flow
Other Android code changes
  -> Compilation / deployment flow
```

This routing determines when the Agent needs only `compile`, when it must `deploy` to a device, and when it should run tests through `instrument`.

## Input and output boundaries

- The Skill does not replace Jugg plugin capabilities directly. Actual compilation, deployment, UI inspection, and log waiting use Jugg CLI or MCP.
- The Skill prioritizes the current working directory when resolving a project. It can also use `--project-dir` to specify one explicitly.
- Build-related CLI commands block until a terminal state. The Agent does not need to poll an MCP job itself.
- On failure, read the returned `detail`, log path, and status fields before deciding whether to retry, fall back to a Gradle build, or request remote diagnosis.

## Related pages

- [Jugg CLI](./cli.md)
- [Build and deployment](./cli-build-deploy.md)
- [Android Test](./cli-android-test.md)
- [UI automation](./ui-automation.md)
