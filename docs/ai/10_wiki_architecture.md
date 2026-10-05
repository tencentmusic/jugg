# Wiki Architecture and Operation

> Last verified: 2026-09-12
> Consistency rule: If documentation conflicts with code, code takes precedence.

---

## 1. Purpose of This Document

This page describes the Jugg user Wiki's project structure, local development, build preview, and publishing boundaries.

For article-writing rules, see `10_wiki_authoring.md`.

---

## 2. Core File Index

| File | Role |
|---|---|
| `docs/wiki/package.json` | Entry point for npm scripts to develop, package, and preview the Wiki; run all subsequent npm commands under `docs/wiki`. |
| `docs/wiki/.vitepress/config.mts` | VitePress site configuration: base/nav/sidebar/search, GA4 first-page tracking, and dev-only page exclusion. |
| `docs/wiki/.vitepress/theme/index.ts` | Extends the default VitePress theme, loads existing styles, and adds browser-side GA4 tracking for single-page navigation. |
| `.agents/skills/wiki-writer/scripts/validate_wiki.py` | Checks English/Chinese Markdown paths, nav/sidebar route order, relative links, configured routes, and build outputs. |
| `.github/workflows/wiki-pages.yml` | Builds and publishes GitHub Pages when the Wiki changes on `main`. |
| `.github/workflows/release.yml` | Builds an official GitHub Release from a version tag only when the tag commit is already in `main`, avoiding official releases from develop tags. |
| `.github/workflows/canary.yml` | Daily or manual check of the branch that triggered the run; builds only when its HEAD differs from the `canary-nightly` tag, then updates the Canary prerelease, plugin archive, and SHA-256. |
| `.github/workflows/dev.yml` | Manually triggered build verification only; builds the triggered ref as `<versionName>-dev.<date>.<run number>` and updates the rolling `dev-latest` prerelease, `jugg-dev.zip`, and SHA-256. |
| `docs/wiki/dev/elements-demo.md` | English dev-only element showcase page, used only for visual acceptance in development. |
| `docs/wiki/zh/dev/elements-demo.md` | Chinese dev-only element showcase page, used only for visual acceptance in development. |
| `docs/wiki/dev/assets/wiki-elements-demo.svg` | Sample image asset used on the demo page. |

---

## 3. Site Structure

The Wiki uses VitePress, with `docs/wiki` as its source root.

```text
docs/wiki/
  .vitepress/
    config.mts
  capabilities/
  concepts/
  guide/
  onboarding/
  reference/
  troubleshooting/
  zh/
    capabilities/
    concepts/
    guide/
    onboarding/
    reference/
    troubleshooting/
```

English pages live at the root route, while Chinese pages live under `/zh/`. English is the sole content source. After removing the `zh/` prefix, the English and Chinese Markdown path sets must match exactly; nav/sidebar hierarchy, order, and target pages must also be strict mirrors.

### 3.1 GA4 Page Tracking

`docs/wiki/.vitepress/config.mts` loads the Google tag in the page `head` and initializes GA4 with measurement ID `G-GNEQK6VECM`. That initialization tracks the first page.

Later VitePress route changes do not reload the browser page. `docs/wiki/.vitepress/theme/index.ts` extends the default theme and listens for browser-side route changes. Initialization covers the first route callback; subsequent callbacks invoke `gtag('config', ...)` again with the new path. When changing the measurement ID, update both files.

---

## 4. Dev-Only Page Rules

Place pages needed for visual acceptance but not for publication under the dev-only paths:

```text
docs/wiki/dev/
docs/wiki/zh/dev/
```

All four conditions must hold:

1. The file is under a dev-only directory.
2. Its frontmatter contains `visibility: dev`.
3. `docs/wiki/.vitepress/config.mts` excludes the path from production builds through `srcExclude`.
4. Nav/sidebar links appear only in dev mode.

Current dev-only detection:

```text
JUGG_WIKI_DEV=true or vitepress dev
  -> include dev pages

production build
  -> exclude dev/** and zh/dev/**
```

After a production build, confirm that the dist output contains no dev-only page titles.

---

## 5. Local Operation

The Wiki uses VitePress; run all npm operations from `docs/wiki`. Install dependencies after the first checkout or a dependency change:

```bash
cd docs/wiki
npm ci
```

Use the dev server while editing Wiki pages:

```bash
npm run dev
```

This starts the VitePress dev server by default and hot-reloads Markdown or configuration changes. To set a host or port, pass VitePress arguments after `--`:

```bash
npm run dev -- --host 127.0.0.1 --port 5173
```

Dev mode includes dev-only pages automatically because `isWikiDev` in `docs/wiki/.vitepress/config.mts` recognizes `vitepress dev`. Local visual acceptance can therefore use:

```text
/dev/elements-demo
/zh/dev/elements-demo
```

---

## 6. Production Build

Run a production build before publishing:

```bash
npm run build
```

Build output is written to:

```text
docs/wiki/.vitepress/dist/
```

Do not set `JUGG_WIKI_DEV=true` for a production build. The default configuration excludes these paths through `srcExclude`:

```text
dev/**
zh/dev/**
```

To check temporarily whether dev-only pages can build independently, run:

```bash
JUGG_WIKI_DEV=true npm run build
```

Use that command only for development acceptance, not to produce a publication artifact.

---

## 7. Preview the Build Output

`npm run dev` previews the source in development mode. Before publishing, also preview the generated static output:

```bash
npm run preview
```

To set a host or port:

```bash
npm run preview -- --host 127.0.0.1 --port 4173
```

`npm run preview` reads `docs/wiki/.vitepress/dist/`, so run `npm run build` first.

---

## 8. GitHub Pages Publishing

GitHub Pages uses the project-site path:

```text
https://tencentmusic.github.io/jugg/
```

`.github/workflows/wiki-pages.yml` runs when `docs/wiki/**` or the workflow itself changes on `main`, and it also supports manual dispatch. In `docs/wiki`, the build job runs `npm ci` and `npm run check:homepage`. After the production build and homepage-render check, it publishes `.vitepress/dist` as a Pages artifact.

The public VitePress path is controlled by `JUGG_WIKI_BASE`:

```text
GitHub Pages build -> JUGG_WIKI_BASE=/jugg/
default local build -> /
```

Do not hardcode `base` as `/jugg/`: that would make local builds incorrectly reference `/jugg/assets/**`. Before the first publication, set Source to `GitHub Actions` under `Settings -> Pages` in `tencentmusic/jugg`, then manually run `Deploy wiki to GitHub Pages` or push a Wiki change to `main`. GitHub does not automatically redirect the Pages address of the repository's previous owner to the new address.

Verify GitHub Pages publication by:

1. Confirming both the build and deploy jobs of `Deploy wiki to GitHub Pages` succeeded in Actions.
2. Opening `/jugg/`, `/jugg/zh/`, and at least one body page in each language.
3. Checking that CSS, JavaScript, font, and image requests use `/jugg/assets/**` or the appropriate `/jugg/` subpath.

---

## 9. Public Plugin Downloads

Official and Canary releases have different publishing semantics:

- A version tag triggers `release.yml` for an official release; it builds only if the tag commit is in `main`, and each version receives a separate GitHub Release.
- `canary.yml` updates the movable `canary-nightly` tag and overwrites the `Jugg Canary` prerelease.
- Canary Actions artifacts are retained for only 14 days and serve build investigations; public download links must target a GitHub Release asset, not a workflow-run page.
- README and Wiki use the fixed Canary Release asset URL, so the page link need not change after each build.
- `release.yml` must exclude the Canary tag, so the official-release workflow does not try to validate a rolling tag as a version number.

Canary is republished only when the triggering branch has a new commit. Its version is `${baseVersion}-canary.<date>.<run>`. Canary may contain changes without full verification; download pages must clearly identify it as unstable.

## 10. Related Documents

- `10_wiki_authoring.md`: Rules for ordinary Wiki articles.
- `docs/wiki/.vitepress/config.mts`: Site configuration, routes, navigation, and production-exclusion rules.
