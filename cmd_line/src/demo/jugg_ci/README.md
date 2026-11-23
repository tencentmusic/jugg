# Run a single CI build

[简体中文](README.zh-CN.md)

Place a clean Git checkout at `<work-dir>/project`. Keep the distribution's launcher, HTTP helpers and custom compilers in their packaged locations. From the distribution's `demo/` directory run:

```sh
python3 jugg_ci/build.py --work-dir /path/to/ci-work --server https://jugg-ci.example.com
```

| Option | Purpose |
| --- | --- |
| `--server` | Select a baseline backend. The default is configured in build.py. |
| `--project-id` | Isolate project records. Defaults to default. |
| `--scenario` | Select the debug or release build configuration. |
| `--cmd-line` | Use a specific cmd_line launcher. |
| `--dry-run` | Query and evaluate baselines and write dry-run-result.json. |
| `--force-gradle` | Build a new Gradle baseline. |

Configure Gradle tasks, APK output paths, scenario keys and incremental eligibility in `build.py`. Serialize builds per checkout. Collect APKs from the `apkDir` in `<work-dir>/build-result.json` and diagnostics from `runDir`.

For local runs, start `python3 jugg_ci/base_build_server.py` in another terminal. Its default address is 127.0.0.1:18766. The service stores the index in `jugg_ci/base-builds.json` and ZIPs in `jugg_ci/artifacts/`. Use `--data-file` to select an existing index. The [full demo](../README.md) also starts and stops its own service.
