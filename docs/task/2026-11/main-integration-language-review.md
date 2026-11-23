# Main integration language review

**Purpose** — Review the incoming Chinese additions from 989b33401 to 0ab9a00dc.
**Decision** — Retain the current English knowledge base and merge the incoming contracts into their behavior owners.
**Impact** — CI, Gradle, ConstRef, runtime and Wiki maintainers have current English references.

旧知识库的十个文件在 develop 已删除，main 修改了原路径。逐项核对增量，保留删除状态并更新下表对应英文主题。九份历史任务记录保留原文，各补英文摘要。

| Incoming path | Classification and English landing or exception |
| --- | --- |
| `AGENTS.md` | Current contributor rule, English CI domain definition in AGENTS.md. |
| `change_log/change_log_cn.html` | Localized release notes → change_log/change_log.html. |
| `change_log/change_log_rc_cn.yaml` | Localized release notes → change_log/change_log_rc.yaml. |
| `cmd_line/src/demo/README.md` | Current operating instructions, English landing in this README. |
| `cmd_line/src/demo/demo_flow/baseline_service.py` | English source comments in place. Chinese CLI messages, expected outputs and demo inputs are localized content. |
| `cmd_line/src/demo/demo_flow/flow_common.py` | English source comments in place. Chinese CLI messages, expected outputs and demo inputs are localized content. |
| `cmd_line/src/demo/demo_flow/install_and_launch.py` | English source comments in place. Chinese CLI messages, expected outputs and demo inputs are localized content. |
| `cmd_line/src/demo/demo_flow/prepare_project.py` | English source comments in place. Chinese CLI messages, expected outputs and demo inputs are localized content. |
| `cmd_line/src/demo/demo_flow/run.py` | English source comments in place. Chinese CLI messages, expected outputs and demo inputs are localized content. |
| `cmd_line/src/demo/jugg_ci/.gitignore` | English ignore-rule comment in place. |
| `cmd_line/src/demo/jugg_ci/README.md` | Current operating instructions, English landing in this README. |
| `cmd_line/src/demo/jugg_ci/_common.py` | English source comments in place. Chinese CLI messages, expected outputs and demo inputs are localized content. |
| `cmd_line/src/demo/jugg_ci/base_build_server.py` | English source comments in place. Chinese CLI messages, expected outputs and demo inputs are localized content. |
| `cmd_line/src/demo/jugg_ci/build.py` | English source comments in place. Chinese CLI messages, expected outputs and demo inputs are localized content. |
| `cmd_line/src/test/python/test_demo_flow.py` | Localized CLI output assertions and meaningful test inputs. |
| `docs/ai_knowledge/02_compile_core.md` | Current knowledge → `docs/ai/02_compile_core.md`. |
| `docs/ai_knowledge/02_compile_source.md` | Current knowledge → `docs/ai/02_compile_source.md`. |
| `docs/ai_knowledge/03_deploy_const_ref.md` | Current knowledge → `docs/ai/03_deploy_const_ref.md`. |
| `docs/ai_knowledge/03_runtime_jvmti.md` | Current knowledge → `docs/ai/03_runtime_jvmti.md`. |
| `docs/ai_knowledge/04_engineering_compat.md` | Current knowledge → `docs/ai/04_engineering_compat.md`. |
| `docs/ai_knowledge/04_engineering_project.md` | Current knowledge → `docs/ai/04_engineering_project.md`. |
| `docs/ai_knowledge/08_cli_tools_list.md` | Current knowledge → `docs/ai/08_cli_tools_list.md`. |
| `docs/ai_knowledge/10_wiki_architecture.md` | Current knowledge → `docs/ai/10_wiki_architecture.md`. |
| `docs/ai_knowledge/98_code_map.md` | Current knowledge → `docs/ai/98_code_map.md`. |
| `docs/ai_knowledge/99_index.md` | Current knowledge → `docs/ai/99_index.md`. |
| `docs/task/2026-11/ci-constref-baseline-handoff.md` | Historical task record with English Purpose / Decision / Impact abstract in place. |
| `docs/task/2026-11/ci-multi-repository-changes.md` | Historical task record with English Purpose / Decision / Impact abstract in place. |
| `docs/task/2026-11/ci-multi-repository-selection.md` | Historical task record with English Purpose / Decision / Impact abstract in place. |
| `docs/task/2026-11/ci-skip-apk-signing.md` | Historical task record with English Purpose / Decision / Impact abstract in place. |
| `docs/task/2026-11/constref-overlapping-analysis.md` | Historical task record with English Purpose / Decision / Impact abstract in place. |
| `docs/task/2026-11/gradle-script-warnings-as-errors.md` | Historical task record with English Purpose / Decision / Impact abstract in place. |
| `docs/task/2026-11/gradle_fallback_clean.md` | Historical task record with English Purpose / Decision / Impact abstract in place. |
| `docs/task/2026-11/jugg-ci-orchestration-sample-plan.md` | Historical task record with English Purpose / Decision / Impact abstract in place. |
| `docs/task/2026-11/wiki_404_redirection_and_seo_fix.md` | Historical task record with English Purpose / Decision / Impact abstract in place. |
| `docs/wiki/.vitepress/theme/NotFound.vue` | Bilingual redirect status, localized UI text. |
| `docs/wiki/zh/articles/index.md` | Localized routes/content → docs/wiki/articles/index.md (historical article text remains Chinese). |
| `docs/wiki/zh/capabilities/index.md` | Localized routes/content → docs/wiki/capabilities/index.md (historical article text remains Chinese). |
| `docs/wiki/zh/concepts/incremental-compile/index.md` | Localized routes/content → docs/wiki/concepts/incremental-compile/index.md (historical article text remains Chinese). |
| `docs/wiki/zh/guide/downgrade-gradle.md` | Localized routes/content → docs/wiki/guide/downgrade-gradle.md (historical article text remains Chinese). |
| `docs/wiki/zh/guide/index.md` | Localized routes/content → docs/wiki/guide/index.md (historical article text remains Chinese). |
| `docs/wiki/zh/troubleshooting/supported-packaging.md` | Localized routes/content → docs/wiki/troubleshooting/supported-packaging.md (historical article text remains Chinese). |

No unresolved product facts were promoted. Historical benchmark timings and external-project deployment outcomes remain scoped to their original reports.

The integration adds Chinese mirrors of the two demo guides at `cmd_line/src/demo/README.zh-CN.md` and `cmd_line/src/demo/jugg_ci/README.zh-CN.md`. Each is paired with the English README in its directory. The Chinese contribution guide mirrors the English CI tag definition. This record and `merge-main-into-develop-4.md` are historical integration records for the Chinese-speaking reviewer.

## Rebuilt merge against 9b3f96eba

The incremental review compares the old main snapshot at `0ab9a00dc` with `9b3f96eba`. The earlier classifications above still apply to the reused merge result.

| Incoming path | Classification and English landing or exception |
| --- | --- |
| `change_log/change_log_cn.html` | Localized release entries, paired with `change_log/change_log.html`. |
| `change_log/change_log_rc_cn.yaml` | Localized release entries, paired with `change_log/change_log_rc.yaml`. |
| `deploy_compat/README.md` | Current English instructions now link to the new-release workflow in the English compatibility guide. |
| `docs/ai_knowledge/04_engineering_compat.md` | New-release verification workflow, full-build dispatch and Stingray API changes integrated in `docs/ai/04_engineering_compat.md`. |
| `docs/ai_knowledge/98_code_map.md` | The adapter entry in `docs/ai/98_code_map.md` identifies Stingray and its API boundaries. |
| `docs/ai_knowledge/99_index.md` | The compatibility row in `docs/ai/99_index.md` routes to the English workflow. |
| `docs/wiki/zh/reference/compatibility.md` | Localized support range and upstream Canary 2 verification, paired with `docs/wiki/reference/compatibility.md`. |

The incoming runtime evidence concerns the main implementation. Real-IDE deployment with the integrated develop adapter remains unverified.
