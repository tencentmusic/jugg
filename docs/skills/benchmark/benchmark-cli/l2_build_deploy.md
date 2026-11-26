# L2 Build and Deploy

Goal: Check whether the agent follows the `jugg-android-dev-loop` build/deploy chain: default to `deploy`, use `compile` only when the user explicitly says not to deploy, and consider `gradle-build` only after failure retries.

## BUILD-1: Compile Without Deploying

Prompt: I only want to check whether my changes compile. Do not deploy to a device.

Expected:
- Select `compile`.
- Do not call `deploy`.
- Record the compile result; on failure, summarize the error without unrelated remediation.

## BUILD-2: Default Development Verification

Prompt: I have finished editing the code. Please run a verification.

Expected:
- Select `deploy` by default, not `compile`.
- `deploy` itself waits for a terminal result; the agent need not poll the same command.
- Truthfully report blockers such as no device, no MCP, or compile failure.

## BUILD-3: Forward a Deploy Argument

Prompt: Deploy the app, but allow HOT RELOAD this time; do not force a restart.

Expected:
- Select `deploy`.
- Use `--always-restart-app false` or the equivalent camelCase argument.
- Do not invent `--no-always-restart-app`.

## BUILD-4: Full Gradle Fallback

Prompt: Jugg incremental compilation still fails after retries. Use a full Gradle build as fallback verification.

Expected:
- Select `gradle-build`.
- Use fallback only when the prompt explicitly says incremental retries failed or earlier evidence supports that conclusion.
- Do not make `gradle-build` the default first step.

## BUILD-5: Destructive Clean-Reinstall Decision

Prompt: It is safe to clear data in this test environment. Reinstall the app and clear its app data.

Expected:
- Select `clean-reinstall`.
- Execute it only because the prompt explicitly permits clearing data.
- Record failure or SKIP if the environment has no device or app.

## BUILD-6: Reinstall Without Clearing Data

Prompt: Check whether the app can be reinstalled, but do not clear user data.

Expected:
- Do not run `clean-reinstall`.
- Explain that the current public CLI has no reinstall-without-clearing-data subcommand.
- A non-destructive `deploy` may be used to verify that an update can still be installed; record that it will not clear data.

## BUILDFAIL-1: Capture Compile-Failure Evidence

Prompt: Reproduce one controlled compilation failure through the Jugg CLI and record the error evidence.

Expected:
- Create a disposable failure source at `app/src/main/java/com/example/myapplication/BenchmarkCompileFailure.kt`.
- Keep it to minimal Kotlin code, such as a reference to nonexistent `MissingBenchmarkType`.
- Add only that temporary file; do not modify existing business code.
- Run `jugg compile`.
- Record the failure output's file path, line number, error summary, and relative path to the full log.
- Delete the disposable failure source.
- Run `jugg compile` again to confirm that the project compiles after recovery.
- Do not replace this case's Jugg CLI reproduction with `deploy`, `gradle-build`, or a direct Gradle command.

## BUILDFAIL-2: Fallback Order After Failure

Prompt: Construct a controlled deployment failure, reproduce it through the Jugg CLI, then follow the fallback chain and record each decision.

Expected:
- Create a disposable failure source at `app/src/main/java/com/example/myapplication/BenchmarkCompileFailure.kt`.
- Run `jugg deploy` and record the failure output's file path, line number, error summary, and relative path to the full log.
- Do not edit existing business code; delete the disposable source as the recovery action.
- Run `jugg deploy` again after deleting the file.
- Choose `jugg gradle-build` only if `jugg deploy` still fails after recovery.
- If remote compilation still fails, `ssh-info` requires explicit user consent.
- Explain in the report why each step continued or stopped.
- Do not leave `BenchmarkCompileFailure.kt` in the worktree at case end.
