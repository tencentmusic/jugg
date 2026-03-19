# Error Pattern Library

Auto-fix policy: known pattern + `scope=low` + `confidence ≥ 0.8` → apply. Otherwise: stop and ask user.

Schema: `id, stage(compile|deploy|runtime|observe), signature{includes,regex}, diagnosis, fix_strategy, fix_scope(low|med|high), confidence_hint(0-1), auto_apply(bool)`

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
  diagnosis: missing or renamed resource
  fix_strategy: verify_resource_reference_chain
  fix_scope: low
  confidence_hint: 0.88
  auto_apply: true

- id: runtime_fatal_exception
  stage: runtime
  signature: {includes: ["FATAL EXCEPTION"]}
  diagnosis: app crash after launch or interaction
  fix_strategy: extract_top_stack_and_patch
  fix_scope: medium
  confidence_hint: 0.80
  auto_apply: false

- id: ide_port_drift_multi_studio
  stage: compile
  signature: {includes: ["MCP_PROJECT_NOT_INITIALIZED", "list_projects", "project not found"]}
  diagnosis: multiple IDE instances cause port/project context drift
  fix_strategy: verify_single_target_ide_and_port_binding
  fix_scope: low
  confidence_hint: 0.92
  auto_apply: false

- id: incremental_annotation_not_effective
  stage: runtime
  signature: {includes: ["annotation", "not effective", "inject null", "ViewModel not found", "table missing"], regex: "@Inject.*null|@HiltViewModel.*not found|@Entity.*table|@Dao.*not found|@GlideModule"}
  diagnosis: unsupported annotation processor skipped in incremental compile (Dagger/Hilt/Room/Glide)
  fix_strategy: use_force_gradle_compile_to_regenerate
  fix_scope: low
  confidence_hint: 0.92
  auto_apply: true

- id: incremental_transform_not_effective
  stage: runtime
  signature: {includes: ["instrumentation", "transform", "bytecode", "hook not triggered", "init not called"], regex: "ASM|Transform|bytecode.*inject|aspect.*not.*trigger"}
  diagnosis: incremental chain has no Gradle Transform; recompiled files lose instrumentation
  fix_strategy: use_force_gradle_compile_to_run_transforms
  fix_scope: low
  confidence_hint: 0.90
  auto_apply: true

- id: xml_regex_patch_miss
  stage: compile
  signature: {includes: ["patch success", "expected view id not found", "layout_dump missing node"]}
  diagnosis: regex patch reported success but UI node change not landed
  fix_strategy: rewrite_target_xml_and_verify_node
  fix_scope: low
  confidence_hint: 0.89
  auto_apply: true

- id: viewhierarchy_server_unavailable
  stage: observe
  signature: {includes: ["ViewHierarchy server is unavailable", "find_and_tap failed", "layout_dump failed"]}
  diagnosis: app-side ViewHierarchy server unreachable
  fix_strategy: restart_app_then_one_gradle_compile_for_socket_failures_then_retry
  fix_scope: low
  confidence_hint: 0.93
  auto_apply: true

- id: layout_dump_json_single_line
  stage: observe
  signature: {includes: ["layout_dump executed successfully", "json one line", "\"bounds\""]}
  diagnosis: layout dump emitted as single-line JSON
  fix_strategy: use_jq_or_grep_by_text_or_resource_id_then_extract_bounds
  fix_scope: low
  confidence_hint: 0.95
  auto_apply: true

- id: layout_verify_target_not_found
  stage: observe
  signature: {includes: ["layout_verify failed", "target not found"]}
  diagnosis: target element not found (wrong selector, not rendered, different window)
  fix_strategy: refine_selector_or_refresh_dump
  fix_scope: low
  confidence_hint: 0.90
  auto_apply: true

- id: layout_verify_dumpfile_not_found
  stage: observe
  signature: {includes: ["layout_verify failed", "dumpFile not found"]}
  diagnosis: dumpFile path stale or never created
  fix_strategy: rerun_layout_dump_and_retry_verify
  fix_scope: low
  confidence_hint: 0.95
  auto_apply: true

- id: layout_verify_unsupported_property_dumpfile
  stage: observe
  signature: {includes: ["layout_verify failed", "unsupported property in dumpFile mode"]}
  diagnosis: property unavailable in dumpFile mode, needs live query
  fix_strategy: retry_without_dumpfile_for_live_query
  fix_scope: low
  confidence_hint: 0.98
  auto_apply: true
```
