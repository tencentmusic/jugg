---
title: Custom compiler
description: Explains how project-specific generation, transformation, or instrumentation tasks connect to Jugg through SPI and process inputs and artifacts at the correct incremental compilation stage.
status: active
tags:
  - concept
  - compile
  - custom-compiler
---

# Custom compiler

Large Android projects often add specialized steps outside standard compilation. A changed PB file may need to regenerate protocol code and package a JAR, a configuration file may need to become source code, or compiled classes may require project-specific instrumentation. A full Gradle build runs these steps through plugins or tasks, while the built-in Jugg incremental path handles only supported Android inputs.

A custom compiler connects these project-specific actions to Jugg. A team packages an existing generator, transformer, or validation routine into an extension JAR and declares whether it runs at the resource, source, minify, or DEX stage. After related files change, Jugg can continue generating current incremental artifacts instead of returning directly to a full Gradle build for one project-specific step.

## How additional Gradle actions enter the incremental flow

A complete Gradle build orders project-specific steps and standard Android compilation through task dependencies:

```text
project inputs such as PB / configuration / templates
  -> Gradle plugin or custom task
  -> intermediate artifacts such as generated source, resources, classes, or JARs
  -> standard stages such as Java / Kotlin, aapt2, or D8/R8
```

Jugg does not execute the complete Gradle task graph and cannot infer inputs, outputs, and timing from arbitrary tasks. A custom compiler gives that knowledge to the project itself: the extension identifies relevant files, invokes the correct tool, produces defined artifacts, and selects where it joins the built-in compilation path.

This division is already used for two kinds of project flow: packaging a protocol JAR after protocol files change, and adding project-specific instrumentation to incremental classes. The first supplies a generation step missing before standard source compilation, while the second processes classes already produced by built-in stages.

## How SPI loads a project extension

A custom compiler is delivered as a JAR and exposes its creation entry through JVM `ServiceLoader`. Jugg selects a local JAR from project configuration or loads the implementation after a remote JAR download completes:

```text
project custom compiler configuration
  -> locate a local JAR or download a remote JAR
  -> validate md5
  -> create an extension ClassLoader
  -> ServiceLoader discovers ICompilerCreator
  -> create custom compilers for the current project context
```

The JAR provides implementations, while `CompileOrder` determines when they run. One JAR can provide multiple extensions that participate in different stages.

## before and after connect inputs and artifacts

Each custom compiler declares an insertion point, which places it before or after a built-in stage:

| Insertion point | Semantics |
|---|---|
| `atFirst` | Process current inputs before the main built-in stages begin |
| `beforeAsset` / `afterAsset` | Before or after the assets and native library stage |
| `beforeRes` / `afterRes` | Before or after the resource stage |
| `beforeSource` / `afterSource` | Before or after the Java / Kotlin / class input stage |
| `beforeMinify` / `afterMinify` | Before or after release minification handling |
| `beforeDex` / `afterDex` | Before or after the DEX stage |
| `atLast` | Process final incremental artifacts after the main built-in stages finish |

A before extension processes current inputs first and decides which files continue to the built-in compiler. A protocol extension, for example, can consume project-specific input and invoke an existing packaging tool so that the same file does not enter an unsuitable built-in stage. An after extension runs only after the built-in stage succeeds. It receives artifacts from that stage and is suitable for instrumentation, transformation, validation, or additional output.

Choosing an insertion point requires considering both the input form and artifact destination. Protocol generation and resource preprocessing usually run before their corresponding built-in stages. An extension that handles classes runs after classes are produced and before DEX generation. The extension must also return the current compilation result explicitly; selecting `CompileOrder` alone does not make files from an arbitrary output directory enter the next stage automatically.

## Extension loading and failure boundaries

After an extension JAR enters the Jugg process, it follows these boundaries:

- **A remote JAR participates only after downloading completes**: The download runs in the background. An extension not ready when the current Run begins is skipped for that Run; after download and validation complete, it is loaded on the next Run.
- **md5 only checks that configuration matches the file**: Both local and downloaded files must match the md5 in project configuration. A JAR that fails validation does not participate in compilation. md5 does not provide code isolation or prove a trusted source.
- **Load only team-trusted extensions**: A custom compiler runs inside the Jugg process, can access compilation context, and can invoke project tools. It is not a restricted script environment.
- **The extension ClassLoader still inherits the Jugg API**: The custom JAR can use the types required by SPI and can also conflict with same-named dependencies already loaded by Jugg. Extensions should avoid packaging conflicting dependencies repeatedly.
- **An extension failure stops the current incremental task**: An exception becomes a user-visible compilation failure instead of silently skipping project-specific processing. The exception does not propagate further and terminate the IDE process.
- **Custom compilers supplement incremental stages**: Logic that requires the complete Gradle lifecycle, cross-task orchestration, or cannot define inputs and outputs still belongs in Gradle.

## Related pages

- [Incremental compilation overview](./index.md)
- [Custom compiler capability](../../capabilities/compile/custom-compiler.md)
- [Custom compiler guide](../../guide/custom-compiler.md)
