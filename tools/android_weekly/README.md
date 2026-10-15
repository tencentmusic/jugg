# Android Weekly crawler

`crawl.py` preserves Android Weekly issue pages locally and creates a static,
link-only highlights site. It uses Python's standard library and never downloads
the linked third-party articles.

```bash
python3 tools/android_weekly/crawl.py --output /tmp/android-weekly --from-issue 744 --to-issue 744
open /tmp/android-weekly/site/index.html
```

Omit the range to crawl every issue advertised by the archive. The default
output is `build/android-weekly-data/`, which is ignored by Git. Existing
normalized issue JSON is reused; pass `--refresh` to retrieve pages again.
To reparse all cached raw pages and regenerate analysis without downloading,
use `--rebuild-analysis`.
Raw HTML is stored in `raw/`, parsed records in `issues/`, and the generated
site in `site/`. `failures.json` records each issue that still fails after its
bounded retry; the crawler continues processing the remaining archive entries
and exits non-zero if that file is non-empty. Requests use a descriptive
User-Agent, a default one-second delay, a 20-second timeout, and one retry only
for transient errors.

Highlights are deterministic: titles and summaries mentioning Android, Kotlin,
Compose, Jetpack, tooling, security, releases, performance, or testing rank
first. Each article receives a 1-10 score averaged from novelty, technical
depth, engineering value, and story value, with the four dimension scores and
reason stored in JSON and shown on issue pages. The index includes the highest
scoring articles plus Chinese `interview-template-zh.html` and
`interview-case-library-zh.html` pages. They are structured interview narration
templates derived from newsletter cases, not claims of personal experience.

The same run also creates `opensource-projects-zh.html` and
`opensource-projects.json`. These are an offline intelligence library built
only from explicit GitHub/GitLab/Codeberg/SourceForge links in the 741 cached
issues. Candidates retain issue numbers, article titles, summaries, original
URLs, categories, and explainable scores. The page contains research and
1-2-week practice suggestions, not claims that the user has used or
contributed to a project. No GitHub/GitLab metadata API calls are made by
default (`network_calls: 0` in the JSON).

## HarmonyOS interview library

Every rebuild also writes a separate Chinese interview preparation site:

- `site/harmonyos-interview-zh.html`: the single browse page: searchable and
  topic-filterable question bank, scenario questions, OpenHarmony project
  cards, Android quick comparison, 60-minute mock, and research status. Search
  and filters apply to questions/scenarios, not project cards.
- `site/harmonyos-interview-zh.json`: structured original questions and answers.
- `site/harmonyos-research-manifest.json`: source URLs, access date, access
  limitations, and the copyright/research boundary.
- `site/harmonyos-opensource.json`: structured catalogue for verified public
  OpenHarmony project homepages, source-reading entries, risks, and exercises.
  `site/harmonyos-opensource-zh.html` is retained only as a compatibility
  redirect to `harmonyos-interview-zh.html#opensource`.

It is intentionally independent of Android Weekly. HarmonyOS official
documentation URLs are the technical-fact sources. Community pages only
contribute broad interview-topic signals; the crawler stores neither their
article bodies nor copied question lists, and all Chinese questions and answers
are original summaries. APIs, SDK behavior, and device availability can change:
verify each answer against the target SDK's current official documentation and
a real device before using it in an interview. To refresh maintained content,
update the source map and original question data in `crawl.py`, rerun
`python3 tools/android_weekly/crawl.py --rebuild-analysis`, then run the
targeted unittest command below.

Repository research is deliberately bounded. The manifest identifies the public
GitHub metadata observed on the retrieval date, recorded 404s, and source
licenses only when the public API provided them. It does not infer repository
internals from names, copy third-party interview questions, or treat community
pages as API facts.

```bash
python3 -m unittest tools.android_weekly.test_crawl
```
