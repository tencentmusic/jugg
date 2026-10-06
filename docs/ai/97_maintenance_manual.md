# AI Knowledge-Base Maintenance Manual

## 1. Goal

When maintaining topic documents under `docs/ai`, the goal is not to retell the code. It is to let AI obtain three kinds of high-value information without reading as much code:

1. **Source index**: Quickly identify the core classes, their locations, and their responsibilities.
2. **Call chains / state machines**: Document main flows that would otherwise require jumping across a dozen classes.
3. **Subtle details**: Record information that is unobvious in code or absent from it, such as hidden constraints, design rationale, known defects, and boundaries easily misdiagnosed.

## 2. Quality Standard

Every new paragraph should answer at least one question:

- Does it save AI from opening several source files?
- Does it explain a cross-class call chain or state transition?
- Does it reveal a constraint, intent, or risk that is hard to see from code alone?
- Does it avert a likely misdiagnosis?

If every answer is no, omit the paragraph.

Decide where the information belongs before applying this quality standard. Code that already expresses a local rule clearly needs no additional prose. Explain a non-obvious reason confined to one implementation in a nearby English code comment. Put a hidden constraint in this knowledge base when its effects span multiple files and one file cannot convey the complete rule. A constraint already explained in a call chain or state model should appear there once, not again in a hidden-constraints list.

## 3. Content Selection

### 3.1 Keep

- Core entry classes, key collaborators, and key data models.
- Main business flows, important branches, and failure-recovery flows.
- State machines, lifecycles, and data-flow order.
- Hidden constraints, such as “scoped data may be used only for transport, not for commit.”
- Design intent, such as “this side path replaces only transport, not lifecycle management.”
- Known defects, investigation entry points, and logs or symptoms that are easily misread.
- Where current code differs from old understanding, update the document to match current code.
- When a claim's owner or current truth cannot be verified, retain it with the uncertainty stated; do not silently move or delete it.

### 3.2 Remove or Compress

- Mechanical lists of every class, method, or parameter.
- Sentence-by-sentence natural-language renditions of code's if/else branches.
- Low-information boilerplate columns such as “When AI needs to read code.”
- Material already covered by another document; testing rules should generally point to `06_testing.md`.
- Background without clear business meaning.
- Pure concept explanations that do not help locate code, understand a flow, or avoid a misdiagnosis.

## 4. Recommended Structure

Prefer the following structure for topic documents. Individual documents need not match it exactly, but should maintain information density.

```markdown
# Topic name

> Last verified: YYYY-MM-DD
> Consistency rule: If documentation conflicts with code, code takes precedence.

## 1. Purpose of This Document

State which questions this page answers, which details it leaves out, and where to find those details.

## 2. Core Source Index

| Class/interface | File | Role |
|---|---|---|

## 3. Core State Model / Data Model

Explain key states, data structures, and lifecycles with a table or short text.

## 4. Core Call Chain

Use a `text` code block for the main flow, emphasizing decision points rather than paraphrasing code line by line.

## 5. Hidden Constraints / Design Rationale / Known Defects

Include only information unobvious in code, hard to infer across files, or historically easy to misread.

## 6. Investigation Entry Points

| Symptom | First entry point |
|---|---|

## 7. Related Documents
```

If a topic has no state machine, rename §3 to “Core Data Flow” or “Key Models.” For a pure investigation manual, move investigation entry points earlier.

## 5. Length and Density

- Brevity is not a goal by itself, but every section must have a clear use.
- A 100-line document should not usually grow to 300 lines unless most added content explains call chains, state machines, or hidden constraints.
- A typical topic document should be about 150–250 lines.
- A large topic may exceed 250 lines, but its added information must be justified by what cannot be learned easily from code alone.
- Prefer one source-index table to several grouped tables; split only when many classes have genuinely different responsibility boundaries.

## 6. Writing Call Chains

Prefer:

```text
entryMethod()
  -> key decision A
  -> key collaborator B
  -> key state update C
  -> completion action after success/failure
```

Avoid:

```text
method1()
  -> method2()
  -> method3()
  -> method4()
```

The second example merely lists source-code jumps without explaining business meaning. A call chain must show:

- Why a branch is taken.
- Which state changes.
- Which actions cannot be reordered.
- How a failure is recovered or stopped.

### 6.1 What Belongs in a Call Chain

Prioritize flows that cross files, modules, stages, or state objects. Each arrow in the main chain should meet at least one condition:

- It crosses into another core class, module, or subsystem.
- It enters another business stage, such as resource outputs feeding the source stage or compiled outputs entering deployment staging.
- It reads or writes state that spans runs: caches, history, deploy state, staging, global context, and similar objects.
- It explains a branch, failure recovery, fallback, retry, asynchronous wait, or ordering constraint.
- It identifies an output's origin, consumer, or why the current file alone is insufficient.

Normally omit from the main chain:

- Method order that can be read directly within one class or file.
- Method jumps that do not explain state changes, output handoffs, branches, or failure containment.
- Details included only to prove that one method calls another.
- A compressed `methodA -> methodB -> methodC` copy of the source order.

Keep a step within one file only if:

- Another stage or file consumes its output.
- It changes cross-run state, caches, staging, history, deploy state, or a similar object.
- It has a non-obvious ordering constraint that another file or stage must respect.

For presentation:

- Write the main chain across boundaries, for example `SourceCompiler -> SourceDataBindingProcessor -> DataBindingGenMapperCompiler -> JavaCompilerInvoker`.
- Name the stable method at an important cross-file entry, state owner, or failure-recovery boundary when the class alone leaves multiple plausible starting points. Pair the method with the relevant decision, state change, or output; do not list every call.
- Show actual caller-to-callee order in a call chain. Label a sequence as data flow when its arrows describe conceptual output order rather than direct calls.
- Recast a necessary single-file step as a cross-file output or ordering contract, or as a core navigation entry; otherwise omit it rather than expanding method order.

## 7. Writing the Source Index

Include only classes AI must know to investigate the topic:

- Entry classes.
- Data models.
- State managers.
- Key strategy, planner, or transport implementations.
- Failure-recovery and compatibility handlers.

Describe each class's business responsibility, not a vague “utility for handling xxx.” Paths must reflect current code; a directory-level path must still let AI locate the class quickly.

For navigation-only entries, include main-flow entry points, owners of key state, and main failure-recovery entry points. Do not use presumed frequency as an admission criterion; a single task cannot establish it reliably.

## 8. Writing Hidden Constraints

Express hidden constraints as actionable judgments:

- “Commit only after the entire run succeeds.”
- “Scoped data is for transport only; it must not update global state.”
- “Once a writer is dirty, it cannot fall back to the old flow.”
- “Disagreement among history, cache, and device triggers recovery or reinstall.”

Avoid abstract principles:

- “Mind state consistency.”
- “Consider exceptional cases.”
- “The logic here is complex.”

### 8.1 Aggregated Errors and Diagnostic Meaning

When a user-visible error, log, or state aggregates several lower-level results, explain its interpretation boundary. Do not merely repeat the message or a root cause from one historical incident. Prefer:

| Observation | What it proves | What it does not prove | Next discriminating evidence |
|-------------|----------------|------------------------|------------------------------|
| User-visible error or aggregated state | The classification made by its producer | A lower-level cause not directly observed yet | Producer implementation, raw input, lower-level state, or exception stack |

Writing requirements:

- Distinguish raw evidence, derived results, and investigation conclusions; do not treat a wrapper message as an underlying fact.
- Record interpretation boundaries that hold across versions. Do not inflate a specific class name, error code, or fix commit from one Issue into a general rule.
- For version differences, specify where the current implementation, incident version, and historical implementation apply.
- “Absent from the log” has diagnostic meaning only when collection and search scope are clear. Do not infer that behavior never occurred solely from missing output.

### 8.2 Counter-Evidence Gate Before Conclusions

An investigation topic should require a minimal counter-evidence check before presenting a conclusion: state the leading explanation, observable evidence that could refute it, conflicting signals already seen, and the boundary where the conclusion applies. This gate constrains evidence completeness; it does not prescribe a minimum number of tool calls, reasoning tokens, or hypotheses to enumerate.

If direct evidence is missing, guide AI to present the smallest confirmed facts, the inference chain, and the missing evidence, instead of choosing between “fully certain” and “entirely unknown.”

## 9. Writing Investigation Entry Points

Give only the first hop, not a full investigation script:

| Symptom | First entry point |
|---------|-------------------|
| Specific log or user-visible symptom | Class / method / document section |

If investigation requires a fixed log or path, put it in the symptom. Do not turn the entire document into an FAQ.

In ordinary topic documents, keep a pure symptom-to-code jump only when its destination meets the core-entry criteria in §7. Diagnostic interpretations and evidence boundaries are different content; assess them under §8.1–§8.2. `09_plugin_runtime_debug.md` has its own investigation criteria and does not inherit this navigation-only gate.

## 10. Maintenance Process

1. Follow `AGENTS.md` reading order: `00_overview.md`, `99_index.md`, then `98_code_map.md` to locate the topic. Read this manual and only the relevant target pages before source code.
2. Mark mechanical indexes, code paraphrases, duplicate testing rules, and generic background. Check the current implementation for each affected capability, flow, state, and diagnostic claim; do not scan unrelated code.
3. For a targeted update, change only affected sections. For an explicit rewrite, classify the whole page, consolidate duplicates, and use the §4 structure where it helps.
4. Before removing, compressing, or replacing existing content in a targeted update or rewrite, record the destination of each **distinct fact or useful core navigation anchor**: retained in the changed page; already covered in a named current document; moved to an English comment beside its implementation owner; or excluded because code disproves it or a specific placement rule rejects it. Preserve an unverified claim with its uncertainty until it can be resolved. Audit diagnostic meanings and verification prerequisites separately. A retained behavior summary does not replace a useful method-level first hop, and searchable code or an existing test does not by itself replace diagnostic interpretation or a non-obvious verification condition. Put durable verification policy in `06_testing.md`.
5. Check every remaining paragraph against §2, every main-chain arrow against §6.1, and each error/conclusion against the interpretation and counter-evidence rules in §§8.1–8.2.
6. Check `99_index.md` and `98_code_map.md` when a topic, entry class, or path changes. Run `git diff --check` and spot-check named source paths, test owners, and commands.

## 11. Self-Review Checklist

Check each item before committing:

- Have low-value class/method lists been removed?
- Have line-by-line paraphrases of code been avoided?
- Is the source index for AI's essential first hops intact?
- Where a core boundary needs a method-level first hop, is its verified method still named?
- Are cross-class call chains or state machines clear?
- Do call-chain arrows reflect actual calls, with conceptual data flow labeled separately?
- Does each arrow in the main chain cross a file, stage, or state object, or explain an output/state/failure-recovery handoff?
- Has method order within one file been omitted unless it explains a cross-file output, state, or ordering contract?
- Are non-obvious code constraints, design intent, or known defects included?
- Does each hidden constraint have effects across files that cannot be understood from one file alone? Have local reasons been left beside the code only when they need explanation?
- Do navigation-only jumps lead to main-flow entry points, owners of key state, or main failure-recovery entry points?
- Is each cross-file constraint stated once in the most useful section rather than repeated?
- Does every distinct removed, compressed, or replaced fact or useful core navigation anchor have a verified destination, including diagnostic interpretation and verification prerequisites?
- Do aggregated errors explain what they prove, what they do not prove, and the next discriminating evidence?
- Must an investigation conclusion check counterexamples, conflicting signals, and scope?
- Is content already covered by another topic linked instead of repeated?
- Is every new claim supported by current code or known facts?
- Does `git diff --check` pass?

## 12. Delivery Standard

After restructuring, explain in one sentence how the page improved, for example:

- “AI can identify core classes and the main flow without reading code.”
- “The cross-class recovery state machine is condensed into one flowchart.”
- “The additions are mainly hidden constraints rather than code paraphrases.”

If you cannot do that, the document still needs pruning or restructuring.

## 13. Integrating Documentation From Another Branch

Before merging a branch into `develop/4.0`, identify the pre-integration base and run `python3 tools/check_english_first_diff.py --base <base-commit> --head <candidate-tip> --report /tmp/jugg-language-review.md`. After resolving the merge, rerun it against the final integration commit. The script reports only newly added or replaced Chinese lines; it is a review aid, not a CI failure based on character detection. Unchanged archives are outside its scope.

Review each reported row against **both** branch versions and current implementation. Record its final classification, English landing page (or exemption), and unresolved factual questions in the integration review. A Chinese current-rule or product-fact addition goes into the matching English `docs/ai/` topic or other maintained English source. Do not replace a branch's unique new facts with an older English translation. For an existing bilingual Wiki page, edit the English source and synchronize `docs/wiki/zh/` in the same integration. New non-English `docs/task/YYYY-MM/` records keep the reviewer's language and start with a concise English `Purpose / Decision / Impact` abstract; old task and `docs/superpowers` records remain archival evidence, not automatic authority. Localized pages, genuine Chinese diagnostics or parser matches, and meaningful test inputs are language exceptions, though their behavior still needs ordinary review.

For a proposed historical conclusion, check whether the behavior exists in current code and whether an English topic already covers it. Promote only a verified, durable constraint or investigation distinction; retain the original task record. Mark unimplemented proposals and claims lacking current evidence as unresolved instead of silently making them normative.
