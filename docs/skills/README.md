# jugg-android-dev-loop — Skill Design Guide

> This document is for skill maintainers. **Agents do not read it at runtime.**
> See [ADK_RULES.md](ADK_RULES.md) for content governance.

---

## 1. Design Goal

Enable an AI agent to use the Jugg CLI for a deterministic Android development loop: change → compile → deploy → verify.

- **Controlled:** Phase 0 collects context variables; Phase 1 routes to a flow with checkpoints.
- **Efficient:** Load references on demand; keep SKILL.md within 200 lines and peak loaded context within 500 lines.
- **Extensible:** Add a reference and update the routing table for a new scenario, without substantially rewriting SKILL.md.

---

## 2. Architecture: Scenario Routing

SKILL.md uses three stages: **Context Interview → Scenario Route → Load Reference**.

```
Phase 0: Collect variables (projectDir, hasAutoRunEntry, enabledAndroidTest)
    ↓
Phase 1: Match scenario → load the primary reference
    ↓
Reference: Execute the flow (steps, checkpoints, error handling)
```

SKILL.md contains routing decisions and shared rules (CLI Quick Reference and Build Fallback Chain). References contain the scenario-specific steps.

---

## 3. Core Decisions

| Decision | Input | Location |
|----------|-------|----------|
| **D1** Whether the skill activates | User mentions Jugg or Android source is being edited | Frontmatter description |
| **D2** Which flow to use | `hasAutoRunEntry`, `enabledAndroidTest`, and user intent | Phase 1 route |
| **D3** Which references to load | Scenario and current step | Phase 1 scenario table |
| **D4** How to handle compile failure | JSON `status`/`message` and retry count | Build Fallback Chain |

---

## 4. Content Structure

### 4.1 File Tree

```
jugg-android-dev-loop/
├── SKILL.md                               ← Agent entry (≤200 lines)
├── README.md                              ← This maintainer guide
├── ADK_RULES.md                           ← Content governance
└── references/                            ← Loaded on demand (each ≤150 lines)
    ├── cli_manual.md                      ← UI/advanced command parameters
    ├── error_patterns.md                  ← Compile/runtime error diagnosis
    ├── flow_android_test.md               ← androidTest / instrument flow
    ├── flow_compile_deploy.md             ← Compile/deploy flow
    ├── flow_with_auto_run.md              ← Flow with an auto-run entry
    ├── guide_write_auto_run_entry_code.md ← Auto-run entry coding guide
    ├── guide_install_cli.md               ← Jugg CLI installation guide
    └── policy_incremental_compile_limits.md ← Incremental compile limits
```

### 4.2 SKILL.md Structure

```
Frontmatter          → Activation conditions and metadata
Phase 0              → Context Interview (variable collection)
Phase 1              → Scenario Route & Load (route and scenario table)
Mandatory Rules      → Shared constraints
CLI Quick Reference  → CLI commands needed across scenarios
Build Fallback Chain → Failure fallback shared by the flows
```

---

## 5. Iteration Guide

Follow [ADK_RULES.md](ADK_RULES.md) for every revision:

1. **Four checks:** Is it control flow, a decision, or a guardrail? Will SKILL.md exceed 200 lines? Will a reference exceed 150 lines? Will peak context exceed 500 lines?
2. **Zero-sum rule:** Offset new SKILL.md content by removing or moving at least as much existing content.
3. **Review checklist:** See ADK_RULES.md §5.3.

### Common Changes

| Scenario | Action |
|----------|--------|
| Add an error pattern | Edit `error_patterns.md` only. |
| Add a high-frequency or essential CLI command | Edit SKILL.md §CLI Quick Reference. |
| Add a low-frequency command with complex parameters | Edit `cli_manual.md` and add a pointer in SKILL.md §UI Commands. |
| Change CLI scripts, help, skill text, or references | Increment `scripts/py/cmd/cmd_version.py` `CLI_VERSION` and this skill's `SKILL.md` `version`/`date`; auto-refresh compares only the SKILL.md version. |
| Add a scenario flow | Add `flow_*.md` and update the Phase 1 route and scenario table. |
| Change routing | Edit SKILL.md Phase 1 and this README §3. |

---

## 6. Design Principles

| Principle | Warning sign |
|-----------|--------------|
| **Load on demand** | Instructions say to read all references first. |
| **Collect context before acting** | Phase 0 is skipped. |
| **Route first** | Steps start without the Phase 1 route. |
| **Keep scenarios separate** | Both flow_compile_deploy and flow_with_auto_run are loaded together. |
| **Keep SKILL.md for routing** | Scenario-specific execution steps are added to SKILL.md. |
