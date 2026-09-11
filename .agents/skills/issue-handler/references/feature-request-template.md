# Feature Request Response Template

Use this template only for pure feature requests. If the Issue also reports broken existing behavior, handle that portion with the bug workflow instead of treating the whole Issue as a feature.

## Preparation

Before replying:

1. Identify the user scenario, desired outcome, and current workaround from the Issue and maintainer comments.
2. Inspect the relevant docs, implementation, and history to determine whether the capability is absent, partially supported, or already available through another workflow.
3. Separate confirmed current behavior from the proposed product behavior.
4. Identify the smallest user-visible scope that would make the request useful, including compatibility or fallback constraints only when they materially affect the design.

## Response Structure

Start with one concise paragraph that states:

- The understood user scenario and desired outcome.
- What Jugg currently supports or does not support.
- Whether this is a new capability, an extension of an existing capability, or a request already satisfied by another workflow.

Then adapt the following sections. Omit any section that adds no useful information.

### Proposed Behavior

Describe the product behavior from the user's perspective:

- What triggers or exposes the capability.
- What observable result the user receives.
- What happens when the capability cannot complete, if failure or fallback behavior is relevant.

Prefer the minimum coherent behavior over optional configuration or speculative extension points.

### Scope

State the proposed boundary when it prevents ambiguity:

- **In scope:** the minimum supported workflow, platform, or project shape.
- **Out of scope:** adjacent behaviors that are not required for the initial capability.

Do not invent exclusions when the Issue is already narrow and clear.

### Acceptance Criteria

List a small set of observable outcomes that would show the feature works. Describe user-visible behavior, compatibility, or stable external contracts rather than internal class structure or implementation details.

### Open Decisions

Ask only questions whose answers would materially change the product behavior or implementation scope. Prefer a concrete recommended default when the repository evidence supports one. Do not turn this section into a generic questionnaire.

If no blocking product decision remains, provide the assessment directly instead of requesting confirmation.

## Formatting Requirements

- Reply in the reporter's language.
- Keep the response concise and specific to the Issue.
- Do not request Jugg diagnostics, report IDs, crash logs, Gradle-versus-Jugg reproduction, or a reproducible Demo unless the Issue separately claims a bug.
- Do not describe an unsupported capability as a defect or search for a root cause for its absence.
- Do not promise acceptance, priority, implementation, or delivery timing without explicit maintainer direction.
- Do not repeat information already supplied in the Issue.
