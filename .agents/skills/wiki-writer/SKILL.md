---
name: wiki-writer
description: Write, rewrite, review, and synchronize Jugg user documentation under docs/wiki. Use for concepts, capabilities, guides, troubleshooting, and reference pages; turn source code, maintainer knowledge, and historical material into pages for ordinary Android developers. Treat English pages as the content source and keep Chinese pages as strict mirrors. Do not use for routine maintainer knowledge-base work, task plans, changelogs, or Markdown outside the Jugg Wiki.
---

# Jugg Wiki writing

Write Wiki pages that explain real engineering problems, Jugg's choices, and user-visible results. Establish a concise causal chain instead of listing implementation components.

## Read the minimum authoritative context

Follow the mandatory workflow in the repository's `AGENTS.md` before reading or editing implementation code.

1. If not yet read in this session, read `docs/ai_knowledge/00_overview.md` and `docs/ai_knowledge/99_index.md`.
2. Read `docs/ai_knowledge/98_code_map.md` to locate the behavior owner.
3. Read `docs/ai_knowledge/10_wiki_authoring.md` and `docs/ai_knowledge/10_wiki_architecture.md`.
4. Read the target page and the nearest `index.md` in its directory hierarchy. Read a higher-level index only if the nearest one does not establish the page's role or navigation context.
5. If same-language concept or capability pages cover the same topic, read them too. If a target spans independent topics, inspect each paired page and decide whether to split the target.
6. Select only directly relevant `docs/ai_knowledge` topics through `99_index.md`; never load the whole knowledge base at once.

For product behavior, compatibility differences, or recovery claims, verify the current implementation. Restrict code investigation to the behavior owner and facts proposed for the article.

For an English-to-Chinese translation, the English Wiki page is the sole content baseline: translate it without rechecking each product fact against topic documents or implementation. Report internal contradictions, broken links, or unclear meaning instead of inventing an explanation.

## Use historical material

Treat old articles, presentations, demos, and screenshots as supplementary background. Before using them, verify whether:

- The current implementation still uses the same approach.
- Support and limitations have changed.
- Statements such as “in development” or “not supported yet” are obsolete.
- Performance figures have reproducible measurement context.
- Current evidence still supports the failure and tradeoff described.

Do not invent design motives, alternatives, or performance gains when historical material is absent. Build the causal account from current code, topic documents, logs, stable reproductions, and known user symptoms. Comments and implementation constraints can explain why current handling is needed, but do not prove the original decision process or historical benefit. When evidence only establishes what happens now, do not claim to know why it was first chosen.

If a historical design intent or failure mode is verified against the current implementation, remains useful, and is absent from `docs/ai_knowledge`, list it as a knowledge-base synchronization candidate in the handoff. Do not expand a Wiki-writing task into knowledge-base maintenance unless the user requests it.

## Identify the page's role

Choose the page type from the reader's question:

| Page type | Reader's question | Content focus |
|---|---|---|
| `concepts` | Why is the mechanism needed, and how does it stay correct? | Cause, simpler approach's gap, Jugg mechanism, state/data flow, tradeoffs, limits |
| `capabilities` | Is my change supported, and what result will I see? | Scope, triggers, visible results, prerequisites, fallback, related concepts |
| `guide` | What should I do now? | Ordered actions, expected results, decision points, safe recovery |
| `troubleshooting` | What should I check next for this symptom? | Observable symptom, relevant boundary, first diagnostic step, recovery entry |
| `reference` | What stable fact or contract applies? | Exact parameters, states, formats, constraints, links to explanations |

Do not repeat a concept page in different words on a capability page. Keep only a short trigger-to-result flow there and link to the concept for mechanisms.

When an existing route is an external entry point, preserve compatible English and Chinese routes together; do not keep a language-specific extra page.

## Audit structure before writing

Before editing, state the page's argument in one to three sentences or a few points for the writing process, then check:

1. **Type and name:** Determine whether readers need a mechanism, support judgment, procedure, or diagnosis. Name concept pages after the mechanism, state model, or flow. A title asking “how” or “when” may belong in a guide or troubleshooting page. A rename requires checking frontmatter, H1, navigation labels, body links, and home-page calls to action.
2. **Opening value:** The first two paragraphs of a concept should establish what the developer changed, which work a full build normally handles, why Jugg must handle this change separately, and which visible result the page explains. Do not build the whole opening out of negatives; use a negative statement only to correct a real misconception.
3. **Standard mechanism and Jugg's difference:** When Jugg bypasses, replaces, or reuses an Android build step, first establish the standard inputs, tool, and output briefly, then say which step Jugg changes. Explain compilation and packaging before deployment or effect. “Jugg does not run X” is not an adequate starting point unless X was already explained.
4. **Reuse boundary:** When saying “reuse,” “replace,” “bypass,” or “take over,” name the concrete mechanism, its original behavior owner, and Jugg's added responsibility. When the subject changes, repeat its precise name instead of vague pronouns such as “this capability” or “this chain.”
5. **Page boundary:** Adjacent topics do not automatically belong in one page. Compare their behavior owner, reused artifact, changed state, and result:

| Question | Distinction to establish |
|---|---|
| Behavior owner | Compilation, deployment, device recovery, or Run orchestration |
| Reused artifact | Whether current compilation or deployment output remains in use |
| Changed state | Transport condition, device state, or build baseline |
| Result | Retry the step, broaden recovery, or switch phase |

Split topics with materially different answers, even if one failure flow connects them. Do not force independently explainable problems into a vague “fallback” or “robustness” page.

Choose the entry point based on the reader's prior knowledge. Start from a concrete change and failure when the standard mechanism is familiar; otherwise establish its minimum working model. Use comparison tables and full flows only for real mappings or interdependent stages.

Use titles that name their content, not meta-headings such as “Background,” “Pain point,” “Core solution,” “Value,” or “Tradeoffs.” Do not broaden a claim that current documents, code, logs, or stable reproduction cannot support.

Prioritize engineering facts when they explain visible behavior: deep customization (isolated compiler, custom aapt2, JVMTI Agent), environment conflict (Android Studio, JBR, AGP, devices), timing/dependency conflict (generated source, old symbols, Gradle baseline, runtime structure), and bounded failure handling (changed-condition retries, explicit Gradle fallback). An internal workaround needs its own section only if it explains a visible difference, a commonly misread constraint, or a real design choice.

## Ground the narrative in evidence and failure

When a real failure exists, provide a compact chain, for example:

```text
A removes a method or changes a field type
  -> unchanged B is not recompiled
  -> this local compilation succeeds
  -> the APK retains the old call
  -> runtime throws NoSuchMethodError or NoSuchFieldError
```

A failure example explains the mechanism; do not fabricate an obviously broken implementation to make Jugg appear better. Do not misrepresent one step of a multi-stage external mechanism as a complete alternative unless that partial flow was an actual implementation, historical approach, or stable reproduction.

For a significant choice, explain what the simpler option does, its observable failure or cost, why Jugg's approach fits the environment, and the fallback trigger.

Quote performance numbers only with measurement subject, representative project or input scale, machine/environment, tool versions, sampling method, and statistics. Repetition across Wiki pages is not independent evidence. Otherwise describe which work was reduced or narrowed without a precise gain.

## Maintain the user-facing boundary

Keep useful frontmatter and one H1. Preserve an existing `frontmatter.title` and H1 unless the user requests a rename or a clear conflict with page purpose has been confirmed.

Put an independent opening paragraph between H1 and first H2. For a familiar mechanism with a real failure, it can name the change, visible result, and Jugg's treatment. For an unfamiliar mechanism, first introduce the standard flow and Jugg's difference. The failure need not be the first sentence.

General Android developer concepts such as Gradle, D8, DEX, aapt2, Manifest, JVMTI, classpath, `NoSuchMethodError`, and `minSdk` are appropriate. Avoid maintainer-only source paths, packages, internal classes/methods/fields/line numbers, mechanical call sequences without decisions or state changes, test owners, database tables, temporary directories, internal cache names, and obsolete status claims.

Translate implementation facts into mechanisms. Instead of “The compiler isolation class creates a ClassLoader,” say that the IDE and compiler may contain different versions with the same package name, so Jugg loads the compiler in an isolated environment.

Use tables for real comparisons and text flows for dependent stages, not decoration.

## Refine language after facts are stable

Write directly and precisely. Remove promotional phrasing, empty claims of importance, filler transitions, and generic conclusions. Replace “ensures correctness” with the specific protected state, artifact, or failure boundary. Replace “may have problems” with a trigger and visible result.

Distinguish unsupported operations, ignored operations, and failures followed by fallback. For an ignored operation, first say what this run does not produce and whether old content remains accessible; describe full builds or reinstalls as subsequent ways to apply the desired change. Do not say only “requires a full build,” which suggests the current run failed or fell back.

Examine abstract terms such as “contract,” “guarantee,” “context,” “consistency,” “capability,” and “chain.” Use exact concepts such as a method signature, field type, inheritance structure, DEX reference, Gradle artifact, or runtime exception when they carry the meaning. Keep an abstract term where it has precise technical meaning. Give each paragraph one main judgment and break at decision boundaries. Preserve accurate terms across nearby Wiki pages instead of changing words for variety. Avoid first-person voice, humor, emotion, personal asides, and chat traces.

These are the complete language checks for formal Wiki pages. Do not load a generic prose-polishing skill unless the user explicitly asks; technical accuracy and terminology here take precedence. Language edits must preserve facts, preconditions, state semantics, and visible outcomes.

## Translate English to Chinese

The English page is the sole content baseline for its Chinese mirror. Keep the same relative path, page type, section order, table entries, alerts, code blocks, and related-page structure. Chinese nav/sidebar mirrors the English hierarchy and order.

- Use natural Chinese without adding, removing, reordering, or weakening facts and limits. Long sentences may be split, voice changed, and filler removed.
- Translate `title`, `description`, H1, and navigation labels; keep `status`, `tags`, and `visibility` aligned.
- Keep `compile` as a verb, `compilation` as the process, `build` as building, `fall back` as a verb, and `fallback` as a noun/adjective when reviewing English terminology.
- Use consistent English terms `incremental compilation`, `incremental deployment`, `recompilation`, `self-healing`, `baseline`, `take effect`, and `project information` in English pages. Render both “recompile” and related propagation concepts consistently with `recompilation`.
- Preserve product names (Jugg, Android Studio, Gradle, Kotlin, Java, APK, DEX, AAPT2, JVMTI, MCP, CLI, Apply Changes, Code Swap, Full Swap, Hot Reload), commands, parameters, paths, config values, log keywords, and actual UI labels.
- Change internal links to the corresponding Chinese mirror; keep external links and anchor semantics.
- Do not add product facts independently in Chinese. A language-only correction may modify only the affected language if facts, structure, boundaries, and links stay unchanged.
- The glossary is a column-structure exception: `zh/reference/glossary.md` has three columns (Chinese term, English term, meaning), using `-` when no Chinese name exists; `reference/glossary.md` has only Term and Meaning. Entries, ordering, and meanings must still match.

## Control scope and synchronize languages

The English root and Chinese `/zh/` path must strictly mirror each other. Except for language-only corrections, handle every Wiki content change in this order:

1. Add or change the English page as the content baseline.
2. In the same task, add or update the Chinese page at the same relative path.
3. Update both nav/sidebar locales for additions, deletions, moves, or renames; leave no page unique to one language.
4. Include both languages' changes to facts, structure, boundaries, or links in one diff and commit.

When a Chinese translation exposes a factual or structural problem, correct the English baseline first, then translate that correction to Chinese. An isolated spelling, grammar, or fluency correction may be submitted in one language only if it changes no fact, section structure, route, or link.

Preserve shared routes, existing `frontmatter.title`, H1, product terms, and working internal links unless the request requires a change. Mirror compatibility entries in both languages.

For a request only to propose an approach or review, do not edit files. Report the suggested argument, structure, content to keep/remove, validation needs, and routes/titles/H1/product terms/working links that should stay; explain and await confirmation for any proposed change to those stable identifiers.

## Verify the result

For a proposal or read-only review, confirm referenced page and implementation paths exist; separate verified facts, obsolete content, unsupported claims, and rewrite suggestions; report mirror gaps; do not build, edit, stage, or commit unchanged Markdown.

For an actual page creation or rewrite:

1. Compare Markdown path sets after removing the `zh/` prefix, including dev-only pages.
2. Except for language-only corrections, ensure changed English pages and Chinese mirrors both appear in the diff.
3. Run `python3 .agents/skills/wiki-writer/scripts/validate_wiki.py --wiki-root docs/wiki` for mirrors, relative Markdown/HTML links, and sidebar source pages.
4. Run `git diff --check`.
5. Run production `npm run build` in `docs/wiki` without a dev-only configuration.
6. For unchanged routes, verify expected HTML and a distinguishing new title or section in `docs/wiki/.vitepress/dist/`.
7. For route changes, audit old titles and slugs with `rg --hidden` across `docs/wiki`, including config, home page, cards, and HTML links. Use the validator's `--forbid-source-text`, `--expect-html-route`, and `--expect-html-text ROUTE::TEXT`. For unpublished pages use `--expect-removed-route`; for published pages require `--expect-compatible-route`.
8. Review the final diff for unsupported behavior, obsolete status, duplicate concept/capability text, source details, and Chinese body text left on English pages.
9. Stage and commit only this task's files under the repository convention.

Do not add automated tests for prose-only changes. For new or rewritten pages, use a production build, link checks, rendered output, and code comparison as evidence. For English-to-Chinese translation, use mirror comparison and the same build, without rechecking implementation facts.

## Quality gate

Before finishing, check applicable points:

- The page stands alone without its parent.
- The opening supplies the scenario or minimum domain model and the connection to Jugg.
- The reader can use that model to connect later mechanisms instead of seeing isolated facts.
- Major sections answer reader questions, not merely name implementation components.
- A core mechanism page uses a concrete case when a real failure or cost exists.
- The page explains the present choice and its limits or fallback.
- New/reworked product claims match current code; historical material was verified, marked, or discarded; a Chinese translation matches the English baseline.
- Concepts and capabilities do not duplicate the same mechanism explanation.
- English and Chinese Markdown paths, structure, and navigation strictly mirror; only the documented glossary column exception applies.
- Both languages changed together except for language-only corrections.
- Prose is specific and restrained.
- Actual page changes pass production build and link checks; read-only reviews cite repository evidence.

The final response should state the reader-visible improvement, evidence, changed files, and commit, and include the fixed checklist required by `AGENTS.md`.
