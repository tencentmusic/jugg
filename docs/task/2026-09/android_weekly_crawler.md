# Android Weekly crawler implementation record

## Goal

Provide a standalone local tool that downloads the Android Weekly archive, stores
the fetched issue pages durably, derives useful deterministic highlights, and
generates a browsable static site with original source links.

## Approved scope

- Add a standard-library Python crawler under `tools/android_weekly/`.
- Discover issue URLs from the public archive and support explicit issue ranges
  when archive discovery is incomplete.
- Use a descriptive User-Agent, timeout, one-second default delay, bounded
  transient-error retry, cache reuse, and per-issue failure recording while
  continuing the remaining archive.
- Write raw HTML and normalized JSON atomically beneath an ignored default
  output directory, while allowing callers to select another local directory.
- Parse article links defensively and score Android, Kotlin, Jetpack, tooling,
  security, and release-related entries to produce highlights.
- Render a static index and one page per issue without any JavaScript framework
  or external service.
- Document use and cover parser, analysis, persistence, and generated output
  with fixture-based unit tests.

## Scope boundaries

The crawler is not part of the Jugg plugin or public Jugg Wiki. It does not use
an LLM, require credentials, redistribute third-party article content, or fetch
linked external articles. It stores only Android Weekly pages and links readers
to their original sources.

## Validation

Run the targeted Python unittest suite and generate a site from fixtures. A
single live issue fetch is limited by the default request delay and only writes
to an ignored local output directory.

## Offline open-source intelligence extension

The analysis rebuild also extracts explicit GitHub, GitLab, Codeberg, and
SourceForge repository links from the cached issue JSON. Repository URLs are
canonicalized and non-repository pages such as issues, pull requests, releases,
blobs, and commits are excluded. Each candidate keeps its issue numbers,
article titles, summaries, source links, category, topics, and an explainable
heuristic score. The generated page selects the top 50 candidates for research
and 1-2 week practice suggestions; these suggestions are not user experience
claims.

This stage is deliberately offline: it performs zero GitHub/GitLab metadata
requests and records `network_calls: 0` in
`build/android-weekly-data/site/opensource-projects.json`. The 741 cached
issues and 18,313 articles produced 1,872 deduplicated repository candidates,
with 50 selected for the page across five categories.

## Approved HarmonyOS interview library scope

Add a separate, Chinese HarmonyOS interview preparation library to the existing
static output. It must not consume or claim facts from Android Weekly. The
library uses official HarmonyOS developer documentation URLs as the primary
technical-fact references, with an explicit retrieval date and a warning that
API availability changes by SDK/API version. Public community pages may only
inform broad question types and follow-up directions; their content is neither
stored nor copied.

The generator will write an independently structured JSON dataset, a research
manifest with URLs, access results, and short original metadata, and a
searchable/filterable HTML page. The dataset covers the approved technical
topics, at least 20 original scenario questions, a 60-minute simulation, and
an Android-developer comparison. Tests validate the stable output contract and
minimum question/scenario counts. The user requested no commit.

The approved follow-up adds a bounded source-reading catalogue. It uses public
OpenHarmony GitHub project metadata as a verifiable project-homepage source and
records successful metadata reads, license values when supplied, and the
attempted HVigor repository 404 in the research manifest. It adds original
source-reading questions and a separate project browser. Every basic, scenario,
and source-reading question has at least one clickable official document or
project-homepage reference. Repository descriptions remain conservative and
version-bound; no source implementation is inferred from a repository name.

The HarmonyOS HTML surface is consolidated in
`site/harmonyos-interview-zh.html`. Its anchored directory exposes overview,
questions, scenarios, open-source projects, Android comparison, mock interview,
and source/research status in one page. The former open-source HTML page is a
compatibility redirect to the `#opensource` section; JSON remains separate for
structured reuse.
