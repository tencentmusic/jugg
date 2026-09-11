# Issue Information Collection Template

Use this template when the available evidence cannot explain the project-specific cause. Adapt it to the actual investigation instead of copying placeholders or unsupported claims.

## Opening

Start with one short paragraph containing all of the following:

- The environment and reported scenario that were tested.
- What was or was not naturally reproduced.
- Any artificial experiment used to construct the same downstream state.
- The exact boundary of that experiment. Do not describe an artificially constructed state as reproducing the original cause.
- What project-specific condition remains unknown and why further evidence is needed.

Example structure:

> We tested the reported scenario with [matched environment] but did not naturally reproduce [missing output or failure]. We then artificially [constructed condition] and reproduced [observable warning or behavior]. This only verifies behavior after that condition exists; it does not explain why the reporter's project entered that state. More project evidence is required to identify the responsible module relationship, resource structure, build step, or runtime cause.

## Evidence Options

Offer these three executable options. Replace every placeholder with the real Issue number, external URL, failure details, relevant paths, and investigation focus before replying.

### 1. Upload Jugg Diagnostics

Recommend this first:

1. In Android Studio, double-press Shift and run `Report Jugg Issue`.
2. Select `Upload logs` and reply to the Issue with the Report ID.
3. If upload is unavailable, select `Save locally without uploading` and attach the generated Diagnostics Bundle ZIP to the Issue.

### 2. Provide a Reproducible Demo

Give the reporter a prompt that asks their Agent to create and verify a minimal Demo. Let the Agent choose the appropriate extraction or reconstruction strategy for the available project and environment.

```text
Create a minimal Android Demo that reliably reproduces Jugg Issue #[ISSUE_NUMBER]:
[ISSUE_URL]

Review the available project and choose an appropriate way to extract or reconstruct the Demo. Match the reported AGP, Gradle, Kotlin, JDK, Android build configuration, and [REPORTED_SCENARIO]. Keep only the minimum files and module relationships required for reproduction. Ensure the deliverable does not contain accounts, credentials, secrets, unrelated business code, or private dependencies.

Verify the reported steps in the Demo. Run the minimum normal Gradle full-build and Jugg incremental-build checks needed to confirm whether the issue reproduces. If Jugg, a device, or another required dependency is unavailable, record the limitation instead of claiming success.

Add a README containing the environment, complete reproduction steps, expected result, actual result, relevant logs or stack traces, and the difference between normal Gradle and Jugg results. Return the local Demo path and verification result. Do not upload the Demo; the reporter will review and share it.
```

Write the prompt in the reporter's language. Keep the Issue URL exposed on its own line inside the code block.

### 3. Generate a Local Agent Analysis Report

Give the reporter a second directly copyable prompt. It must include both the exposed Issue URL and the exposed Jugg runtime troubleshooting guide URL inside a fenced `text` code block:

```text
Analyze Jugg Issue #[ISSUE_NUMBER] only:
[ISSUE_URL]

First read the Jugg runtime troubleshooting guide:
https://github.com/tencentmusic/jugg/blob/main/docs/ai_knowledge/09_plugin_runtime_debug.md

First construct the reported reproduction scenario. Choose the appropriate reproduction strategy based on the available project and tools, match the reported environment and steps, and run the focused build, deployment, or device checks required to determine whether the issue reproduces. If the required toolchain, dependencies, or device are unavailable, record the limitation.

Then inspect build/jugg/log/compile_latest.log and locate logs related to [RELEVANT_COMPONENTS_OR_SYMBOLS]. Read the relevant generated outputs, source or resource variants, module relationships, and the deepest cause from the complete runtime stack if a crash is involved.

Compare the reproduction results, normal Gradle full-build evidence, and Jugg incremental-build evidence. Produce an analysis report containing the constructed scenario, evidence paths, reproduction results, root-cause assessment, competing explanations, limitations, and missing information. Remove sensitive information before sharing the report publicly.
```

Adapt the inspection targets to the Issue. Do not leave generic placeholders in the published reply.

## Formatting Requirements

- Keep the opening concise and evidence-based.
- Present diagnostics upload, reproducible Demo, and local Agent analysis as three alternatives.
- Let the Agent choose the reproduction workspace and implementation strategy based on the project state and available tools.
- Never ask the Agent to upload a Demo, logs, or analysis report automatically.
- Put both Agent prompts in fenced `text` code blocks, not blockquotes.
- Keep every URL complete and exposed on its own line inside the prompt code block. Do not use `[label](url)` Markdown links there.
- Do not request evidence already supplied and inspected.
