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
```

## Reporting Format

When using a pattern, always include in response:

- `pattern_id`
- `confidence`
- `auto_applied`
- `retry_count`
- `result`
