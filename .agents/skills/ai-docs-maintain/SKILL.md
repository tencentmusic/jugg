---
name: ai-docs-maintain
description: Maintain or rewrite specified Jugg docs/ai knowledge-base pages when explicitly invoked as $ai-docs-maintain. Do not use for routine code changes, Wiki pages, or task records.
---

# Maintain Jugg AI documentation

Use this skill only when the user explicitly invokes `$ai-docs-maintain`. Identify the target pages or topic from the request; ask for a target if none can be determined. A rewrite may reorganize the named pages, but does not authorize a knowledge-base-wide rewrite.

Follow the repository `AGENTS.md` workflow. Read `docs/ai/00_overview.md` and `99_index.md`, locate the topic in `98_code_map.md`, then read `97_maintenance_manual.md` and only the relevant target pages. Verify claims against the current implementation; code takes precedence over documentation.

## Decide where information belongs

- Keep core source navigation, cross-file flows and state handoffs, and hidden constraints whose effects span multiple files and cannot be understood from one file in `docs/ai/`.
- Keep navigation-only jumps only for a main-flow entry point, owner of key state, or main failure-recovery entry point. Do not infer frequency from the current task.
- Treat a stable method name at one of these core boundaries as navigation information when a class name alone leaves multiple plausible entry points. Keep the method with its decision, state, or output meaning; do not preserve exhaustive method lists. Distinguish actual caller-to-callee order from conceptual data flow.
- Keep diagnostic interpretation separate from navigation and hidden constraints. Apply the evidence rules in `97_maintenance_manual.md` §§8.1–8.2; `09_plugin_runtime_debug.md` has its own investigation criteria.
- When an update or rewrite finds a current, non-obvious reason confined to one implementation in the target page, move that reason into a concise English comment beside its owning code as part of the requested documentation maintenance. Limit code edits to comments; do not change behavior. If the code already makes the rule clear, remove redundant prose without adding a comment. Remove obsolete claims after verification. If the owner or truth cannot be verified, preserve the claim and report the uncertainty rather than moving or deleting it silently.
- State each cross-file constraint once, in the call chain, state model, or hidden-constraints section where its effect is clearest. Do not repeat implementation details in another section.

## Work on the requested pages

For a targeted update, synchronize affected existing facts, apply the placement rules to the affected sections, and add only facts that pass them. For an explicit rewrite, classify the entire named page by its value to navigation, cross-file understanding, or correct diagnosis; keep supported facts, consolidate duplicates, and remove code paraphrases. Preserve the current behavior and any useful facts unique to the page. Do not apply the navigation gate to diagnostic interpretations or the hidden-constraint gate to source indexes.

Before removing, compressing, or replacing existing content in either a targeted update or a rewrite, check the destination of each distinct fact and useful core navigation anchor: retained in the changed page, already covered in a named current document, moved into an owning code comment, or excluded by a specific placement rule or because code disproves it. Audit diagnostic meanings and verification prerequisites separately from source navigation and hidden constraints. Preserving a flow's meaning does not by itself preserve a useful method-level first hop; searchable implementation or an existing test does not by itself replace diagnostic interpretation or a non-obvious verification condition. Put durable verification guidance in `06_testing.md` when that is its authority, and link instead of duplicating it. Compare the affected old and new sections after an update, or the whole old and new pages after a rewrite, for information with no verified destination. Keep this audit in working notes; a short `$ai-docs-maintain` request supplies enough context and does not require the user to prepare it.

Check `99_index.md` and `98_code_map.md` when a topic, entry class, or path changes. Run `git diff --check` and spot-check any source paths or commands named in the changed pages. In the final response, explain which facts were kept, moved, or removed and why, then include the repository task execution checklist.
