---
name: feature-development
description: Explore and plan a new user-visible product feature before implementation. Use only when the user explicitly asks to add new product behavior or capability. Do not use for documentation maintenance, repository organization, bug fixes, refactoring, optimization, dependency or build maintenance, or changes to existing engineering workflows.
---

# Feature Development

Apply a mandatory review gate before implementing a new user-visible product feature.

## Workflow

### 1. Explore

- Read all applicable `AGENTS.md` files and the project documentation required by them before reading or editing source code.
- Inspect the current behavior, relevant modules, implementation owners, dependencies, and existing validation coverage.
- Separate verified facts from assumptions and unresolved questions.
- Collect concrete evidence from the repository. Do not edit implementation files during this phase.

### 2. Design

- Design the smallest solution that satisfies the confirmed request.
- Follow the project's existing architecture, coding conventions, compatibility rules, validation policy, and documentation requirements.
- Reuse existing types and workflows. Avoid unrelated refactoring, speculative extension points, and unnecessary abstractions.
- Describe the proposed behavior, implementation approach, important tradeoffs, risks, and validation strategy.

### 3. Present the Review Scope

Provide a review-ready change list containing:

- Each affected module and why it must change.
- Each existing file expected to change and the responsibility of that change.
- Each new file, if any, and why it is necessary.
- Expected test, verification, and documentation changes.
- Explicit assumptions, open questions, and items excluded from scope.

Use exact repository paths whenever they are known. End by requesting explicit approval of the proposed scope. Do not begin implementation in the same turn.

### 4. Implement Only After Approval

- Treat only an explicit approval of the proposed change list as review approval. Silence, partial feedback, or the original implementation request is not approval.
- If feedback changes the scope, revise the design and change list, then request approval again.
- Before editing any implementation file, save the approved design and change list under `docs/task/YYYY-MM/`, using the plan's creation month. Treat it as the implementation record and keep it aligned with later approved scope changes.
- After approval, modify only the approved scope, follow the project's required development workflow, run risk-appropriate verification, and report the results.
- If implementation reveals a material unreviewed change, stop and return that change for review before continuing.
