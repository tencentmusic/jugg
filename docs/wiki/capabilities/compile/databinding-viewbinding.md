---
title: DataBinding/ViewBinding
description: Explains Jugg's incremental handling of DataBinding and ViewBinding layout changes.
status: active
tags:
  - capability
  - compile
  - databinding
  - viewbinding
---

# DataBinding/ViewBinding

Jugg supports incremental handling of DataBinding and ViewBinding layout changes. It converts layout changes into resource artifacts and generated sources, then continues with resource compilation and source compilation. This page covers only the support scope and usage boundaries. For the handoff between the two stages, see [Incremental DataBinding / ViewBinding compilation](../../concepts/incremental-compile/databinding-viewbinding.md).

## Supported capabilities

| Change type | Current support | User-visible result |
|---|---|---|
| ViewBinding layout change | Supported | Binding-related sources are updated and compiled |
| DataBinding layout change | Supported | Mapper, BR, and related sources are updated and compiled |
| `<include>` impact | Supported using layout info | Layouts affected through include relationships are included when generated sources are updated |
| Gradle layout info maintenance | Supported | Later Gradle builds can still access the required layout baseline |

> [!TIP]
> When enabling DataBinding/ViewBinding for the first time, upgrading AGP, or changing related Gradle configuration, run a Gradle build or Sync first so that intermediate artifact paths and layout info become the new baseline.

## Trigger and result

```text
DataBinding / ViewBinding layout changes
  -> Update resource-side artifacts
  -> Update binding-related generated sources
  -> Continue resource compilation and source compilation
  -> Apply results during deployment
```

The important point is that a layout change does not produce only a resource overlay. When the current run changes `R`, a binding class, or a mapper, Jugg also adds Java/Kotlin compilation. Multiple compilation stages in the log are expected.

## Key boundaries

- A regular layout does not necessarily enter the DataBinding mapper just because ViewBinding is enabled.
- The DataBinding mapper depends on layout info and the BR baseline produced by the latest Gradle build. Gradle must rebuild them when they are missing.
- Stripped XML is both a resource artifact and an input used by the source stage to determine mapper work. Java output alone does not show whether processing succeeded.

## Related pages

- [Resource compilation](./resource-compile.md)
- [Annotation processors](./annotation-processors.md)
- [Source compilation](./source-compile.md)
- [Incremental DataBinding / ViewBinding compilation](../../concepts/incremental-compile/databinding-viewbinding.md)
