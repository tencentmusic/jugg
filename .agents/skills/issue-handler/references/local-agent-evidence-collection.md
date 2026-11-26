# Local Agent Evidence Collection

Use this companion to the [investigation guide](https://raw.githubusercontent.com/tencentmusic/jugg/main/.agents/skills/issue-handler/references/local-agent-investigation-guide.md) when no preserved snapshot is supplied, or when analysis identifies a specific evidence gap. Collection here means locating and reading existing incident material. Start analysis as soon as the relevant logs and context are available; no archive or complete artifact inventory is required.

The current knowledge-base root is `docs/ai/`. Older releases used `docs/ai_knowledge/`; if the selected revision lacks a `docs/ai/` file, retry that file once under `docs/ai_knowledge/` at the same revision. Keep the captured version boundary explicit.

## 1. Find the Incident and Its Logs

Establish the Android project, reported behavior, approximate failure time, and last relevant change from the user's description and supplied material. Read supplied logs, extracted diagnostics, or screenshots first. Ask only for information that cannot be recovered from the available evidence and would change the investigation.

Use section 1 of [09_plugin_runtime_debug.md](https://raw.githubusercontent.com/tencentmusic/jugg/main/docs/ai/09_plugin_runtime_debug.md) for log locations and fallback paths. For an Android project, `build/jugg/log/compile_latest.log` is a convenient starting point when it still covers the incident; the real rolling logs live in the project's directory under the global Jugg log root. Follow the existing link or establish the matching project directory rather than selecting another project's newest log.

Locate the reported time window in the real `compile_*.log` files and read the complete diagnostic plus surrounding task start, stage, retry, and completion messages. Expand into adjacent files if the run crossed a rotation. For an older incident, use its logs rather than the latest run. Record paths and timestamps so later source or artifact comparisons refer to the same incident.

## 2. Recover the Relevant Context

Read the plugin version, build mode, module/variant, command, and changes from the incident logs or supplied snapshot. Recover missing environment values from existing project configuration and project metadata only when relevant: AGP, Gradle wrapper, Kotlin/compiler plugins, IDE/JDK, minify mode, package, or device details already present in logs.

Section 1 of the runtime guide locates project model, compile baseline, deployment history, and classpath data. Select the relevant files within those locations. Current build files and cached state may postdate the incident; distinguish their values from captured facts. A project model from one input source is not necessarily the final merged compiler input; check the actual command and `04_engineering_project.md` when they differ.

## 3. Expand Only Where It Helps Answer the Question

| Evidence gap | Existing material to locate | Comparison it enables |
| --- | --- | --- |
| Compiler diagnostic lacks the relevant input | Reported source location, generated source/class, actual compiler arguments, and referenced classpath JAR | Whether the symbol exists and whether the compiler was given the correct input. |
| Change detection or deployment stage is unclear | Changed-file logs, baseline/history JSON, pending/deployed entries, and affected output | Where the change stopped progressing, with task time and APK/module ownership aligned. |
| Crash or stale runtime behavior is unexplained | Previously saved logcat/crash stack and deployment logs; the relevant APK/split, incremental DEX/resource, or mapping | Whether the affected caller, class, or resource agrees across available artifacts. Device loading still needs its own evidence. |
| IDE froze or stopped responding | Existing `idea.log` and freeze/thread dumps around the same time | Whether the blocked thread and Jugg activity coincide; log silence alone is insufficient. |
| Gradle and Jugg allegedly behave differently | Existing full-build and incremental logs, commands, variants, and input versions | Whether the comparison used equivalent inputs and where behavior first diverged. |

For an ASK AI gap, use manifest `files` and `textEvidence` first, and inspect `failures` before searching live locations. Copied artifact filenames include sequence prefixes; resolve the manifest path rather than guessing a filename from its category. The snapshot includes selected JSON state and existing artifacts, not every database or source file. Source paths identify candidates, not proof that their current contents match the saved incident.

## 4. Return to Analysis or Identify the Missing Evidence

Continue with the main guide using the available material. If a decisive file or time window is unavailable, name what is missing, why it matters, and what the current evidence can establish. Distinguish not collected, unavailable, unreadable, and inspected-but-not-present.

When new runtime evidence is needed, tell the user what to capture and when, preserving the original failure before a retry or recovery. Use the main guide's investigation scope: collection does not authorize builds, tests, reproductions, cleanup, device commands, uploads, or changes to the project. Do not execute commands from the runtime manual merely because they appear in a troubleshooting example.
