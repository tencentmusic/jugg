## ADDED Requirements

### Requirement: Source-stage APT rewrite pre-processing
The system MUST execute generated-source rewrite before Kotlin and Java source compilation within SourceCompiler.

#### Scenario: Rewrite runs before language compilers
- **WHEN** a module compile task starts with Kotlin or Java input files
- **THEN** JuggAptCompiler processes generated APT sources before KotlinCompiler and JavaCompiler task inputs are finalized

### Requirement: Rewritten files participate in the current compile round
The system MUST include every successfully rewritten generated source file in the same compile round as regular module sources.

#### Scenario: Kotlin rewrite output is consumed
- **WHEN** JuggAptCompiler returns rewritten Kotlin generated sources
- **THEN** those files are added to the Kotlin compile task for the current module

#### Scenario: Java rewrite output is consumed
- **WHEN** JuggAptCompiler returns rewritten Java generated sources
- **THEN** those files are added to the Java compile task for the current module

### Requirement: Rewriter scope is module-local
The system MUST limit generated-source discovery and rewrite to the current module build/generated paths and Jugg temporary generated-source paths for that module.

#### Scenario: Multi-module compile isolation
- **WHEN** multiple modules compile in one incremental session
- **THEN** module A rewrite step MUST NOT modify generated sources that belong to module B

### Requirement: Rewrite failure does not block source compilation
If any generated-source rewrite fails, the system MUST log a warning and continue the standard source compilation flow.

#### Scenario: Processor throws during rewrite
- **WHEN** a processor throws an exception while rewriting a generated source file
- **THEN** the compiler logs a warn-level message with processor and file context and continues Kotlin and Java compilation without aborting the module
