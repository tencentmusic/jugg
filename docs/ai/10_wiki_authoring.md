# Wiki Article Authoring Guidelines

> Last verified: 2026-08-10
> Consistency rule: If documentation conflicts with code, code takes precedence.

---

## 1. Purpose of This Document

This page is for AI and developers maintaining the Jugg user Wiki. It answers:

- How official user articles should organize page metadata, headings, body text, links, and callouts.
- How official articles should bound implementation detail so they do not become source-maintainer documentation.
- How to use Markdown and engineering-semantic blocks.
- How to synchronize English and Chinese pages.
- Which dedicated standard to read before writing a capability page.

For Wiki development, build, preview, and publication operations, see `10_wiki_architecture.md`.

---

## 2. Page Frontmatter

A Wiki page may use frontmatter for metadata:

```yaml
---
title: Wiki Elements Demo
description: Demonstrates every supported Markdown element for the Jugg Wiki.
visibility: dev
status: draft
tags:
  - dev
  - demo
---
```

Common fields:

| Field | Recommendation | Meaning |
|---|---|---|
| `title` | Recommended | Page title; useful in navigation, search, and future automatic indexes. |
| `description` | Recommended | Page summary; useful in search results. |
| `visibility` | As needed | `dev` means visible only in development. Omit it on official user pages. |
| `status` | As needed | `draft` / `active` / `deprecated` maintenance state. |
| `tags` | As needed | For future search, aggregation, or bulk maintenance. |

Constraints:

- Dev-only pages must set `visibility: dev`.
- Dev-only pages must live under `docs/wiki/dev/` or `docs/wiki/zh/dev/`.
- Official user pages must not use `visibility: dev`.
- When editing an existing page, preserve `frontmatter.title` and H1 by default. Change them only when the user explicitly requests a rename, or when the title clearly conflicts with the page's role and the change has been confirmed. The `description` may change with the body.

---

## 3. Supported Basic Markdown Elements

Prefer native Markdown or elements with stable VitePress support:

| Element | Syntax | Guidance |
|---|---|---|
| Page title | `# Title` | Keep one H1 per page. |
| Nested headings | `##` / `###` / `####` | Define page structure and anchors. |
| Paragraph | Ordinary text | Avoid overlong blocks of prose. |
| Bold / italic / strikethrough | `**bold**` / `*italic*` / `~~deleted~~` | Use for local emphasis. |
| Inline code | `` `value` `` | Use for commands, configuration, artifact names, log keywords, and user-visible parameters. Do not use it to label internal class or method names or source paths on official user pages. |
| Code block | ```` ```kotlin ```` | Use for commands, configuration, pseudocode, and flows. |
| Unordered list | `- item` | Use for rules and key points. |
| Ordered list | `1. item` | Use for steps that must run in sequence. |
| Task list | `- [ ] item` | Use for TODOs, acceptance checklists, and migration checklists. |
| Table | Markdown table | Use for state machines, capability matrices, behavior differences, boundaries, and investigation entry points. |
| Link | `[text](path)` | Prefer relative or site-absolute paths. |
| Image | `![alt](path)` | Always provide meaningful alt text. |
| Blockquote | `> quote` | Use for ordinary quotations, not as a substitute for a callout. |
| Divider | `---` | Use only to separate major parts of a long page. |
| Table of contents | `[[toc]]` | Use only on long pages. |

Avoid extensive raw HTML on official user pages. Where necessary, ensure the VitePress build passes and the result remains readable on mobile devices.

---

## 4. Callouts

Use GitHub Alert-style blockquotes for callouts, keeping the Markdown readable and degradable:

```markdown
> [!NOTE]
> Ordinary note.
```

Supported levels:

| Level | Use |
|---|---|
| `NOTE` | Background or supplemental information. |
| `TIP` | Recommended practice or best practice. |
| `IMPORTANT` | Important information that is not necessarily dangerous. |
| `WARNING` | Risk to consider before proceeding. |
| `CAUTION` | Severe risk or an operation that may corrupt state. |

With a title:

```markdown
> [!WARNING]
> **Cache consistency risk**
>
> Scoped data is for transport only and must not directly update the global deploy state.
```

Constraints:

- Do not add custom levels; use the five listed above.
- Use `WARNING` or `CAUTION` for risks instead of only bold text.
- A callout may contain a list or code block, but should remain short.

---

## 5. Engineering-Semantic Blocks

In addition to ordinary Markdown, the Jugg Wiki may use fixed engineering-semantic blocks to help readers identify capabilities, flows, boundaries, and investigation entry points. Official user pages must not contain a source index; put source entry points, class names, and paths in `docs/ai`.

### 5.1 Capability / Mechanism Index

```markdown
## Capability Index

| Capability | What it means |
|---|---|
| Incremental resource link | Reuses the last Gradle baseline and links only changed resource inputs. |
```

Use this when a page covers several capabilities, technical mechanisms, or user-observable behaviors.

Constraints:

- Do not list source files, package names, classes, interfaces, methods, or fields.
- Avoid a maintainer viewpoint such as “class X is responsible for,” “interface Y calls,” or “implemented under path Z.”
- Record source entry points in `docs/ai`, not in the official Wiki.

### 5.2 Call Chains / Flows

```text
user action
  -> key decision
  -> visible behavior
  -> state boundary
```

A flow must convey business meaning rather than list method names mechanically. In an official user page, describe user actions, system decisions, artifact movement, and state boundaries, not source-code call relationships.

### 5.3 Hidden Constraints

```markdown
## Hidden Constraints

- Dev-only pages must use `visibility: dev` frontmatter.
- Production builds must not expose dev pages through nav, search, or direct routes.
```

Use these for rules users may misjudge that affect behavior. Keep internal maintenance rules, source organization, and test placement off official user pages.

### 5.4 Troubleshooting Entry Points

```markdown
## Troubleshooting

| Symptom | First entry |
|---|---|
| Incremental run falls back to Gradle | Check whether Gradle files, dependencies, or target device changed. |
```

Give only the first hop, not a complete investigation script. On an official user page, a first hop should be an observable symptom, log keyword, setting, or next action, never a source class or file path.

---
### 5.5 Extracting High-Value Technical Content and Writing Objectively

A page on a core concept, compilation core, or low-level module should first supply the background needed to understand Jugg's mechanism. If readers know the basic mechanism, explain the issue directly; if they do not, establish the smallest useful working model before explaining Jugg's differences and boundaries. Aim for high information density, technical credibility, and a restrained tone.

#### 5.5.1 Opening the Page

A standalone opening paragraph is required between H1 and the first H2. It should first supply what readers need to understand the body:

- If readers know the target mechanism and the page centers on a real failure, state the specific operation, user-visible result, and Jugg's response.
- If readers do not know the mechanism's inputs, stages, and outputs, briefly explain the standard working model before stating which steps Jugg reuses, replaces, or supplements.

Introduce only enough domain background to support the later Jugg explanation; do not turn it into a general framework tutorial. A concrete failure can appear after the basic model and need not dominate the opening. A child page must stand on its own without requiring context from its parent page.

#### 5.5.2 Choose the Narrative Starting Point

Problem-driven writing is a common way to argue a point, not a fixed opening for every concept page. Choose a starting point based on readers' prior knowledge:

- When readers know the basic mechanism and a real local solution or observable failure exists, begin with the problem, Jugg's mechanism, and its boundary.
- When readers do not know the basic mechanism, first explain standard inputs, stages, and outputs, then the steps Jugg changes, key state, and fallback boundary.
- The first H2 should extend the mental model established in the opening. It can be a concrete problem or a standard working model.
- Headings must describe content. Avoid meta-structure labels such as “Pain point,” “Solution,” “Core solution,” “Boundaries and costs,” or “Bottleneck.”
- Develop a three-part argument only on core pages with real architectural friction. Organize simple pages such as navigation, introductory, or parallel-routing pages naturally by content.
- Do not replace an introduction with “This page follows one main thread: ...”.
- Use comparison tables and complete flows only when there is a real mapping or several dependent stages, not as a mandatory format for unfamiliar domains.
- Do not split a complete external mechanism that requires several cooperating stages into a nonfunctional “only one step” flow and use that invented failure to argue for the current design. Retain a local flow only if it is a real implementation choice, historical approach, or reproducible stable case.

#### 5.5.3 Identify Reuse and the Behavior Owner

When the body says “reuse,” “replace,” “bypass,” or “take over,” name three things together: the specific mechanism affected, the original owner of the user-visible behavior, and Jugg's added decision, correction, or fallback. Do not merge them into a vague actor simply because they share one deployment flow.

After the narrative subject changes, avoid pronouns such as “this capability,” “this flow,” or “continue reusing it” in a key conclusion. Repeat precise terms so readers can tell whether the original mechanism still executes the final behavior or Jugg has taken it over.

For example:

- Vague: `Jugg continues reusing this online replacement mechanism.`
- Clear: `Jugg currently reuses the Apply Changes hot-reload channel directly. The Apply Changes Agent still performs ordinary class redefinition; the Jugg Agent detects JVMTI availability and installs runtime corrections.`

#### 5.5.4 Identify High-Value Technical Points

When rewriting or expanding a page, actively extract high-value technical points from the existing documentation or code. Prioritize three kinds of signals:

- **Deep customization**: custom aapt2, an isolated ClassLoader, or Jugg's own JVMTI Agent.
- **Hostile environment differences**: specific Android Studio version differences, device-vendor compatibility, or customized systems such as HarmonyOS / HyperOS.
- **Timing and dependency conflicts**: early initialization, class conflicts, Smart Cast failure, or runtime-state alignment.

Elevate these to key mechanisms or boundaries in the page structure; do not bury them in a chronological activity log.

#### 5.5.5 Tone and Rhetoric

Explain benefits and tradeoffs using measurements and technical principles, such as “10 to 15 seconds of fixed overhead,” “100–200 milliseconds,” “missing local-state cache,” and “in-memory overlay.”

Do not use subjective emotional language or anxiety-driven phrasing, such as “even if,” “purely,” “stuck fast,” “desperate,” “astonishing,” or “cliff-like.” Do not turn a technical explanation into marketing copy.

For abstractions such as “contract,” “guarantee,” “context,” “consistency,” “capability,” and “flow,” check whether they conceal a specific object or result. Prefer a method signature, field type, inheritance structure, DEX reference, Gradle build artifact, or runtime exception when one can be named. Retain abstract terms when they have a precise technical meaning. Do not vary already accurate and consistent terminology merely for style. Avoid first-person language, humor, emotion, personalization, and digressions.

Do not substitute vague adverbs or invented scenarios for exact boundaries. In official user pages, qualifiers such as “basically,” “possibly,” “it may also,” “not necessarily,” “if it happens every time,” and “if one only looks at” must meet at least one condition:

- They correspond to a real trigger, system behavior, or user-visible result.
- They express a branch in a troubleshooting page with a next action.
- They state an actual capability boundary, such as a device, Gradle task, or third-party environment that Jugg cannot fully control.

Otherwise, replace them with verifiable facts: where an artifact originates, which effective path receives it, why that path is needed, and how the system contains failure. Do not invent an incorrect implementation with “if ...” and then use it to justify the current design; describe the real mechanism and cost directly.

When describing a capability boundary, distinguish three outcomes: an unsupported operation that fails or falls back; an ignored operation that leaves old state unchanged; and an operation that has been processed but needs a later restart or reinstall to take effect. For an ignored operation, first state which result is not produced in this run and whether old content remains present or accessible. Mention a full Gradle build or reinstall only as a later way to make the desired change effective. Saying only “requires a full build” could wrongly imply that the operation is unsupported, incremental compilation failed, or an automatic fallback already occurred.

### 5.6 Information Boundary for Official User Pages

The official Wiki addresses product users and ordinary Android developers, not source maintainers. It may explain mechanisms, tradeoffs, boundaries, and user-observable behavior, but must avoid a source-code viewpoint.

Rules:

- Do not include a source index.
- Do not list source files, package names, classes, interfaces, methods, or fields.
- Avoid maintainer phrasing such as “a class is responsible for,” “an interface calls,” or “implemented under a path.”
- Do not expose internal module boundaries to users unless they are already an understandable product capability or public concept.
- Do not put source-code investigation entry points on official user pages; direct users to symptoms, logs, settings, operation results, and next actions.

Allowed content:

- Product capabilities, runtime stages, and technical mechanisms visible to users.
- General Android concepts familiar to developers: Gradle, APK, DEX, Manifest, resources.arsc, aapt2, Apply Changes, and JVMTI.
- Names users see in logs, UI, configuration, or errors.
- Engineering facts that explain behavior differences, such as “a Gradle baseline must be re-established” or “the app is reinstalled when device state is untrustworthy.”

Rewriting principles:

- Replace internal implementation names with mechanism descriptions.
- Replace source paths with capability boundaries.
- Replace call relationships with a flow users can understand.
- Replace maintainer investigation entry points with observable symptoms and next actions.

### 5.7 Division of Labor Between Prompts and Rules

Keep stable authoring rules in this document instead of repeating them in every prompt. A prompt should specify only the target page, audience, main standard, and delivery action.

Suggested short prompt:

```text
Rewrite <target document> according to docs/ai/10_wiki_authoring.md. Write the English source page first, then synchronize its Chinese mirror.

Depending on ordinary Android developers' familiarity with the topic, establish the necessary working model or start from a real problem, then explain Jugg's mechanism and boundaries. Avoid a source-code viewpoint. Preserve the existing title, H1, internal links, and user-visible facts. Build and commit when done.
```

For multiple pages:

```text
Rewrite these Wiki pages according to docs/ai/10_wiki_authoring.md: <page list>. Write the English source pages first, then synchronize their Chinese mirrors.

Write consistently for ordinary Android developers. Choose a narrative starting point based on each topic's prerequisites, remove the source-code viewpoint, and preserve understandable technical mechanisms, boundaries, and internal links. Build and commit when done.
```

### 5.8 Distinct Roles of `concepts` and `capabilities`

`concepts` and `capabilities` can discuss the same technical topic, but must not repeat the same explanation. Separate them by reader intent:

| Directory | Reader question | Content boundary |
|---|---|---|
| `concepts` | Why is this mechanism needed, and how does Jugg preserve correctness? | Causes of the problem, limitations of conventional approaches, Jugg's mechanism, key state/data flows, timing constraints, costs, and boundaries |
| `capabilities` | Is my change or operation supported, and what result will it trigger? | Supported scope, trigger conditions, user-visible results, prerequisites, common degradation/fallback, and further reading |

Decision rules:

- Put explanations of “why this must be done / how the mechanism preserves correctness” in `concepts`.
- Put “is this scenario supported / what result will I get” in `capabilities`.
- Put “what should I do now” in `guide`.
- Put “where should I look next for this symptom” in `troubleshooting`.

A capability page must not rephrase and repeat its corresponding concept page. It may retain a short flow limited to user-understandable triggers and results, typically 5–8 lines. Link deeper mechanism explanations to the relevant `concepts` page under “Related Pages.”

### 5.9 Recommended Capability Page Structure

For a specific capability, prefer:

```markdown
# Capability Name

One sentence explaining the user scenario and scope of this page.

## Supported Scope

| Scenario | Current support | User-visible result |
|---|---|---|

## Triggers and Results

A short flow or list describing only triggers and user-visible results, without the deep mechanism.

## Usage Boundaries

- Prerequisites.
- Cases that fall back, restart, reinstall, or require a Gradle baseline.
- Limitations users may misjudge.

## Related Pages
```

Capability-page rules:

- Prefer “User-visible result” for the third table column, not an internal executor class or data field.
- “Triggers and Results” connects the support matrix to outcomes; it does not explain the entire mechanism.
- When an underlying cause needs explaining, give one conclusion and link to the concept page.
- If a concept page exists for the topic, do not repeat its core paragraphs in the capability page.
- For a boundary scenario, describe the current incremental path's actual result before its recovery action. For example, if deletion is ignored, state that old content still exists instead of only saying “deletion requires a full build.”

### 5.10 Recommended Concept Page Structure

There is no mandated opening or section order for concept pages. When readers know the basic mechanism, a page can begin with a real problem. When they lack the background, explain standard inputs, stages, and outputs as briefly as possible. Both types of page usually cover Jugg's response, key state or timing, applicable boundaries, and related pages; name headings for their actual content.

Concept-page rules:

- Do not include a support matrix; that belongs on a capability page.
- Do not include operation steps; those belong in a guide.
- Do not turn each capability into a list-style entry point; routing belongs on the capability overview page.
- Mechanism details are allowed, but address ordinary Android developers without source paths, class names, or method names.

### 5.11 Cross-Directory Links Among `guide`, `concepts`, and `capabilities`

Cross-directory links let readers move among “how to do it,” “why it works,” and “what is supported.” They are not a reason to interlink every page in the same broad technical category.

First determine whether two pages are **corresponding pages for the same topic**:

- A `guide` page gives direct operation, judgment, and recovery steps for a feature.
- A `concepts` page explains the same feature's underlying problem, mechanism, state, or timing.
- A `capabilities` page states the same feature's supported scope, triggers, and user-visible results.

Corresponding pages must link both ways:

- If both `guide` and `concepts` exist, link them to each other.
- If both `concepts` and `capabilities` exist, link them to each other.
- If both `guide` and `capabilities` exist, link them to each other.
- If all three exist, prefer the complete triangle; do not require readers to pass through a third page.
- The directory homepages of `guide`, `concepts`, and `capabilities` should also offer entry points to one another.

The following links do not require a reverse link:

- An upstream baseline or downstream deployment step required by the current mechanism.
- A background page used to explain an artifact, limitation, or failure branch.
- Navigation from an overview to a child, troubleshooting, or reference page.

When editing “Related Pages” or “Related Capabilities”:

- Merely sharing a broad “compilation,” “deployment,” or “tools” category does not establish a relationship.
- Do not link to a broad generic page for symmetry when there is no exact corresponding page. Leave the gap until a real corresponding page exists.
- If a confirmed same-topic pair has only a one-way link, treat that as a documentation gap and add the reverse link.
- Compare the reader question and body of both pages before adding links; do not pair mechanically by similar titles.
- After adding or removing cross-directory links, check reverse links, resolve relative paths, and run the production Wiki build.

## 6. English and Chinese Page Synchronization

The Jugg Wiki uses English root-route pages as its sole content source. Chinese `/zh/` pages are strict mirrors:

- After removing the `zh/` prefix, the English and Chinese Markdown path sets must match exactly, including dev-only pages.
- Page type, section order, table rows, callouts, code blocks, and Related Pages structure must match.
- Translate `title`, `description`, H1, and navigation labels into Chinese; keep `status`, `tags`, and `visibility` identical.
- Mirror nav/sidebar hierarchy, order, and target pages; translate only the displayed labels.
- Add, delete, move, or rename both language versions in the same task; do not leave a page in only one language.
- Update English first when facts, structure, boundaries, or links change, then update Chinese in the same diff and commit.
- A purely linguistic fix that changes no facts, structure, routes, or links may update only the affected language.

Translate from the English page into Chinese directly without rechecking the implementation. Natural Chinese sentence structure is fine, but do not add, remove, reorder, or weaken English facts or boundaries. If English content conflicts, a link is broken, or the intended meaning is unclear, report the issue first rather than adding your own explanation to the Chinese page.

Use American English and sentence case headings consistently in English. Follow the English and Chinese `reference/glossary.md` pages for terminology. Keep commands, parameters, paths, configuration values, log keywords, and actual UI strings unchanged.

The glossary is a column-structure exception: Chinese `zh/reference/glossary.md` has three columns, “Chinese term / English term / meaning”; use `-` where there is no Chinese term. English `reference/glossary.md` has only “Term / Meaning” and should not gain a Chinese column. The entry set, order, and meanings must still correspond.

---

## 7. Discouraged Practices

- Do not put internal maintenance rules on official user Wiki pages.
- Do not use extensive custom HTML merely for visual effect.
- Do not expose dev-only demos, AI maintenance workflows, or internal task plans on official pages.
- Do not include source indexes, source paths, internal class names, or method names on official pages.
- Do not put maintainer investigation entry points on user pages.
- Do not use screenshots instead of tables, code blocks, or searchable text.
- Do not translate code implementations line by line into Wiki prose; extract entry points, flows, states, and constraints.
- Do not hide unverified mechanism descriptions behind weak qualifiers such as “basically,” “may also,” or “not necessarily.”
- Do not manufacture an argument from counterfactual bad practices such as “if it happened every time ...” or “if one looked only at ...”.

---

## 8. Related Documents

- `10_wiki_architecture.md`: Wiki project structure, local operation, build, preview, and publishing boundaries.
- `97_maintenance_manual.md`: AI knowledge-base maintenance quality standard.
- `99_index.md`: AI documentation search entry point and topic catalog.
