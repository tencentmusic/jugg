---
title: Fall back to Gradle compilation
description: Learn when to make Jugg use Gradle compilation after a no-change run, through the fallback button, or from the dependency-change dialog.
status: active
tags:
  - guide
  - gradle
  - fallback
---

# Fall back to Gradle compilation

Jugg tries incremental compilation first by default. When the current changes need verification through the complete Gradle pipeline, or when you want to refresh the full-build baseline, you can make the current run use Gradle compilation. It is slower, but the result is closer to a native Android Studio Run.

## Run when no files have changed

When you click Jugg Run again without any newly saved changes, Jugg may ask whether to continue with Gradle compilation.

| Choice | Result |
|---|---|
| Yes | Use Gradle compilation for this run, then install and launch the app |
| No | Cancel this run |
| Clean And Reinstall | Clear app data and reinstall the APK |
| Export incremental APK | Export the incremental APK that has already been compiled |

This usually means either that you intentionally want to refresh the baseline or that you clicked Run by mistake. Select No if it was accidental.

## Use the fallback button

When you know you need a full Gradle build, click `(Jugg) Fallback to Gradle Compile` in the IDE. From a terminal or an agent, run:

```bash
jugg gradle-build
```

Use an explicit fallback when:

- You just switched branches or pulled many changes.
- You changed the Gradle plugin, dependencies, source sets, or Manifest placeholders.
- You suspect the incremental result is incorrect and need a Gradle comparison.
- You deleted a class, resource, or Manifest node and need to confirm that the old content is gone.

After Gradle succeeds, Jugg reloads the APK, classpath, mapping, and resource baseline. Subsequent small changes can still use Jugg Run.

## The build-file change dialog

After you modify `build.gradle`, `settings.gradle`, or dependency declarations, Jugg may first display a dependency-change dialog.

| Choice | When to choose it |
|---|---|
| Fallback to Gradle | You are unsure how the dependency change affects the build, or you changed a plugin, variant, or source set |
| Find out changed Libraries | You changed only a library version and want Jugg to try incremental compilation for the changed library |
| Ignore build changes | You know the build-file change is unrelated to the current APK |
| Close the dialog | Cancel this run |

When in doubt, choose Fallback to Gradle. Spending more time here is usually easier than continuing with an inaccurate baseline.

## Related pages

- [Run an app](./run.md)
- [Export an incremental APK](./export-incremental-apk.md)
- [Clear app data](./clean-data.md)
- [Gradle fallback and baseline rebuilding](../concepts/gradle-fallback-baseline.md)
- [Gradle fallback](../capabilities/compile/gradle-fallback.md)
- [Compilation failed](../troubleshooting/compile-failed.md)
- [Changes did not take effect](../troubleshooting/changes-not-applied.md)
