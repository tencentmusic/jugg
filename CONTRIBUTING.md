<p align="left">
  <strong>English</strong> | <a href="./CONTRIBUTING.zh-CN.md">简体中文</a>
</p>

# Contributing to Jugg

Thanks for helping improve Jugg. This guide is for people who report issues, suggest features, or send pull requests to [tencentmusic/jugg](https://github.com/tencentmusic/jugg).

GitHub will also link to this file when you open an issue or a pull request.

## Ways to contribute

- Report a bug with a Jugg Report ID or other diagnostic material.
- Request a user-visible capability or workflow improvement.
- Fix a bug, add a small feature, or improve documentation.
- Help other users in the [WeChat group](./docs/images/wechat-group.jpg).

Please search [existing issues](https://github.com/tencentmusic/jugg/issues) before opening a new one.

## Report a bug

Use the [Bug Report](https://github.com/tencentmusic/jugg/issues/new?template=04_bug_report_en.yml) template.

1. Describe the unexpected behavior, the expected result, and the shortest reproduction steps.
2. Attach the highest-priority diagnostic material you can provide:
   - **Jugg Report ID** (preferred)
   - Diagnostics Bundle ZIP
   - A minimal reproducible demo
   - Manual environment information, only if the first three are unavailable
3. Remove secrets, signing credentials, private source, and anything else you do not want to make public.

How to get a Report ID or Diagnostics Bundle:

1. In Android Studio, press Shift twice and choose **Report Jugg Issue**, or click **Report Issue** in the Jugg Running Panel.
2. Choose **Upload logs**, then copy the Report ID. If upload is unavailable, choose **Save locally without uploading** and create a Diagnostics Bundle.

See the Wiki [Report an issue](https://tencentmusic.github.io/jugg/guide/report-issue) page for what is uploaded and where local logs live.

Do not post credentials, private diagnostic dumps, or suspected security vulnerabilities in a public issue. If you believe you found a security problem, contact the maintainers privately instead of filing a public bug.

## Request a feature

Use the [Feature Request](https://github.com/tencentmusic/jugg/issues/new?template=05_feature_request_en.yml) template.

Describe the real use case and the user-visible result you want. You do not need to design the internal implementation.

## Development setup

### Prerequisites

- JDK 17 (CI uses Temurin 17; the plugin compiles to Java 11)
- Git
- The Gradle Wrapper in this repository (`gradlew`); do not install a separate Gradle version unless you are changing the wrapper itself

Native agent work also needs the Android SDK, NDK, and CMake used by CI. Most Kotlin/Java plugin changes do not.

### Clone and build

```bash
git clone https://github.com/tencentmusic/jugg.git
cd jugg
./gradlew buildPlugin
./gradlew runIde
```

- `buildPlugin` writes the plugin zip to `idea/build/distributions`.
- `runIde` starts a disposable IDE for development.

Install the built plugin into a local Android Studio only when you need to verify against a real Android project and device.

## Repository layout

| Path | Role |
|---|---|
| `idea/` | IDE plugin, run configurations, UI, and IDE tests |
| `main/` | Incremental compile, deploy, project model, MCP, and core tests |
| `deploy_compat/` | Android Studio version compatibility |
| `cmd_line/` | Command-line entry |
| `docs/wiki/` | User Wiki |
| `docs/ai_knowledge/` | Maintainer / AI architecture notes |

Change only the modules that own the behavior you are fixing. Prefer the smallest patch that solves the reported problem.

## Coding guidelines

- Keep the change small and scoped. Do not mix unrelated refactors, cleanup, or extra abstractions into a bug fix.
- Write code comments in English. Do not add Chinese comments in source files.
- Prefer non-null Kotlin types. Prefer optional parameters over new overloads.
- Name interfaces with an `I` prefix. The default implementation uses the name without `I`, for example `IDeployHistoryManager` and `DeployHistoryManager`.
- Log with `JuggLogger`. Do not use `error`. Use `warn` for unexpected user-visible failures, `info` for key user-visible flow, `debug` for developer log-file diagnostics, and `trace` for high-frequency logs behind a switch.
- When a log call is too long, wrap only at `+` in the message string. Indent continuation lines 8 spaces relative to the call. Keep the exception argument on the same line as the last message segment:

```kotlin
logger.warn("message bla bla bla" +
        "details", exception)
```

## Tests and verification

Every change needs verification evidence that matches the risk. Automated tests are one kind of evidence, not a requirement for every patch.

- Do not add a test that only locks implementation details, simple pass-through, or mock interaction with no observable result.
- Do not add test-only `provider` / `supplier` / `factory` / `override` lambdas to production code.
- Do not run unfiltered `:main:test` or `:idea:test`. Use `--tests` for the behavior you changed:

```bash
./gradlew :idea:compileKotlin
./gradlew :main:test --tests "com.sickworm.intellij.jugg.deploy.data.DeployDataGeneratorTest"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.manager.TopLevelFlowTest"
```

Compile-only changes can use `./gradlew :idea:compileKotlin`. Plugin packaging can use `./gradlew :idea:buildPlugin`.

If the change cannot be asserted automatically without binding private implementation, say so in the pull request: include reproduction evidence, why a test was not added, and what you verified instead.

## Commit messages

Use English. Title format: `[prefix] subject`

- `subject` starts with a lowercase letter and does not end with a period.
- Choose the prefix from the user-visible result:
  - `[bugfix]` existing behavior is wrong
  - `[feature]` new user-visible capability
  - `[optimize]` existing behavior is correct, but clearer, more reliable, or easier to use
  - `[refactor]` / `[docs]` / `[test]` / `[other]` for no behavior change, documentation, tests, or other work
- Describe the user scenario and observable result, not the internal implementation.
- `[bugfix]` titles usually look like `[bugfix] fix <problem> when/after/for <scenario>`.
- `[optimize]` titles usually look like `[optimize] <improvement> when/for <scenario>`.

If the title is not enough, add a short body after a blank line.

Examples:

```text
[bugfix] fix incremental deploy skipping resource changes after Gradle fallback
[docs] add repository contributor guidelines
```

## Pull requests

1. Fork the repository and create a branch from `main`.
2. Open a pull request against `main`. Maintainers may redirect larger work to `develop`.
3. Keep the pull request focused. One problem, one fix, one verification story.
4. Complete the pull request template (`.github/PULL_REQUEST_TEMPLATE.md`). GitHub fills it in when you open a PR. At minimum, include:
   - What user-visible problem or capability this changes
   - How you verified it
   - Linked issue, if there is one
5. Expect review comments. Small follow-up commits are fine; do not force-push unless a maintainer asks.

Do not include secrets, local IDE files, `build/` outputs, or unrelated formatting churn.

## Documentation

- **User Wiki** lives in `docs/wiki`. Chinese pages under `docs/wiki/zh/` are the content source; English pages should stay in sync.
- **Maintainer notes** live in `docs/ai_knowledge`. Update them when a change affects plugin internals, compile/deploy behavior, or AI task routing.
- Do not copy internal class names into user-facing Wiki pages unless the page is explaining a user-visible name.

## Community

Be respectful in issues, pull requests, and the WeChat group. Assume good intent, keep discussion about the problem, and do not share other people's private logs or project files.

## License

Jugg is released under the [MIT License](LICENSE). By contributing, you agree that your contribution is licensed under the same license.
