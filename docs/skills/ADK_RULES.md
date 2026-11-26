# ADK_RULES — Agent Dev Kit Skill Content Governance

> **Goal:** Balance context size, attention, and reliable decisions.
> **Scope:** Every file under `jugg-android-dev-loop`.

---

## 1. Hard Budgets

| Layer | Line limit | Reason |
|-------|------------|--------|
| **SKILL.md** (agent entry) | **≤ 200 lines** | The agent loads it in full at activation; longer entries dilute essential instructions. |
| **Individual reference** | **≤ 150 lines** | Loaded on demand, but it shares the context with SKILL.md. |
| **SKILL.md + peak reference load** | **≤ 500 lines** | The simultaneous load is the actual decision context. |

Measure with `wc -l`, counting blank and comment lines. Frontmatter does not count toward the SKILL.md body.

---

## 2. Core vs. Supporting Content

### 2.1 Core Content: Keep in SKILL.md

Content is core when **any** condition applies:

| Criterion | Example |
|-----------|---------|
| **Control flow:** Determines the agent's next step | Pipeline steps, entry gates, checkpoints, failure fallback |
| **Decision rule:** Determines whether or how to act automatically | Auto-apply threshold, retry budget, compile-only branch |
| **Guardrail:** Violation can fail the task or harm the user | No skipped gates; deploy invalidates prior runtime context |
| **Activation:** Determines whether the skill runs | Frontmatter description and skip rule |

### 2.2 Supporting Content: Move to References

Content is supporting when **any** condition applies:

| Criterion | Example |
|-----------|---------|
| **Tool parameter detail:** Needed only when using that tool | MCP input/output fields and response structure |
| **Diagnostic knowledge:** Needed only on error | Entries in `error_patterns.md` |
| **Procedure:** How to perform a particular operation | Detailed Figma/manual UI verification paths |
| **Example or template:** Clarifies but does not govern decisions | Quick example or report template |
| **Policy detail:** Expands a boundary decision | Specific processors unsupported by incremental compile |

### 2.3 Mixed Content

1. **Split:** Keep the decision in one or two SKILL.md lines; move the explanation to a reference.
2. **Point:** Link from SKILL.md to the relevant reference section.
3. **Avoid reverse section dependencies:** A reference must not depend on a SKILL.md section number.

---

## 3. Content Density

### 3.1 SKILL.md Writing Rules

| Rule | Meaning |
|------|---------|
| **One rule per line** | Express each constraint on one line; avoid paragraph-length rules. |
| **Tables over prose** | Use tables for multiple dimensions; avoid nested lists. |
| **Very short code blocks** | Keep SKILL.md code blocks within five lines; move long templates and examples to references. |
| **No rhetorical emphasis** | Avoid tonal labels such as “please note”, “very important”, or “NOTICE”. |
| **Semantic bold only** | Use bold for an action path or required/prohibited condition, not general emphasis. |
| **No repetition** | State a rule once; refer to an earlier gate instead of restating it. |

### 3.2 Reference Writing Rules

| Rule | Meaning |
|------|---------|
| **Self-contained** | A reference should work without reading another reference. |
| **Organize by use moment** | Follow the agent's action point, not a broad technical taxonomy. |
| **Quick lookup first** | Use tables or YAML for tool cards rather than long prose. |
| **Inline examples** | Place an example next to the rule it illustrates. |

---

## 4. Trimming Priority

If SKILL.md exceeds its budget, trim in this order:

| Priority | Content | Action |
|----------|---------|--------|
| **P0 first** | Examples | Move to a reference or remove. |
| **P1** | Report templates | Keep a one-line summary and pointer; move the template. |
| **P2** | Tool usage details | Keep selection criteria; move parameters, responses, and examples. |
| **P3** | Rule explanations | Keep one-line rules; move their explanation. |
| **P4 last** | Control flow and guardrails | Preserve; these govern safe operation. |

---

## 5. Iteration Rules

### 5.1 Questions Before Adding Content

1. **Is it control flow, a decision, or a guardrail?** Put it in SKILL.md; otherwise use a reference.
2. **Will SKILL.md exceed 200 lines?** Remove or move at least as many old lines.
3. **Will a reference exceed 150 lines?** Split or trim it.
4. **Will SKILL.md plus the peak loaded references exceed 500 lines?** Reduce the largest load.

### 5.2 Zero-Sum Rule

Each addition to SKILL.md must be offset by removing or moving at least as much old content. Do not exceed the line budget.

### 5.3 Review Checklist

After each revision, verify:

```
- [ ] SKILL.md body ≤ 200 lines
- [ ] Every reference ≤ 150 lines
- [ ] SKILL.md + peak reference load ≤ 500 lines
- [ ] SKILL.md has no tool parameter details that belong in a reference
- [ ] SKILL.md has no full code example or report template
- [ ] Each rule is stated once
- [ ] Every moved section has a pointer
```
