---
title: Wiki Elements Demo
description: Demonstrates every supported Markdown element for the Jugg Wiki.
visibility: dev
status: draft
tags:
  - dev
  - demo
  - markdown
---

# Wiki Elements Demo

> Last checked: 2026-06-14  
> Visibility: dev only. This page is excluded from production builds.

This page is a development-only showroom for Markdown and Jugg engineering wiki patterns. It should help reviewers verify rendering, spacing, search behavior, and future style changes.

[[toc]]

## 1. Text

Plain paragraphs support **bold text**, *italic text*, ***bold italic text***, ~~deleted text~~, and `inline code` such as `JuggManager` or `docs/wiki/.vitepress/config.mts`.

A hard line break can be written with two trailing spaces.  
This sentence starts on the next line.

## 2. Headings

### 2.1 Third-level heading

#### 2.1.1 Fourth-level heading

Headings generate page anchors and outline entries.

## 3. Lists

Unordered list:

- Compile
- Deploy
- Debug
  - Nested item
  - Nested item

Ordered list:

1. Read the page frontmatter.
2. Render the Markdown body.
3. Hide dev-only pages in production.

Task list:

- [x] Add demo page
- [x] Add dev-only frontmatter
- [ ] Review visual style in the dev server

## 4. Links

- Internal link: [Guide overview](/guide/)
- Relative link: [Log files](../reference/log-files.md)
- External link: [VitePress](https://vitepress.dev/)
- Anchor link: [Jump to alerts](#_9-alerts)

## 5. Table

| Element | Markdown syntax | Required |
|---|---|---|
| Heading | `# Heading` | Yes |
| Alert | `> [!NOTE]` | Yes |
| Task list | `- [ ] Task` | Yes |
| Image | `![alt](path)` | Optional |

## 6. Code

Inline code is useful for symbols like `JuggRunningTask`.

```kotlin
class WikiElementRenderer {
    fun render(page: WikiPage) {
        println(page.title)
    }
}
```

```bash
npm run dev
npm run build
```

```text
load markdown
  -> parse frontmatter
  -> render body
  -> exclude dev-only pages in production
```

## 7. Blockquote

> This is a regular blockquote. It is not an alert because it does not start with an alert marker.

## 8. Image

![Wiki elements demo illustration](./assets/wiki-elements-demo.svg)

## 9. Alerts

> [!NOTE]
> Notes provide neutral background information.

> [!TIP]
> Tips describe recommended practices.

> [!IMPORTANT]
> Important blocks highlight information that should not be missed.

> [!WARNING]
> Warnings describe risks that require attention before continuing.

> [!CAUTION]
> Caution blocks describe severe risks or destructive actions.

> [!WARNING]
> **Custom alert title**
>
> The first bold paragraph can be treated as a custom title by the wiki renderer if needed.

## 10. Divider

Content before the divider.

---

Content after the divider.

## 11. Raw HTML

<kbd>Shift</kbd> + <kbd>F10</kbd>

<details>
<summary>Expandable details</summary>

This block verifies native HTML details rendering.

</details>

## 12. Engineering Wiki Blocks

### Core Source Index

| Class or file | Path | Role |
|---|---|---|
| VitePress config | `docs/wiki/.vitepress/config.mts` | Owns nav, sidebar, search, and dev-only route exclusion. |
| Demo page | `docs/wiki/dev/elements-demo.md` | Shows every supported page element. |

### Flow

```text
wiki dev mode
  -> include /dev/ pages in routing
  -> show Dev navigation entry
  -> render Wiki Elements Demo

wiki production build
  -> exclude /dev/ pages from routing
  -> omit Dev navigation entry
  -> omit dev pages from local search index
```

### Hidden Constraints

- Dev-only pages must use `visibility: dev` frontmatter.
- Dev-only pages must live under `dev/` or `zh/dev/` so production builds can exclude them by path.
- Production builds must not expose dev pages through nav, search, or direct routes.

### Troubleshooting

| Symptom | First entry |
|---|---|
| Demo page appears in production nav | Check `isWikiDev` in `docs/wiki/.vitepress/config.mts`. |
| Demo page is directly reachable in production | Check `srcExclude` in `docs/wiki/.vitepress/config.mts`. |
| Alert style looks wrong | Check VitePress Markdown alert support and theme styles. |

## 13. Frontmatter Example

```yaml
---
title: Wiki Elements Demo
visibility: dev
status: draft
---
```
