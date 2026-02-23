## Purpose
Define an extensible, deterministic, fail-open custom APT processor framework for generated-source rewrite.

## Requirements

### Requirement: JuggAptCompiler follows BaseCompiler contract
The generated-source rewrite compiler MUST be implemented as a BaseCompiler to reuse compile lifecycle, module split, and cancellation behavior.

#### Scenario: Compiler lifecycle consistency
- **WHEN** source compilation starts
- **THEN** JuggAptCompiler is invoked through the same compile(task) lifecycle contract used by other BaseCompiler implementations

### Requirement: Processor contract exposes compile context
Each custom APT processor MUST implement a process interface that receives ICompileContext, current module, all compile files, and discovered generated APT source files.

#### Scenario: Processor reads build and module context
- **WHEN** a processor needs module paths or variant metadata
- **THEN** it obtains required information directly from ICompileContext and module input parameters provided by process

### Requirement: Processors execute in deterministic order
JuggAptCompiler MUST execute registered IJuggAptProcessor instances in configured order and merge outputs deterministically.

#### Scenario: Ordered processor execution
- **WHEN** two processors are registered in order P1 then P2
- **THEN** JuggAptCompiler always runs P1 before P2 for the same module compile task

### Requirement: Same-file conflicts resolve by last successful result
If multiple processors rewrite the same generated source path, the system MUST keep the last successful rewritten result according to execution order.

#### Scenario: Conflict on one generated file
- **WHEN** P1 and P2 both emit changes for the same file and both succeed
- **THEN** the final compile input uses P2 output for that file

### Requirement: Base processor provides shared text rewrite utilities
BaseJuggAptProcessor MUST provide reusable helpers for annotation token matching, target-method tail insertion, and duplicate-snippet detection.

#### Scenario: Custom processor reuses duplicate guard
- **WHEN** a custom processor inserts registration code into a target method
- **THEN** BaseJuggAptProcessor utilities prevent inserting an identical snippet twice

### Requirement: Processor failure is fail-open with warning
JuggAptCompiler MUST treat processor runtime failure as warn-only and continue with remaining processors and downstream source compilation.

#### Scenario: One processor fails and another succeeds
- **WHEN** processor P1 throws an exception and processor P2 can still process files
- **THEN** the compiler logs a warn-level message for P1, executes P2, and keeps the source compile pipeline running
