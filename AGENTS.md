# Jugg AI knowledge base

## Language and coding requirements

- Write code comments in English; Chinese comments are not allowed. Write new current maintainer documentation in English. Until the knowledge-base migration is complete, read the existing Chinese documents at `docs/ai_knowledge/`; do not treat the future `docs/ai/` path as available yet. Respond in Chinese when the user communicates in Chinese.
- Write new Wiki facts in the English root page first, then synchronize the Chinese `docs/wiki/zh/` mirror in the same task. Keep both routes and structures aligned.
- Keep code and structure as simple as possible (Occam's razor). Prefer the smallest direct implementation and avoid unnecessary cognitive load.
  - Do not expose internal implementation details solely for external calls. Do not extract an interface for a class with one implementation and one method.
  - Do not add classes, objects, or database entities without a clear business or architectural need.
- Follow the best-effort principle:
  - For confirmed version differences, external APIs, toolchains, or incomplete data, use an implementation compatible with the current environment. If unavailable, reuse an existing fallback with the same contract. Fail explicitly when no minimum valid result is possible.
  - Contain failures of auxiliary information or enhancements locally: omit only the affected capability and preserve other valid results.
  - Change only the factor causing a failure when falling back, such as the implementation, an incompatible capability, a parameter, or a safe default. Reuse the original flow and preserve result and state contracts.
  - Retry only known, recoverable errors. A retry must change the failure condition and have a limit; without a specific design, retry at most once.
  - Record the failure cause and fallback at the scope affected. If the fallback also fails, preserve the final exception; never swallow errors or fabricate success.
- Follow YAGNI:
  - Implement only confirmed requirements, compatibility differences, and failure modes. Do not preemptively add configuration, extension points, data structures, or general frameworks for hypothetical cases.
  - For a bug fix, locate the actual behavior owner and failure boundary; change only the logic needed to solve the problem. Do not mix in unrelated refactoring, cleanup, optimization, or behavior changes.
  - Compatibility logic needs an explicit trigger. Adapt at the input, read, call, or serialization boundary where possible; preserve the original path when the trigger does not apply.
  - Preserve existing call chains, public contracts, state semantics, and data formats where possible. When old data can be recovered deterministically, read it compatibly or provide a safe default instead of invalidating, migrating, or rebuilding it unnecessarily.
  - Prefer existing types and flows, local conditions, parameters, or small private methods. Keep new state in the narrowest scope. Add an English comment when a compatibility reason is not obvious. Do not add abstractions or configuration for a single fix without a clear business or architectural boundary.
  - Match the change surface to the problem. Verify both the failure behavior and the normal path where the patch does not apply. Follow `06_testing.md` when deciding whether and where to add automated tests.
- Verify before implementing: obtain failure evidence first. Use TDD for behavior that passes the test-value gate, preferably isolating external dependencies with Mockito. Do not change production code solely to enable a test.
- Keep code structure clear and expressive. As a rule, new methods should have fewer than four nesting levels and at most 50 lines.
- Prefix interface names with `I`; name a default implementation by removing that prefix, for example `IDeployHistoryManager` and `DeployHistoryManager`.
- Save new task plans and investigation records in `docs/task/YYYY-MM/` for their creation month. Write the body in the language in which the actual reviewer can judge it most accurately; if no reviewer is specified, use the current user's language. When the body is not English, begin with a concise English `Purpose / Decision / Impact` abstract for search and cross-language handoff. The abstract does not replace review of the full body. If the reviewer needs a full English plan, write the body in English. Do not keep a second full-language mirror of a historical task record. Move durable product facts and architectural constraints into the current English maintainer documentation when the task is done.
- When integrating a branch into `develop/4.0`, run `tools/check_english_first_diff.py` on the incoming diff and record each new Chinese item's classification, English landing page or exception, and unresolved facts. Preserve branch-only product facts while verifying them against current code; old task records, localization, diagnostic matchers, and meaningful test inputs are not automatic language violations. Follow `docs/ai/97_maintenance_manual.md` §13.
- For Kotlin, prefer non-null types and optional parameters over new overloads. Do not declare an optional parameter merely when every caller already supplies it.
- When a log call is long, break lines only at `+` in the message string. Indent continuation lines eight spaces relative to the call, and keep the exception argument on the same line as the last message segment. Do not split a simple log call at the opening parenthesis, message, exception, and closing parenthesis:
  ```kotlin
  logger.warn("message bla bla bla" +
          "details", exception)
  ```
- After adding or changing logs, compare their formatting in this diff against the example before committing. Do not defer to generic Kotlin formatting.
- Use `JuggLogger` for logging:
  - `error`: never use.
  - `warn`: user-visible unexpected failure.
  - `info`: user-visible key flow that should appear in output.
  - `debug`: developer diagnostics in the log file, not user-visible.
  - `trace`: high-frequency logging behind a switch, off by default.

## Testing and verification

> [06_testing.md](docs/ai_knowledge/06_testing.md) is the sole authority for verification evidence, test value, L0–L3 layers, TDD, test placement, and existing-test maintenance.

- Every development task needs verification evidence matching its risk. Automated tests are only one possible form of evidence.
- Apply the test-value gate before adding or retaining an automated test. A test should protect independent, stable, observable behavior that a real change could break. Do not normally test pure implementation details, simple pass-through logic, or behavior without an adjudicable result. The value gate takes precedence over formal TDD and test-layer requirements.
- For a feature or bug fix, first obtain failure evidence. If there is a valuable automated assertion, identify the behavior owner, write a failing test, then change production code. If automation would bind to implementation details or require a test-only seam, do not add the test; record the reproduction, the reason, and alternative verification.
- For optimization or refactoring, identify existing regression owners first; add a test only for stable behavior that lacks protection. Changes to deploy/compile orchestration require L3 or an existing equivalent Flow regression.
- Do not add test-only `provider`, `supplier`, `factory`, or `override` lambdas, function-type parameters, mutable closures, or default lambda parameters to production code merely for mocking. Replace external dependencies through a business-meaningful interface or class and normal dependency injection.
- Run the selected targeted tests or alternative verification after development. Never run unfiltered `:main:test` or `:idea:test`; compilation can use `./gradlew :idea:compileKotlin`.

## Commit conventions

1. After completing a task, commit only its changes. Write the message in English with title `[prefix] subject`; the subject starts with a lowercase letter and has no trailing period.
2. Choose the prefix by user-observable behavior:
   - `[bugfix]`: an existing capability has a defect, unexpected behavior, or a result contrary to expectations.
   - `[feature]`: a new user-visible capability.
   - `[optimize]`: an observable gain in readability, fault tolerance, reliability, convenience, performance, or time for behavior that was already correct and usable; not for build-system optimization.
   - `[refactor]`, `[docs]`, `[test]`, and `[other]`: no-behavior refactoring, documentation only, tests only, and other changes respectively.
3. Prefer a title describing the user scenario and observable outcome, not internal implementation. If there is no direct user, write from the caller, maintainer, Agent, or operator perspective. A `[bugfix]` title normally follows `[bugfix] fix <problem manifestation> when/after/for <scenario>`, not `prevent ... from ...` by default. A `[optimize]` title should describe the improvement for a scenario; wording alone must not disguise a bug fix as optimization.
4. If the title cannot explain both cause and implementation, add a natural-language body after a blank line. Use `Problem:`, `Cause:`, or `Solution:` headings only for longer bodies.

## Reading a GitHub Issue URL

- For a URL of the form `https://github.com/{owner}/{repo}/issues/{number}`, first run `python3 tools/fetch_github_issue.py <issue-url>`.
- The script reads the Issue, comments, labels, status, and Jugg Report ID; it performs no GitHub writes.
- Supply a token only through the `GITHUB_TOKEN` environment variable. Never put it in the repository, command arguments, Issue, or conversation.
- If the script fails, use the built-in Browser as a fallback. Never invent Issue contents because the script failed.

## Runtime investigation

For plugin runtime problems such as incremental compilation failure, Android runtime crashes, deployment failure, or unexpected flow:

1. Read `docs/ai_knowledge/09_plugin_runtime_debug.md` for log structure and common root causes.
2. Following its path rules, read `{projectDir}/build/jugg/log/compile_latest.log` for the compilation context and failure.
3. Compare `[ClassName]` tags and timestamps with the relevant incremental-compilation knowledge to identify the root cause. Do not rely only on the surface error message.

## Mandatory AI workflow

**Complete steps 1 and 2 in order for every task. Do not read code or use Edit/Write tools before completing step 1 and the documentation location in step 2. At task completion, output the checklist in step 3.**

Even when a question names a path outside this project (such as `AndroidStudioProjects/`), do not skip this workflow. Errors such as missing `R` classes, dex merge failures, or Gradle upgrade failures may involve Jugg incremental compilation; read the documentation before concluding relevance.

### 1. First read in a new session

- [00_overview.md](docs/ai_knowledge/00_overview.md)
- [99_index.md](docs/ai_knowledge/99_index.md)

### 2. Investigate by task

- First locate paths and classes in [98_code_map.md](docs/ai_knowledge/98_code_map.md).
- Then follow the recommended search order and topical catalog in `99_index.md`; read only the relevant topic, never the whole knowledge base at once.
- For API capability or behavior judgments, inspect the corresponding implementation rather than relying only on documentation.

List every document actually read in steps 1 and 2 under `Basis` in the step 3 checklist.

### 3. Execute and respond

Every completed task must include this exact section. Combine verification evidence according to task type; omit meaningless `N/A` entries:

```text
### 📋 Task execution checklist
- Basis: [documents actually read; key code, logs, or other location evidence]
- Verification: [failure evidence; test-value judgment; test owner/layer or alternative verification; final result]
- Documentation: [synchronized content and consistency checks / no functional or architectural change]
- Commit: [commit hash + message / no changes produced]
```

### 4. Update documentation when needed

For a functional or architectural change, update the [knowledge base](docs/ai_knowledge) and check [docs/wiki](docs/wiki) for affected user pages; update those pages too when applicable.
