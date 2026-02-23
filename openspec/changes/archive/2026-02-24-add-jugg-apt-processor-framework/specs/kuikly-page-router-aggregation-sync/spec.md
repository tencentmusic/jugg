## ADDED Requirements

### Requirement: Kuikly @Page candidates are discovered by lightweight text scan
The processor MUST identify changed page source files by scanning source text for @Page annotation tokens and extracting route and page class data with lightweight rules.

#### Scenario: Detect annotated page source
- **WHEN** a compiled source file contains a valid @Page annotation and page class declaration
- **THEN** the processor treats the file as a page candidate for router aggregation sync

### Requirement: Kuikly aggregation entry file is discoverable
The processor MUST locate Kuikly aggregation entry sources from module generated-source outputs, including build/generated/ksp/<variant>/kotlin and equivalent generated-source directories used by the compile context.

#### Scenario: Locate generated entry in standard KSP path
- **WHEN** KuiklyCoreEntry.kt exists under module build/generated/ksp/<variant>/kotlin
- **THEN** the processor loads that file as the aggregation target

### Requirement: triggerRegisterPages is the authority insertion target
The processor MUST target triggerRegisterPages as the single authority method for page registration insertion.

#### Scenario: Unique method target insertion
- **WHEN** the aggregation entry is loaded for rewrite
- **THEN** registration updates are applied only to triggerRegisterPages

### Requirement: Missing page registration is appended to triggerRegisterPages
For each candidate page not yet registered, the processor MUST append a register snippet to the end of triggerRegisterPages in the aggregation entry source.

#### Scenario: Append new page registration
- **WHEN** the aggregation entry lacks registration for the current page route and class
- **THEN** the processor appends BridgeManager.registerPageRouter("<route>") { <PageClass>() } at the end of triggerRegisterPages

### Requirement: Registration insertion is idempotent
The processor MUST prevent duplicate registrations by checking both route token and page class token before insertion.

#### Scenario: Existing registration is preserved
- **WHEN** triggerRegisterPages already contains registration for the same route and page class
- **THEN** the processor does not add another registration snippet

### Requirement: Kotlin and Java aggregation sources are both supported
The processor framework MUST allow Kuikly aggregation sync to rewrite both Kotlin and Java generated sources when the target entry format is recognized.

#### Scenario: Java aggregation source rewrite path
- **WHEN** the aggregation entry is provided as a Java generated source file
- **THEN** the processor emits rewritten Java source as compile input for the same compile round
