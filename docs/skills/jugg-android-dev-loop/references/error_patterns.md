# Error Pattern Library (Template + Seed)

Use this library to constrain auto-fix behavior.

## Auto-fix Policy

- Apply fix automatically only when:
  - pattern is known
  - fix scope is local and low-risk
  - confidence `>= 0.8`
- Otherwise stop and ask user with diagnosis and candidate options.

## Pattern Schema

```yaml
- id: unique_pattern_id
  stage: compile | deploy | runtime | observe
  signature:
    includes: ["keyword_a", "keyword_b"]
    regex: "optional_regex"
  diagnosis: concise root cause
  fix_strategy: short action id
  fix_scope: low | medium | high
  confidence_hint: 0.0-1.0
  auto_apply: true | false
  next_action_on_success: deterministic next step
  next_action_on_failure: deterministic fallback
```

## Patterns

```yaml
- id: mcp_project_not_initialized
  stage: compile
  signature:
    includes: ["MCP_PROJECT_NOT_INITIALIZED"]
  diagnosis: project not initialized in IDE/Jugg runtime
  fix_strategy: open_project_and_wait_init
  fix_scope: low
  confidence_hint: 0.98
  auto_apply: false
  next_action_on_success: retry_compile_and_deploy
  next_action_on_failure: ask_user_to_initialize_project

- id: mcp_no_device
  stage: deploy
  signature:
    includes: ["MCP_NO_DEVICE", "no device"]
  diagnosis: no online target device
  fix_strategy: ask_user_prepare_device_or_use_compile_only
  fix_scope: low
  confidence_hint: 0.99
  auto_apply: false
  next_action_on_success: rerun_compile_and_deploy_after_user_ready
  next_action_on_failure: ask_user_to_prepare_device

- id: gradle_unresolved_symbol
  stage: compile
  signature:
    includes: ["cannot find symbol", "unresolved reference"]
  diagnosis: source-level compile error
  fix_strategy: patch_source_import_or_symbol
  fix_scope: low
  confidence_hint: 0.90
  auto_apply: true
  next_action_on_success: recompile
  next_action_on_failure: summarize_diff_and_ask_user

- id: manifest_merge_failed
  stage: compile
  signature:
    includes: ["Manifest merger failed"]
  diagnosis: manifest conflict
  fix_strategy: apply_manifest_merge_rule
  fix_scope: medium
  confidence_hint: 0.82
  auto_apply: false
  next_action_on_success: recompile
  next_action_on_failure: ask_user_manifest_decision

- id: aapt_resource_not_found
  stage: compile
  signature:
    includes: ["AAPT", "resource", "not found"]
  diagnosis: missing or renamed resource chain
  fix_strategy: verify_resource_reference_chain
  fix_scope: low
  confidence_hint: 0.88
  auto_apply: true
  next_action_on_success: recompile
  next_action_on_failure: ask_user_for_expected_resource

- id: runtime_fatal_exception
  stage: runtime
  signature:
    includes: ["FATAL EXCEPTION"]
    regex: "FATAL EXCEPTION"
  diagnosis: app crash after launch or interaction
  fix_strategy: extract_top_stack_and_patch
  fix_scope: medium
  confidence_hint: 0.80
  auto_apply: false
  next_action_on_success: redeploy_and_observe
  next_action_on_failure: ask_user_with_stack_summary

- id: ide_port_drift_multi_studio
  stage: compile
  signature:
    includes: ["MCP_PROJECT_NOT_INITIALIZED", "list_projects", "project not found"]
  diagnosis: multiple Android Studio instances cause Jugg MCP to bind to a different IDE port/project context
  fix_strategy: verify_single_target_ide_and_port_binding
  fix_scope: low
  confidence_hint: 0.92
  auto_apply: false
  next_action_on_success: rerun_list_projects_then_compile_and_deploy
  next_action_on_failure: ask_user_to_close_extra_ide_or_switch_target

- id: incremental_annotation_not_effective
  stage: runtime
  signature:
    includes: ["annotation", "not effective", "inject null", "ViewModel not found", "table missing"]
    regex: "@Inject.*null|@HiltViewModel.*not found|@Entity.*table|@Dao.*not found|@GlideModule"
  diagnosis: >
    Unsupported annotation processor did not run during Jugg incremental compile.
    Supported: DataBinding/ViewBinding (KAPT), Compose (compiler plugin), Parcelize (compiler plugin),
    Kuikly @Page (Jugg custom APT), Moshi @JsonClass (KSP whitelist).
    All others (Dagger/Hilt, Room, Glide, etc.) are skipped.
    Note: if only regular source code is modified (no annotation add/change), the change takes effect normally.
    But adding new annotations or changing annotation values for unsupported processors means the processor
    will not re-run — generated code stays stale and the change silently does not take effect.
  fix_strategy: use_force_gradle_compile_to_regenerate
  fix_scope: low
  confidence_hint: 0.92
  auto_apply: true
  next_action_on_success: retry_compile_and_deploy_after_gradle
  next_action_on_failure: summarize_diff_and_ask_user

- id: incremental_transform_not_effective
  stage: runtime
  signature:
    includes: ["instrumentation", "transform", "bytecode", "hook not triggered", "init not called"]
    regex: "ASM|Transform|bytecode.*inject|aspect.*not.*trigger"
  diagnosis: >
    Jugg incremental compile chain is source → class → dex, with no Gradle Transform stage.
    Any file recompiled by Jugg will have its previously instrumented bytecode replaced by raw compiler output.
    ASM-injected hooks, AOP aspects, routing injection, etc. disappear from recompiled classes.
    Symptom: compile and deploy succeed, but instrumented behavior is absent in the changed files.
  fix_strategy: use_force_gradle_compile_to_run_transforms
  fix_scope: low
  confidence_hint: 0.90
  auto_apply: true
  next_action_on_success: retry_compile_and_deploy_after_gradle
  next_action_on_failure: summarize_diff_and_ask_user

```

## Patterns (Learned)

```yaml
- id: xml_regex_patch_miss
  stage: compile
  signature:
    includes: ["patch success", "expected view id not found", "layout_dump missing node"]
  diagnosis: regex/text-replace patch reported success but did not land intended UI node change
  fix_strategy: rewrite_target_xml_and_verify_node
  fix_scope: low
  confidence_hint: 0.89
  auto_apply: true
  next_action_on_success: compile_and_deploy_then_layout_dump_verify
  next_action_on_failure: ask_user_for_layout_structure_decision

- id: viewhierarchy_server_unavailable
  stage: observe
  signature:
    includes: ["ViewHierarchy server is unavailable", "find_and_tap failed", "layout_dump failed"]
  diagnosis: >
    app-side ViewHierarchy server is unreachable and no legacy fallback path is available.
    If error text indicates socket connect/forward failure, this is likely app-side server integration/runtime package mismatch.
  fix_strategy: restart_app_then_one_gradle_compile_for_socket_failures_then_retry
  fix_scope: low
  confidence_hint: 0.93
  auto_apply: true
  next_action_on_success: retry_element_mode_or_layout_verification
  next_action_on_failure: fallback_to_screenshot_percent_or_coordinate_then_ask_user

- id: layout_dump_json_single_line
  stage: observe
  signature:
    includes: ["layout_dump executed successfully", "json one line", "\"bounds\""]
  diagnosis: layout dump may be emitted as single-line JSON, hard to inspect manually
  fix_strategy: use_jq_or_grep_by_text_or_resource_id_then_extract_bounds
  fix_scope: low
  confidence_hint: 0.95
  auto_apply: true
  next_action_on_success: tap_with_center_point_then_screenshot
  next_action_on_failure: fallback_to_manual_screenshot_and_user_confirm
```
