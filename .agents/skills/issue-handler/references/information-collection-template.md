# Bug Information Collection Template

Use this template only when the available evidence cannot explain the project-specific cause of a bug report. Never use it for a pure feature request. Adapt it to the actual investigation instead of copying placeholders or unsupported claims.

## Opening

Start with one short paragraph containing all of the following:

- The environment and reported scenario that were tested.
- What the reporter's existing evidence does or does not demonstrate.
- Which claimed reproduction steps have supporting logs or artifacts.
- The exact boundary of the available evidence. Do not describe an unverified claim as a reproduced result.
- What project-specific condition remains unknown and why further evidence is needed.

Example structure:

> The supplied evidence shows [observable warning or behavior] in [reported environment], but does not establish [missing cause or transition]. No build, reproduction, or device command was run during this read-only investigation. More project evidence is required to identify the responsible module relationship, resource structure, build step, or runtime cause.

## Evidence Options

Offer these two executable options. Replace every placeholder in the selected launch template with the real Issue URL and the most specific key error supported by the available evidence.

### 1. Upload Jugg Diagnostics

Recommend this first:

1. In Android Studio, double-press Shift and run `Report Jugg Issue`.
2. Select `Upload logs` and reply to the Issue with the Report ID.
3. If upload is unavailable, select `Save locally without uploading` and attach the generated Diagnostics Bundle ZIP to the Issue.

### 2. Run a Local Read-Only Agent Investigation

Give the reporter one short, directly copyable prompt. The linked English guide limits the Agent to reading the existing project evidence and producing an analysis. Both launch templates authorize a separate official Jugg source copy for investigation; application changes, builds, reproductions, ADB, and other device commands remain outside scope.

Use this built-in template when the reporter's primary language is Chinese:

```text
请在当前 Android 工程中调查以下 Jugg 异常，基于现场证据尽可能定位根因。

相关 Issue：
[ISSUE_URL]

关键错误：
[KEY_ERROR]

必读指引：
https://raw.githubusercontent.com/tencentmusic/jugg/main/.agents/skills/issue-handler/references/local-agent-investigation-guide.md

请按指南先定位已有现场材料，再结合 Jugg 知识和源码分析。允许在独立调查目录获取或更新官方 Jugg 源码；用户工程和已有源码仓库保持只读，不运行构建、复现或设备命令，不自动上传材料或提交 Issue。
```

Use this built-in template for all other reporters:

```text
Investigate the following Jugg failure in the current Android project and identify the root cause as far as the available evidence allows.

Related Issue:
[ISSUE_URL]

Key error:
[KEY_ERROR]

Required guide:
https://raw.githubusercontent.com/tencentmusic/jugg/main/.agents/skills/issue-handler/references/local-agent-investigation-guide.md

Follow the guide to locate existing incident evidence, then investigate using Jugg knowledge and source code. Obtaining or updating official Jugg source in a separate investigation directory is authorized. Keep the application project and existing source checkouts read-only. Do not run builds, reproductions, or device commands, or automatically upload material or submit an Issue.
```

## Formatting Requirements

- Keep the opening concise and evidence-based.
- Present diagnostics upload and local read-only Agent investigation as two alternatives.
- Use exactly one built-in launch template according to the reporter's primary language. Do not translate, expand, summarize, or inline the linked guide.
- Keep both built-in templates in this file so the Issue reply does not depend on ad-hoc translation.
- Put the selected launch template in a fenced `text` code block, not a blockquote.
- Keep every URL complete and exposed on its own line inside the prompt code block. Do not use `[label](url)` Markdown links there.
- Do not leave placeholders in the published reply.
- Never ask the Agent to upload a Demo, logs, or analysis report automatically.
- Do not request evidence already supplied and inspected.
