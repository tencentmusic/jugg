# Wiki article authoring

> Last verified: 2026-10-07. The English pages under `docs/wiki/` are the content source; `docs/wiki/zh/` is a strict Chinese mirror. For site routing, build, and publication, use [10_wiki_architecture.md](10_wiki_architecture.md).

## 1. Audience and page placement

Official Wiki pages address ordinary Android developers. Explain a capability, mechanism, result, or next action in terms readers can observe: Gradle tasks, APK/DEX/resources, Jugg settings, logs, UI, fallback, restart, and reinstall. Do not include a source index, internal package/class/method names, or maintainer-only investigation steps. Put implementation ownership and cross-file source flows in `docs/ai/`. The dev-only [elements demo](../wiki/dev/elements-demo.md) may show internal authoring examples; it is not a template for an official page.

| Page type | Reader question | Primary content |
| --- | --- | --- |
| `concepts/` | Why does the mechanism exist, and how does Jugg preserve correctness? | Cause, standard working model when needed, Jugg's mechanism, state/timing, costs, and boundaries. |
| `capabilities/` | Is this change supported, and what happens? | Scope, trigger, visible result, prerequisites, fallback/degradation. |
| `guide/` | What should I do now? | Ordered operation, decision, and recovery steps. |
| `troubleshooting/` | Where should I look next for this symptom? | Observable evidence and first discriminating action. |

A concept and its capability page should answer different questions. A capability page may give a short user-facing trigger→result flow and link to the concept for mechanism detail; it should not repeat the concept explanation. Concept pages do not need a support matrix or operation procedure. Guide steps belong in guides. A troubleshooting first hop should be a log keyword, setting, result, or action rather than a source file.

For a corresponding topic pair across `guide/`, `concepts/`, and `capabilities/`, link both ways; if all three exist, use a complete triangle. Their directory homepages should offer cross-directory entry points. A background dependency or overview-to-child link does not require a reverse link. Compare the actual reader question and body before adding a “Related pages” link; broad category similarity alone is insufficient. After link edits, check the reverse links and resolved paths.

## 2. Explain mechanisms without inventing a story

Place a standalone opening paragraph between H1 and the first H2. A child page must be understandable without reading its parent. If readers lack the Android mechanism's inputs, stages, or outputs, establish the smallest accurate working model before Jugg's change. If the mechanism is familiar and the page centers on a real failure, begin with that operation, its visible result, and Jugg's response. Use content-specific headings; do not impose “Pain point / Solution / Boundaries” or a three-part argument on simple pages.

When saying Jugg “reuses,” “replaces,” “bypasses,” or “takes over” a mechanism, identify the mechanism, the original owner of the visible behavior, and Jugg's added decision or fallback. Once the subject changes, repeat the concrete actor or artifact instead of using “this flow” or “it” in a key conclusion. A flow should connect user action, decision, artifact/state movement, and visible result; do not translate method calls into prose.

Distinguish an **unsupported** operation that fails or falls back, an **ignored** change that leaves old state in place, and a **processed** change that needs a later restart or reinstall to become visible. For an ignored deletion, say which old content remains and what this run did not produce before recommending a full build. A full build recommendation alone does not establish that Jugg automatically fell back. Name actual variant, device, toolchain, and timing prerequisites. Treat custom toolchains, version-specific Android Studio behavior, device-vendor differences, early initialization, and class/state conflicts as prominent boundaries when they determine the result. Use measured costs only when evidence supports them. Avoid vague qualifiers, subjective rhetoric, and counterfactual failures presented as real design history.

## 3. Page metadata and Markdown

Most current official pages use `title`, `description`, `status`, and `tags` frontmatter. `title` and H1 describe the same page in the locale's language; `description` summarizes the body. `status` (`draft`, `active`, `deprecated`) and `tags` are editorial metadata, not publication gates. Preserve an existing title/H1 unless intentionally renaming the page or correcting a clear role mismatch.

Dev showcase pages live under `dev/` in both locales and carry `visibility: dev` by convention. Production exclusion is controlled by the path-based `srcExclude` in `docs/wiki/.vitepress/config.mts`, not by parsing that frontmatter field. Official pages omit `visibility: dev`. Keep the dev paths mirrored so production and dev navigation remain aligned.

VitePress 1.6.4 in `docs/wiki/package.json` renders ordinary Markdown headings, paragraphs, lists, tables, task lists, links, images, fenced code, frontmatter, and `[[toc]]`. Use one H1, meaningful image alt text, relative or site-absolute links, and `[[toc]]` only when a long page benefits from it. Use inline code for commands, configuration, output artifacts, log keywords, and user-facing parameters. Do not replace searchable explanations, tables, or code with screenshots. Raw HTML is possible but should be rare and checked in the rendered page, especially on mobile. The [elements demo](../wiki/dev/elements-demo.md) is the syntax/rendering reference; this page does not duplicate the element tutorial.

Use the five GitHub Alert levels `NOTE`, `TIP`, `IMPORTANT`, `WARNING`, and `CAUTION` for short callouts. Put a custom title on the marker line, for example `> [!WARNING] Cache consistency`; bold text on the next line remains body text, not an alert title. Prefer `WARNING` or `CAUTION` for real risks. Do not add a custom level simply to express a new editorial category.

Tables are useful for genuine mappings such as supported scope, state differences, or symptom→next action. A “Capability index” on a multi-topic user page names user-visible abilities, not source owners. A flow names business stages, not classes. Use these only when they clarify a real relationship, rather than inserting the same blocks into every page.

## 4. English-first mirror and terminology

The route set is mirrored: after stripping `zh/`, the English and Chinese Markdown paths must match, including dev pages. Add, delete, move, or rename both versions in the same task. Write or correct facts, page structure, and links in English first; then translate the Chinese page directly from that reviewed English source in the same diff. A purely linguistic correction with unchanged facts, routes, structure, and links may touch one locale.

Mirror page type, section order, table rows, alerts, code blocks, and Related pages structure. Translate `title`, `description`, H1, body, and displayed navigation labels. Keep `status`, `tags`, `visibility`, commands, parameters, paths, configuration values, log keywords, and literal UI strings consistent. Use American English and sentence case for English headings; follow the [English glossary](../wiki/reference/glossary.md) and [Chinese glossary](../wiki/zh/reference/glossary.md). Chinese prose can be natural, but it must not add, remove, reorder, or weaken an English fact or boundary. If the English meaning or a link is unclear, resolve the English page before translating.

The glossary is a deliberate column exception: English has “Term / Meaning”; Chinese has “Chinese term / English term / meaning”, using `-` when there is no Chinese term. Entry sets, order, and meanings still correspond. Navigation and sidebar hierarchies must use the same route order after removing the locale prefix; only labels change.

## 5. Review and verification

For a Wiki content task, compare the English source with code or current product evidence, then review its Chinese mirror against the English page. The repository's `.agents/skills/wiki-writer/scripts/validate_wiki.py` checks Markdown path mirrors, configured routes, nav/sidebar order, and source links; it does **not** judge translation equivalence. Run it from the repository root. In `docs/wiki/`, `npm run build` checks VitePress rendering and routes after dependencies are installed; it does **not** prove the bilingual prose matches. For dev-only syntax/rendering, use `JUGG_WIKI_DEV=true npm run build` and inspect the rendered demo. For homepage components, use `npm run check:homepage`. The architecture page owns production base paths, preview, and publication checks.

Before finishing, verify the changed locale pair's title/frontmatter, headings, tables, callouts, links, and user-visible boundaries together. A green build or route validator cannot replace that content comparison.

## Related documents

- [10_wiki_architecture.md](10_wiki_architecture.md) — routes, local operation, and publication.
- [97_maintenance_manual.md](97_maintenance_manual.md) — maintainer knowledge-base placement.
