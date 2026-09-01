---
title: Run context and no-change results
description: Explains how CLI/MCP selects a Jugg configuration and how to interpret compile or deploy results when no files are pending.
status: active
tags:
  - capability
  - cli
  - mcp
  - deploy
---

# Run context and no-change results

CLI and MCP reuse the Jugg run context from the current Android Studio project. Commands do not carry a complete Run Configuration, so confirming configuration selection and the meaning of “no changes” is important in projects with multiple apps, multiple variants, or a mix of remote and local execution.

## Configuration selection determines the actual build target

CLI/MCP selects a Jugg configuration in this order:

1. The Jugg configuration currently selected in Android Studio.
2. A configuration matching the command and BuildTarget from the latest full build.
3. A configuration matching the command from the latest full build.
4. The first available configuration, with the fallback selection explained in the log.

The selected configuration determines the compile command, APK pattern, BuildTarget, remote compilation arguments, and deployment target. In a project with multiple configurations, explicitly select the target Jugg configuration before calling the command.

## \`executionType\` identifies the Gradle fallback location

\`status\` and compilation tools return \`executionType\`:

| Value | Meaning |
|---|---|
| \`local\` | Runs Gradle locally when needed |
| \`remote\` | Uses the Run Configuration's remote environment when Gradle is needed |

It describes the fallback execution environment and does not mean that the current call has already used Gradle. Determine whether fallback occurred from the final compilation result and logs.

## No pending file changes is a success state

When \`compile\` succeeds without compiled files, no files currently require compilation and the run produced no new compilation artifacts. The command still succeeds and does not deploy.

When \`deploy\` succeeds without compiled files, Jugg has already deployed the changes it currently detects and no new files are pending. This does not mean that the command failed to execute or that it recompiled every file.

The final no-pending message remains the same whether the command finishes in the initial call or after asynchronous polling.

When possible, a \`deploy\` result also includes information about the latest successful deployment in the current IDE session that contained file changes:

- Absolute and relative time.
- Project-relative file paths.
- Up to 20 files, with only the remaining count shown beyond that limit.

This record exists only in the current IDE session. After the IDE restarts, the command still reports that no deployment changes are pending, but details of the latest files may be unavailable.

## Dry deploy and Gradle fallback

When there are no file changes, an IDE Run can use its setting to:

- Fall back to a full Gradle build.
- Cancel the current run.
- Perform a dry deploy that uses existing deployment state to continue startup or validate device-side state.

A dry deploy should not be reported as Gradle fallback. It produces no new compilation artifacts and is used mainly for the first run, switching projects, Debug, or when the user chooses to skip a full build.

When interpreting CLI/MCP results, read all of the following:

- \`isCompileSuccess\`
- \`isDeploySuccess\`
- message / detail
- compiled files

Successful compilation, no new files, and successful deployment can all be true at the same time.

## Guidance for repeated deployments

\`\`\`text
jugg status
  -> Confirm selected device, executionType, and pending files
jugg deploy
  -> Read the terminal compilation/deployment result
  -> When no files are pending, read the latest changed-file deployment summary
\`\`\`

If you are sure a file changed but Jugg still reports no pending changes, save the file, run status with change refresh, and then check the project directory and selected Jugg configuration.

## Related pages

- [Run configurations and build variants](../../guide/run-configuration.md)
- [Build and deployment](./cli-build-deploy.md)
- [CLI commands](../../reference/cli-commands.md)
- [MCP tools](../../reference/mcp-tools.md)
