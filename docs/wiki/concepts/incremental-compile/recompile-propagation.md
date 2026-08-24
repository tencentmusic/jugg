---
title: Recompilation
description: Explains why source structural changes can invalidate old callers and how Jugg recompiles affected source over successive rounds.
status: active
tags:
  - concept
  - compile
  - recompile
---

# Recompilation

A source change can delete a method, change a field type, or add an abstract method to an abstract base class or interface. The directly changed file can compile successfully while callers or subclasses still present in the old APK continue to use the previous method signature, field type, or abstract method set. If only directly changed source is compiled, execution can later throw `NoSuchMethodError`, `NoSuchFieldError`, or `AbstractMethodError`.

After the first compilation round, Jugg continues to analyze the impact scope and adds source that must adapt to the new structure to the next round. This page calls the process impact propagation, which is also referred to as recompilation in logs and other pages.

## Direct changes can compile while old callers still fail

Suppose class A deletes a method and class B still calls it, but only A was changed and compiled in the current Run:

```text
A deletes a method
  -> unchanged B is not compiled
  -> the first compilation of A succeeds
  -> the device still contains B compiled against the old signature
  -> B throws NoSuchMethodError when that call executes
```

Adding an abstract method to an abstract base class or interface has the same risk. The definition can compile while an old subclass or implementation does not implement the new method and may throw `AbstractMethodError` at runtime.

The absence of an error in the first round therefore proves only that direct inputs are valid, not that callers in the old APK have adapted to the structural change.

## Why Jugg does not recompile the whole module

Compiling only directly changed files misses old callers, while recompiling the entire module every time sends large amounts of unrelated source back through the compilation path. Jugg compares old and new classes, converts only confirmed differences in methods, fields, added abstract methods, and class-level generic signatures into propagation signals, and then finds source that must be recompiled from historical references.

Queries use artifacts from the latest trusted Gradle build and later successful deployments. The index records method calls, field accesses, and inheritance relationships, allowing Jugg to work backward from “what changed” to “who still depends on the old structure.”

## How Jugg finds affected source

After source in the first round produces DEX, Jugg compares the new class with the old baseline class, converts structural changes into impact signals, and queries callers and inheritance relationships:

```text
first source compilation succeeds
  -> compare old and new class structures
  -> find callers, field accessors, and subclasses in the reference index
  -> map matching classes back to source
  -> add those source files to the next compilation round
```

Different structural changes propagate in different directions:

| Structural change | Old code to inspect |
|---|---|
| Method deletion, signature change, or access change that affects invocation | Source that calls the method; instance methods also require inheritance relationships |
| Field deletion, or a type change that removes the old field signature | Source that accesses the old field |
| Abstract base class or interface adds an abstract method | Subclasses and implementations |
| Class-level generic signature changes | Direct member callers and subclasses in the inheritance chain |

Changing only a method body does not produce these signals and therefore does not trigger caller recompilation. This decision controls only source propagation. Whether the current class ultimately takes effect through online replacement, hot fix, or restart is still determined by deployment conditions.

## Why impact can propagate to another round

Recompiling affected source can produce another structural change. For example, a change in A can require B to compile, and the new artifact for B can then change a structure used by C. Jugg cannot discover C until it analyzes B's new artifact.

```text
structural change in A
  -> add B to the second compilation round
  -> new B artifact produces another impact signal
  -> add C to the next round
  -> stop when no new affected source remains
```

Jugg recalculates impact after every successful round instead of assuming the first round can enumerate every file. The same propagation source is deduplicated; another round begins only when a new structural change or impact source appears.

## How propagation avoids expanding without control

Impact propagation does not follow every reference unconditionally:

- An instance method can affect subclasses through virtual dispatch, while a static method checks only direct callers and does not propagate down the subclass tree. This prevents lambdas and compiler-generated static methods from pulling an entire inheritance tree into compilation.
- `R$...` resource classes do not participate in method or field propagation. Resource repair produces many field differences; treating them as ordinary source structural changes would cause large amounts of unnecessary recompilation.
- Repeated propagation sources are filtered so that the same file does not move back and forth between rounds.
- If propagated source or modules exceed the incremental compilation scope, Jugg stops expanding the current Run and falls back to a Gradle build.

## When analysis results become trusted history

Impact analysis produces pending results for the current Run, not state that already took effect. New class structures and reference history are committed for the next incremental compilation only after compilation and deployment both succeed.

If later compilation, deployment, or user cancellation prevents the Run from completing, history does not advance early. The next compilation can rediscover the same impact from the previous trusted state, preventing a failed Run from moving the index to a version that never took effect on the device.

## What ordinary structural propagation does not cover

Impact propagation depends on references in the baseline APK and deployed artifacts. A Gradle build must recreate the index after switching the build variant, changing build configuration, or another condition makes the baseline untrusted.

Generic propagation also has limits. It covers subclass declaration chains and callers with direct method or field references in DEX. An indirect source-only constraint with no direct member reference in DEX is not guaranteed to match.

Compile-time constants and release minification have separate impact sources:

- `const val` and `static final` values can be inlined directly as literals. Ordinary method and field reference indexes cannot see those users, so [constant reference analysis](./const-ref.md) handles them separately.
- R8 / ProGuard can inline methods or remove members. [Release incremental compilation](./release-compile.md) performs bytecode compensation for those cases instead of ordinary source propagation.

## Related pages

- [Constant reference analysis](./const-ref.md)
- [Source incremental compilation](./source.md)
- [Deployment data and impact analysis](../deploy-data-and-impact.md)
- [Recompilation capability](../../capabilities/compile/recompile-propagation.md)
