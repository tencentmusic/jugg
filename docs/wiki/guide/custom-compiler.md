---
title: Custom compiler
description: Integrate a Jugg custom compiler, choose its execution order, configure its artifact, and troubleshoot failures.
status: active
tags:
  - guide
  - custom-compiler
---

# Custom compiler

A custom compiler inserts project-specific generation, transformation, or validation logic into Jugg's incremental-compilation flow. It is intended for teams that already have specialized compilation steps but do not want every run to fall back to a full Gradle build.

## Intended use

A custom compiler can add project-specific preprocessing or postprocessing to Jugg's incremental-compilation flow, such as:

- Consuming specific files before compilation so built-in compilers do not process them again.
- Running project-specific generation around resource, source, or DEX stages.
- Integrating an existing in-house transformation tool with Jugg's incremental flow.
- Validating compilation artifacts or producing supplemental output.

It has clear limits:

- Replacing the complete lifecycle of a Gradle plugin.
- Taking over installation, launch, Hot Reload, or deployment-state commits.
- Running work that blocks the IDE for a long time.
- Running compilation logic that requires manual UI interaction.

## Integration

A custom compiler is provided as a JAR and exposes its entry point through SPI:

```text
Custom JAR
  -> META-INF/services/com.sickworm.intellij.jugg.compiler.custom.ICompilerCreator
  -> ICompilerCreator.create(...)
  -> Return ICompiler
  -> Insert into a Jugg compilation stage according to CompileOrder
```

The JAR can come from:

- An absolute local path.
- A path relative to the project directory.
- An HTTP / HTTPS URL.

The configuration must include the JAR filename, path, and md5. Jugg verifies the md5. If an existing local JAR or a downloaded remote JAR does not match, it is not used as a valid custom compiler.

### Local project configuration

Without backend distribution, maintain these files in the project:

```text
build/jugg/config/
  custom_config.json
  custom_compilers/
    example.jar
```

Merge the custom compiler declaration into the existing configuration in `build/jugg/config/custom_config.json`:

```json
{
  "customCompilers": [
    {
      "jarFileName": "example.jar",
      "path": "build/jugg/config/custom_compilers/example.jar",
      "md5": "<md5 of example.jar>"
    }
  ]
}
```

`path` is relative to the project root, but it can also be an absolute local path. Placing a JAR in `custom_compilers` alone does not load it; declare it in `custom_config.json` as well. After saving the configuration, the next Jugg Run compilation reloads it.

## Execution order

A custom compiler selects its insertion point through `CompileOrder`:

| Insertion point | Common use |
|---|---|
| `atFirst` / `atLast` | Before or after the entire compilation run |
| `beforeAsset` / `afterAsset` | Before or after assets or native libraries are processed |
| `beforeRes` / `afterRes` | Before or after resources, Manifest, and R-related processing |
| `beforeSource` / `afterSource` | Before or after Java/Kotlin/DataBinding mapper processing |
| `beforeMinify` / `afterMinify` | Before or after release minification |
| `beforeDex` / `afterDex` | Before or after DEX generation |

A before hook can consume input files and affect subsequent built-in compilation. An after hook primarily processes built-in compilation artifacts.

## Recommendations

- Process only explicit file types or modules instead of scanning the whole project on every run.
- Return user-readable errors instead of throwing only an exception.
- Choose the narrowest `CompileOrder` interval to limit effects on other stages.
- Do not package dependency versions that conflict with the Jugg API.
- After a remote JAR update, the new instance is guaranteed to reload only on the next compilation run.

## Common problems

| Symptom | Action |
|---|---|
| A configured JAR does not take effect | Check the path and md5, and confirm that local `custom_config.json` or backend configuration is active |
| A remote JAR downloaded successfully but did not run in the current compilation | Trigger another compilation and confirm that the lazy-loading cache refreshed |
| `ServiceLoader` cannot find the implementation | Check `META-INF/services/...ICompilerCreator` |
| The compiler runs in the wrong stage | Check whether `ICompiler.order` falls within the intended stage interval |
| Custom compiler failure fails the entire run | Check warnings and exception summaries in `compile_latest.log` |

## Related pages

- [Compilation stages](./compile.md)
- [Custom compiler capability](../capabilities/compile/custom-compiler.md)
- [How custom compilers work](../concepts/incremental-compile/custom-compiler.md)
- [Compilation failed](../troubleshooting/compile-failed.md)
