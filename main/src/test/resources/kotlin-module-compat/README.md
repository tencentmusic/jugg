# Kotlin module metadata compatibility fixtures

These `.kotlin_module` files come from real versions of `kotlin-compiler-embeddable`, rather than newer metadata libraries writing fabricated old version headers. Each version compiles separate baseline and incremental sources containing top-level functions, extension functions, and `@JvmMultifileClass` parts. See `regenerate.py` for sources, commands, and Maven resolution. Ordinary JVM regressions read committed fixtures without downloading or running old compilers.

| Kotlin compiler | Actual metadata version |
|---|---|
| 1.3.72 | 1.1.16 |
| 1.4.32 | 1.4.2 |
| 1.5.31 | 1.5.1 |
| 1.6.21 | 1.6.0 |
| 1.7.22 | 1.7.1 |
| 1.8.21 | 1.8.0 |
| 1.9.23 | 1.9.0 |
| 2.0.21 | 1.9.9999 |
| 2.1.20 | 2.1.0 |
| 2.2.21 | 2.2.0 |
| 2.3.20 | 2.3.0 |
| 2.4.21 | 2.4.0 |

## Regeneration and cross-validation

Run the Python script from the repository root with JDK 21. It reuses local Gradle artifacts first and downloads missing Maven Central dependencies into a system temporary directory without changing project dependencies.

```bash
python3 main/src/test/resources/kotlin-module-compat/regenerate.py
# Gradle 7.3.3 requires JDK 17; set JAVA_HOME to its local installation.
./gradlew :main:test --tests 'com.sickworm.intellij.jugg.compile.KmModuleMergerTest'
python3 main/src/test/resources/kotlin-module-compat/regenerate.py --verify-merged
```

JUnit writes merged outputs to `main/build/kotlin-module-compat/<version>/compat.kotlin_module`. Cross-validation places each merged index alongside classes compiled with its original compiler, then compiles callers with that compiler to resolve old and new top-level and extension functions. Metadata version checking is not disabled.

## Backward compatibility evidence

The local `kotlin-metadata-jvm:2.4.10` sources JAR supplies these contracts:

- `org/jetbrains/kotlin/metadata/deserialization/MetadataVersion.kt` rejects `0.x`, `1.0`, and versions beyond its upper bound, while accepting historical versions across major versions.
- `org/jetbrains/kotlin/metadata/jvm/deserialization/ModuleMapping.kt` recognizes the pre-1.4 layout without flags and reads/writes versions from the file header.
- `kotlin/metadata/jvm/KotlinModuleMetadata.kt` serializes package parts using the caller's metadata version.

This regression protects Jugg's facade and multifile package-part indexes; it does not extend the merger's support for module annotations or optional annotation classes. One real patch version represents each minor series, as listed above.
