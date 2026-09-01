---
title: Compilation orchestration
description: Explains how Jugg chooses incremental compilation or Gradle within a Run, advances multi-round incremental compilation, and commits the result only after deployment succeeds.
status: active
tags:
  - concept
  - compile
  - scheduling
---

# Compilation orchestration

A conventional Android Run uses Gradle for project configuration, source and resource compilation, APK building, and other work. When Jugg has a trusted build baseline, it can process only the current changes, but the compilation result, the APK on the device, and deployment history must continue to describe the same successful state.

A Run is therefore more than a binary choice between incremental compilation and Gradle compilation. Jugg must also decide whether the current state permits incremental work, continue compiling affected files, and confirm the result only after deployment succeeds. This page explains the complete orchestration flow. See [incremental compilation](./incremental-compile/) for how Java, Kotlin, and resources are compiled.

## Complete compilation orchestration for a Run

```text
start Run
  -> wait for initialization and file change processing to finish
  -> check project information, build target, device state, and change scope
  -> incremental conditions not met: run Gradle or return the current limitation
  -> incremental conditions met: compile the current changed files
     -> write artifacts to staging
     -> affected source or classes that need DEX conversion found: continue to the next round
     -> compilation fails and the failure condition can be changed: retry once
     -> compilation succeeds and the Git follow-up scan finds missed files: run incremental compilation once more
  -> deploy the staging artifacts
  -> deployment succeeds on every target device: commit staging and deployment history
  -> deployment fails: do not commit, then finish with the failure or fall back the entire Run to Gradle
```

This flow has two important boundaries: successful compilation does not mean the current Run has been committed, and compiling again does not necessarily mean retrying a failure. Impact propagation, failure retry, and the Git follow-up scan address three different problems.

## Untrusted states are excluded before incremental compilation

Jugg first waits for initialization and pending file events to complete, then applies a set of short-circuit checks to determine whether incremental compilation is appropriate. If any check matches, later incremental compilation does not run.

| Check result | Current handling |
|---|---|
| The user forces a full build | Run Gradle. |
| Usable project information is missing | Run Gradle to rebuild project information and the build baseline. |
| The build target changes between app and androidTest | Run Gradle to establish the baseline for the corresponding APKs. |
| There are no file changes to process | Finish the compilation stage without creating empty incremental artifacts. |
| The target device or deployment state does not support incremental work | Return the corresponding limitation; switch to Gradle when a rebuild is required. |
| Too many files or modules are involved | Abandon incremental compilation for the current Run before the local scope expands further. |
| A build file changed | Refresh project information first. Continue only if the dependency change can still be handled by the incremental flow; otherwise run Gradle. |

If no build file changed, the refreshed project information describes the current project and can be used for the incremental decision. If a build file changed, the refresh still provides current project information, but it cannot prove that the existing APK baseline remains valid. The orchestration layer must also use the dependency change result to decide whether incremental work can continue. See [project information refresh](./project-info-refresh.md) for the detailed rules.

The decision stage also filters files against deployment history. A file reverted to content already deployed on the device is removed from the current change set to avoid redundant compilation and deployment.

## Compilation artifacts enter staging first

After incremental compilation begins, each compilation path writes the current artifacts to a staging area instead of immediately changing deployed state. Staging passes through three points within a Run:

1. New artifacts are added to staging after each compilation round.
2. After all additional compilation finishes, deployment consumes valid staging artifacts.
3. Staging and deployment history are committed only after every target device deploys successfully.

Multi-APK projects must preserve artifact ownership at this stage. Resources, Manifest, assets, and similar artifacts carry the set of APKs they affect. If one module belongs to multiple APKs, its artifacts must enter each target deployment set. Incorrect ownership can omit an artifact or send it to the wrong APK.

## Why compilation can continue after a successful round

Compiling only directly changed files can leave stale callers behind. For example, after a method is deleted or a field type changes:

```text
A deletes a method or changes a field type
  -> unchanged B is not included in the first compilation round
  -> local compilation of A succeeds
  -> old B still references the original member
  -> NoSuchMethodError or NoSuchFieldError may occur after deployment
```

To prevent this structural inconsistency from reaching the device, Jugg compares old and new class structures after each successful round and queries references, adding affected source files or classes that need DEX conversion to the next round. This continues until no new impact is found, compilation fails, or the propagation scope is no longer suitable for incremental compilation.

Within the same Run, an impact already processed is not added again, and files introduced by propagation are not mistaken for files originally edited by the user. See [recompilation](./incremental-compile/recompile-propagation.md) for the mechanism.

## Three forms of additional compilation solve different problems

| Mechanism | Trigger | Count and result |
|---|---|---|
| Impact propagation | The current round succeeds but finds new affected source files or classes that need DEX conversion | Can continue for multiple rounds until the impact converges, compilation fails, or the scope exceeds incremental limits. |
| Failure retry | The current round fails, and refreshing file changes or compilation context can change the failure condition | Retries at most once; if it still fails, return the failure or fall back to Gradle. |
| Git follow-up scan | An asynchronous scan completed during incremental compilation finds new uncompiled files | Runs incremental compilation once more after the current incremental pass succeeds; does not repeat if no new files are found. |

Failure retry applies only to known recoverable cases. A missing symbol, for example, may come from file changes missed while the project was closed or after a branch switch; refreshing Git changes can supply those inputs. If a dependency class is missing, refreshing the compilation context may repair the classpath. A retry must change the failure condition and never repeats the same operation unchanged.

Not every failure is retried first. An unrecognized or locally unrecoverable exception can proceed directly to the fallback decision. If a recoverable failure remains after retry, Jugg returns the current error or runs Gradle to rebuild the baseline according to the failure result.

## The result is committed only after deployment succeeds

After incremental compilation finishes, deployment applies the staging artifacts to the target devices. Only after every target device succeeds are staging, deployed-file state, and deployment history committed together.

If deployment fails, these states are not committed. Otherwise, the next Run would assume that failed artifacts had already been deployed and calculate changes from an incorrect baseline. After a deployment failure, the orchestration layer either ends the Run while preserving the error or falls back the entire Run to Gradle, depending on whether a full build can recover the failure.

## Orchestration boundaries

- Jugg incremental compilation depends on a trusted Gradle APK baseline. Gradle is required when changes to project configuration, dependencies, annotation processors, or generated code cannot be validated from the current incremental result.
- Impact propagation is not unlimited recompilation. If the scope grows too large or an intermediate round fails, incremental compilation stops and enters the corresponding failure or fallback handling.
- A successful compilation pass only means that artifacts can enter staging; it does not mean device state and deployment history have been updated.
- See [Gradle fallback and baseline rebuild](./gradle-fallback-baseline.md) for the complete Gradle fallback conditions and baseline update rules.

## Related pages

- [Project information refresh](./project-info-refresh.md)
- [Project context](./project-model.md)
- [Incremental compilation](./incremental-compile/)
- [Recompilation](./incremental-compile/recompile-propagation.md)
- [Deployment data and impact analysis](./deploy-data-and-impact.md)
