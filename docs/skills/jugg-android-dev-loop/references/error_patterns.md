# Error Pattern Library

Use to constrain auto-fix behavior.

## Auto-fix Policy

Apply fix when: known pattern + local scope + confidence ≥ 0.8. Otherwise: stop and ask user.

## Pattern Schema

```yaml
- id: unique_id
  stage: compile | deploy | runtime | observe
  signature: {includes: [], regex: ""}
  diagnosis: root cause
  fix_strategy: action_id
  fix_scope: low | medium | high
  confidence_hint: 0.0-1.0
  auto_apply: bool
```

## Patterns

```yaml
- id: mcp_project_not_initialized
  stage: compile
  signature: {includes: ["MCP_PROJECT_NOT_INITIALIZED"]}
  diagnosis: project not initialized in IDE/Jugg runtime
  fix_strategy: open_project_and_wait_init
  fix_scope: low
  confidence_hint: 0.98
  auto_apply: false

- id: mcp_no_device
  stage: deploy
  signature: {includes: ["MCP_NO_DEVICE", "no device"]}
  diagnosis: no online target device
  fix_strategy: ask_user_prepare_device_or_use_compile_only
  fix_scope: low
  confidence_hint: 0.99
  auto_apply: false

- id: gradle_unresolved_symbol
  stage: compile
  signature: {includes: ["cannot find symbol", "unresolved reference"]}
  diagnosis: source-level compile error
  fix_strategy: patch_source_import_or_symbol
  fix_scope: low
  confidence_hint: 0.90
  auto_apply: true

- id: manifest_merge_failed
  stage: compile
  signature: {includes: ["Manifest merger failed"]}
  diagnosis: manifest conflict
  fix_strategy: apply_manifest_merge_rule
  fix_scope: medium
  confidence_hint: 0.82
  auto_apply: false

- id: aapt_resource_not_found
  stage: compile
  signature: {includes: ["AAPT", "resource", "not found"]}
  diagnosis: missing or renamed resource chain
  fix_strategy: verify_resource_reference_chain
  fix_scope: low
  confidence_hint: 0.88
  auto_apply: true

- id: runtime_fatal_exception
  stage: runtime
  signature: {includes: ["FATAL EXCEPTION"], regex: "FATAL EXCEPTION"}
  diagnosis: app crash after launch or interaction
  fix_strategy: extract_top_stack_and_patch
  fix_scope: medium
  confidence_hint: 0.80
  auto_apply: false

- id: ide_port_drift_multi_studio
  stage: compile
  signature: {includes: ["MCP_PROJECT_NOT_INITIALIZED", "list_projects", "project not found"]}
  diagnosis: multiple Android Studio instances cause Jugg MCP to bind to different IDE port/project context
  fix_strategy: verify_single_target_ide_and_port_binding
  fix_scope: low
  confidence_hint: 0.92
  auto_apply: false

- id: incremental_annotation_not_effective
  stage: runtime
  signature: {includes: ["annotation", "not effective", "inject null", "ViewModel not found", "table missing"], regex: "@Inject.*null|@HiltViewModel.*not found|@Entity.*table|@Dao.*not found|@GlideModule"}
  diagnosis: >
    Unsupported annotation processor did not run during Jugg incremental compile.
    Supported: DataBinding/ViewBinding (KAPT), Compose, Parcelize, Kuikly @Page, Moshi @JsonClass (KSP).
    Unsupported: Dagger/Hilt, Room, Glide, etc.
    Note: regular source changes take effect normally; adding/changing annotations for unsupported processors means processor won't re-run — generated code stays stale.
  fix_strategy: use_force_gradle_compile_to_regenerate
  fix_scope: low
  confidence_hint: 0.92
  auto_apply: true

- id: incremental_transform_not_effective
  stage: runtime
  signature: {includes: ["instrumentation", "transform", "bytecode", "hook not triggered", "init not called"], regex: "ASM|Transform|bytecode.*inject|aspect.*not.*trigger"}
  diagnosis: >
    Jugg incremental compile chain is source → class → dex, no Gradle Transform.
    Recompiled files lose previous instrumentation (ASM hooks, AOP aspects, routing injection).
    Symptom: compile/deploy succeed, but instrumented behavior absent in changed files.
  fix_strategy: use_force_gradle_compile_to_run_transforms
  fix_scope: low
  confidence_hint: 0.90
  auto_apply: true

- id: xml_regex_patch_miss
  stage: compile
  signature: {includes: ["patch success", "expected view id not found", "layout_dump missing node"]}
  diagnosis: regex/text-replace patch reported success but did not land intended UI node change
  fix_strategy: rewrite_target_xml_and_verify_node
  fix_scope: low
  confidence_hint: 0.89
  auto_apply: true

- id: viewhierarchy_server_unavailable
  stage: observe
  signature: {includes: ["ViewHierarchy server is unavailable", "find_and_tap failed", "layout_dump failed"]}
  diagnosis: >
    app-side ViewHierarchy server unreachable, no legacy fallback.
    If socket connect/forward failure, likely app-side server integration/runtime package mismatch.
  fix_strategy: restart_app_then_one_gradle_compile_for_socket_failures_then_retry
  fix_scope: low
  confidence_hint: 0.93
  auto_apply: true

- id: layout_dump_json_single_line
  stage: observe
  signature: {includes: ["layout_dump executed successfully", "json one line", "\"bounds\""]}
  diagnosis: layout dump emitted as single-line JSON, hard to inspect manually
  fix_strategy: use_jq_or_grep_by_text_or_resource_id_then_extract_bounds
  fix_scope: low
  confidence_hint: 0.95
  auto_apply: true

- id: layout_verify_target_not_found
  stage: observe
  signature: {includes: ["layout_verify failed", "target not found"]}
  diagnosis: >
    layout_verify could not find target element.
    Causes: wrong resourceId, text changed, element not rendered, different window (dialog/popup), selector too broad/narrow.
  fix_strategy: refine_selector_or_refresh_dump
  fix_scope: low
  confidence_hint: 0.90
  auto_apply: true

- id: layout_verify_dumpfile_not_found
  stage: observe
  signature: {includes: ["layout_verify failed", "dumpFile not found"]}
  diagnosis: dumpFile path does not exist (cleaned up, stale, never created)
  fix_strategy: rerun_layout_dump_and_retry_verify
  fix_scope: low
  confidence_hint: 0.95
  auto_apply: true

- id: layout_verify_unsupported_property_dumpfile
  stage: observe
  signature: {includes: ["layout_verify failed", "unsupported property in dumpFile mode"]}
  diagnosis: requested property (e.g. textSizeSp) not available in dumpFile mode; must use live query
  fix_strategy: retry_without_dumpfile_for_live_query
  fix_scope: low
  confidence_hint: 0.98
  auto_apply: true
```
