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

## Seed Patterns

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
  fix_strategy: connect_or_boot_device
  fix_scope: low
  confidence_hint: 0.99
  auto_apply: false
  next_action_on_success: rerun_device_list_then_deploy
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

- id: install_signature_mismatch
  stage: deploy
  signature:
    includes: ["INSTALL_FAILED_UPDATE_INCOMPATIBLE", "signatures do not match"]
  diagnosis: installed package signature mismatch
  fix_strategy: clean_reinstall_apk
  fix_scope: medium
  confidence_hint: 0.94
  auto_apply: true
  next_action_on_success: app_start
  next_action_on_failure: ask_user_for_signing_policy

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

- id: mcp_no_device_try_first_emulator
  stage: deploy
  signature:
    includes: ["MCP_NO_DEVICE", "device_list", "devices: []"]
  diagnosis: no online device; try booting first local AVD before asking user
  fix_strategy: start_first_local_avd_then_retry_device_list
  fix_scope: low
  confidence_hint: 0.95
  auto_apply: true
  next_action_on_success: compile_and_deploy
  next_action_on_failure: ask_user_to_prepare_device

- id: start_emulator_mock_success_not_real_boot
  stage: observe
  signature:
    includes: ["testStartEmulatorToolCallSuccess", "start_emulator executed successfully", "adb devices empty"]
  diagnosis: unit test may use mocked IMcpRuntime and only verifies response contract, not real AVD boot
  fix_strategy: require_runtime_evidence_after_start_emulator
  fix_scope: low
  confidence_hint: 0.98
  auto_apply: true
  next_action_on_success: continue_with_compile_and_deploy
  next_action_on_failure: mark_as_false_positive_and_request_environment_check
```

## Reporting Format

When using a pattern, always include in response:

- `pattern_id`
- `confidence`
- `auto_applied`
- `retry_count`
- `result`

## Session Notes

```yaml
- id: xml_regex_patch_miss
  stage: compile
  signature:
    includes: ["patch success", "expected view id not found", "layout_dump missing node"]
  diagnosis: regex/text-replace patch reported success but did not land intended XML node
  fix_strategy: rewrite_target_xml_and_verify_node
  fix_scope: low
  confidence_hint: 0.89
  auto_apply: true
  next_action_on_success: compile_and_deploy_then_layout_dump_verify
  next_action_on_failure: ask_user_for_layout_structure_decision

- id: layout_dump_single_line
  stage: observe
  signature:
    includes: ["layout_dump executed successfully", "xml one line", "bounds="]
  diagnosis: layout dump may be emitted as a single-line XML, hard to read manually
  fix_strategy: grep_by_text_or_resource_id_then_extract_bounds
  fix_scope: low
  confidence_hint: 0.95
  auto_apply: true
  next_action_on_success: tap_with_center_point_then_screenshot
  next_action_on_failure: fallback_to_manual_screenshot_and_user_confirm
```
