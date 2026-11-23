# Jugg CI build demo

[简体中文](README.zh-CN.md)

Run the demo with Python 3.8+, Git, an Android SDK, a JDK compatible with the demo Gradle build, and the Jugg command-line distribution. Export `JAVA_HOME` and `ANDROID_HOME` before starting. The Python scripts use the standard library.

## Run all six scenarios

From the distribution's `demo/` directory run:

```sh
python3 demo_flow/run.py
```

The runner deletes and recreates its work directory on startup. The default is `demo/build/ci-demo`. Choose a dedicated directory when passing `--work-dir`. By default the runner starts a private baseline service on an available local port, writes its data under `<work-dir>/server/`, and shuts it down on exit. Pass `--server` to use an existing service. From the source repository, also pass `--cmd-line /absolute/path/to/bin/cmd_line`.

After the first Gradle baseline, the runner advances the same checkout by one commit per scenario.

| Change | Expected build |
| --- | --- |
| Edit Kotlin toast and XML text | Incremental APK |
| Add a BuildConfig field to the Gradle build file | New Gradle baseline |
| Add 101 Java files | New Gradle baseline (the demo limit is 100) |
| Edit the root README | Filter the README and export the baseline APK |
| Change an existing ARouter route | Incremental route compilation |
| Edit the custom Gradle input `config.json` | New Gradle baseline and updated `assets/config.json` in the APK |

Read `flow-results.json` in the work directory for each round's commit, diagnostics and APK directory. Later rounds include all changes since the selected baseline. The default scenario is debug. Use `--install` to install the final APK and launch the app. The option `--quite` hides subprocess output and retains the demo progress log.

## Use individual steps

Start `python3 jugg_ci/base_build_server.py` in another terminal. From the demo directory run:

```sh
python3 demo_flow/prepare_project.py --work-dir /tmp/jugg-manual
python3 jugg_ci/build.py --work-dir /tmp/jugg-manual
# Edit files in /tmp/jugg-manual/project, then commit the changes.
python3 jugg_ci/build.py --work-dir /tmp/jugg-manual
```

Use the resulting `apkDir` with `python3 demo_flow/install_and_launch.py --apk-dir <apkDir>`. Project preparation uses the packaged demo ZIP or the repository's Android demo and creates an isolated Git checkout. To resume initial setup it can reuse its own checkout while still at the initial commit. CI can supply its own clean checkout in `<work-dir>/project`. Keep participating repositories at fixed commits during the build and serialize builds in a shared checkout.

## Baseline and build policy

The demo queries compatible records using the scenario key (Gradle task and local configuration digest). For each participating repository it finds the nearest first-parent candidate. It deduplicates records by ID, downloads each candidate into an isolated directory, and chooses the eligible baseline with the fewest effective changes. Ties follow server order, newest first. The server stores relative repository locations for portable records. Build machines must have compatible toolchains, dependencies and relative repository layouts.

`getChangedFiles` reports each entry's type, module and absolute `gitRoot`. Unclassified inputs have type `Unknown` and an empty module. The top-level `repositories` array includes unchanged repositories and their base and target commits. External repository bases come from the baseline deployment history and targets come from current HEADs. Generate a new full baseline after adding a source repository.

The demo filters only the root README. Build files, missing inputs, remaining unknowns, more than 100 effective changes and configured annotation rules trigger Gradle. The release policy checks patch lines for changes to `@Keep`. Full-source rules cover Hilt annotations. Edit the constants and policy functions in `jugg_ci/build.py` to match your project. When the effective list is empty the command receives an explicit `changedFiles=` and exports the existing baseline APK. Other eligible lists are passed in full. A Jugg failure triggers one Gradle attempt. A failed Gradle build or baseline archive exits with an error.

An incremental pipeline with downstream signing may pass `skipSign=true`. Sign and verify the rewritten APK before distribution. Only new full builds publish baselines. Release builds use `bundleReleaseToApk` and retain available mapping and usage files.

## Artifacts and service API

A run keeps its logs, Git patches, candidate changes and APKs under `<work-dir>/runs/<id>/`. The success record `build-result.json` identifies the commit, mode, reason, APK paths and run directory. Preview writes a separate `dry-run-result.json`.

| Request | Result |
| --- | --- |
| POST /artifacts | Upload a ZIP containing the jugg directory and receive an artifact ID |
| PUT /base-builds?projectId=… | Register the record, artifact ID, commits, scenario and compile command |
| GET /base-builds?projectId=… | List records by descending registration time |
| GET /base-builds/available | Filter available records by project, scenario and compile command |
| GET /artifacts/{artifactId} | Download a baseline ZIP |
| DELETE /base-builds?baseCommit=… | Remove matching records and unreferenced local ZIPs, optionally scoped by projectId |

The local service retains the newest 100 records per project. ZIP storage has a separate lifecycle. Rebuild legacy directory archives as ZIP baselines. See the [single-build guide](jugg_ci/README.md) for pipeline options.
