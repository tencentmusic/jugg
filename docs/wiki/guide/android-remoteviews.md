---
title: Android RemoteViews
description: Write incremental changes into the APK so they take effect in RemoteViews such as home-screen widgets and custom notification views.
status: active
tags:
  - guide
  - remoteviews
  - apk
---

# Android RemoteViews

RemoteViews such as home-screen widgets and custom notification views read resources and layouts from APK content. A normal incremental deployment may update only the runtime overlay, which system processes cannot read.

## When to enable it

Enable the RemoteViews APK embedding mode when:

- After changing a widget layout or custom notification layout, the change appears in the app but not in the system component.
- A drawable, value, or layout used by RemoteViews does not update as expected.
- You know the current changes must be written into APK content.

The entry point is in More Options:

```text
Embedded to APK(for Android RemoteViews)
```

The first time you enable it, Jugg displays a confirmation dialog. Jugg then writes incremental changes into the APK and signs it again, which makes deployment slower.

## Disable it afterward

Disable the option after verifying the RemoteViews scenario. Normal screen development usually does not require embedding every incremental change back into the APK.

## Related pages

- [Run an app](./run.md)
- [Export an incremental APK](./export-incremental-apk.md)
- [Resource compilation](../capabilities/compile/resource-compile.md)
