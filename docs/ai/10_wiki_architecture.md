# Wiki Architecture and Publication Boundaries

> Last verified: 2026-10-07
> Consistency rule: If documentation conflicts with code, code takes precedence.

## 1. Scope and Owners

`docs/wiki/` is the VitePress source root for Jugg's user documentation. This page records site routing, local verification, and publication constraints. Article content and English-first mirroring rules are in `10_wiki_authoring.md`.

| Boundary | Owner |
|---|---|
| Scripts and locked dependencies | `docs/wiki/package.json`, `docs/wiki/package-lock.json` |
| Routes, locales, assets, dev exclusion, analytics | `docs/wiki/.vitepress/config.mts`, `docs/wiki/.vitepress/theme/index.ts` |
| Mirror, link, nav, and built-route checks | `.agents/skills/wiki-writer/scripts/validate_wiki.py` |
| Rendered home-page regression | `docs/wiki/scripts/check-homepage-render.mjs` |
| Pages build and deployment | `.github/workflows/wiki-pages.yml` |
| Plugin download assets linked from Wiki | `.github/workflows/release.yml`, `.github/workflows/canary.yml`, `.github/workflows/dev.yml` |

## 2. Route and Content Model

English pages use root routes, and their Chinese mirrors use `/zh/`. After stripping `zh/`, Markdown path sets must match. `config.mts` defines separate English and Chinese navigation/sidebar trees that should preserve the same route hierarchy and order. `validate_wiki.py` compares route order after locale-prefix removal, checks configured routes and relative links, and can assert built routes or text when given expectation flags. A passing default validator establishes those structural checks, not translation equivalence of the prose. The site's `cleanUrls: true` means links use page routes without `.html`.

`config.mts` also treats historical `zh/articles/` content specially: bare HTML-like tokens are escaped and older relative image sources are normalized during Markdown rendering. This is scoped to Chinese historical articles; a generic Markdown change should not be inferred to share that behavior.

Development showcase pages live under `docs/wiki/dev/` and `docs/wiki/zh/dev/`. `JUGG_WIKI_DEV=true` or `vitepress dev` adds their nav/sidebar entries and disables production `srcExclude`; ordinary builds exclude `dev/**` and `zh/dev/**`. The demo pages carry `visibility: dev` frontmatter as an authoring convention, but the build gate is their path plus `config.mts`, not a frontmatter parser. Keep English/Chinese dev paths mirrored. An accidental `JUGG_WIKI_DEV=true` on a publication build can include those pages.

GA4's measurement ID appears in both `config.mts` and `theme/index.ts`. The head script initializes the first page; the theme's client-side route callback tracks later VitePress navigation. If the ID changes, update both owners. The first callback is intentionally skipped to avoid a duplicate initial page event.

## 3. Local and CI Verification

Run npm commands from `docs/wiki/` after `npm ci`. `npm run dev` serves source pages and includes the dev showcase. `npm run build` produces `.vitepress/dist/` with the default local base `/`; `npm run preview` serves that already-built output. `npm run check:homepage` performs a production build and then checks the rendered English/Chinese home-page assets and CSS assumptions. `JUGG_WIKI_DEV=true npm run build` is a development-only acceptance build, not a publication artifact.

For content or route changes, use `.agents/skills/wiki-writer/scripts/validate_wiki.py` from the repository root to check mirrors, navigation order, source links, and configured routes. Pass its `--expect-html-route`, `--expect-removed-route`, or `--expect-html-text` options after a build when checking a route migration; it does not build the site itself. A green VitePress build alone does not establish locale mirror or route-order consistency. A green validator alone does not establish that the home-page components rendered correctly, which is why CI runs `check:homepage`.

## 4. Publication and Download Boundaries

`wiki-pages.yml` runs on `main` when `docs/wiki/**` or the workflow changes, and supports manual dispatch. Its build job runs `npm ci` and `npm run check:homepage` under `docs/wiki/` with `JUGG_WIKI_BASE=/jugg/`, uploads `.vitepress/dist/`, and the dependent job deploys it through GitHub Pages. The configured public project-site path is `https://tencentmusic.github.io/jugg/`. Local builds intentionally use `/`; hardcoding `/jugg/` into VitePress config would break their asset URLs. For a publication check, inspect both Actions jobs and load root, `/zh/`, and one body route per language with assets under `/jugg/`.

Wiki installation links point to GitHub Release assets, not Pages or expiring Actions artifacts. Official `release.yml` builds a version tag only when its commit is reachable from `main` and its version matches `build.gradle`; rolling Canary and Dev tags are excluded. `canary.yml` publishes `canary-nightly` only when the triggered ref's HEAD differs from the published tag, replacing the fixed Canary ZIP/SHA-256 Release assets. Its workflow artifact has 14-day retention and is for build investigation; the Wiki's Canary link uses the stable Release asset URL. `dev.yml` is manual and similarly updates `dev-latest` assets, but is separate from Wiki deployment. A download-page change should preserve the distinction between official versioned releases and rolling prereleases and identify Canary as potentially unverified.

## 5. Diagnostic Start Points

| Observation | Inspect first |
|---|---|
| One locale route is missing | Mirror Markdown path, `config.mts` nav/sidebar pair, then `validate_wiki.py` output. |
| Local page works but Pages assets fail | `JUGG_WIKI_BASE`, generated `/jugg/assets/` references, and the workflow build artifact. |
| Dev showcase appears in production | `JUGG_WIKI_DEV` at build time and `srcExclude`; frontmatter alone does not exclude it. |
| Home page builds but a component displays as code | `check-homepage-render.mjs` and the built home-page page asset. |
| SPA navigation is absent from analytics | Theme route callback and GA ID in both config and theme. |
| Download link disappears after a workflow run | Verify it targets a Release asset and that the corresponding release workflow published the expected fixed filename. |

## 6. Related Documents

- `10_wiki_authoring.md` — article content, bilingual mirror, and writing rules.
- `97_maintenance_manual.md` — current knowledge-base maintenance and placement rules.
