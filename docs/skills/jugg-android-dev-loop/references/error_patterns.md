# Error Pattern Library

Auto-fix policy: known pattern + `scope=low` + `confidence ≥ 0.8` → apply. Otherwise: stop and ask user.

Schema: `id, stage(compile|deploy|runtime|observe), signature{includes,regex}, diagnosis, fix_strategy, fix_scope(low|med|high), confidence_hint(0-1), auto_apply(bool)`

## Patterns

```yaml
- id: PROJECT_NOT_INITIALIZED
  stage: compile
  signature: {includes: ["PROJECT_NOT_INITIALIZED"]}
  diagnosis: project not initialized in IDE/Jugg runtime
  fix_strategy: open_project_and_wait_init
  fix_scope: low
  confidence_hint: 0.98
  auto_apply: false

- id: NO_DEVICE
  stage: deploy
  signature: {includes: ["NO_DEVICE", "no device"]}
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
  signature: {includes: ["PROJECT_NOT_INITIALIZED", "list_projects", "project not found"]}
  diagnosis: multiple IDE instances cause port/project context drift
  fix_strategy: verify_single_target_ide_and_port_binding
  fix_scope: low
  confidence_hint: 0.92
  auto_apply: false

- id: incremental_annotation_not_effective
  stage: runtime
  signature: {includes: ["annotation", "not effective", "inject null", "ViewModel not found", "table missing"], regex: "@Inject.*null|@HiltViewModel.*not found|@Entity.*table|@Dao.*not found|@GlideModule"}
  diagnosis: unsupported annotation processor skipped in incremental compile (Dagger/Hilt/Room/Glide)
  fix_strategy: use_jugg_gradle_build_to_regenerate
  fix_scope: low
  confidence_hint: 0.92
  auto_apply: true

- id: incremental_transform_not_effective
  stage: runtime
  signature: {includes: ["instrumentation", "transform", "bytecode", "hook not triggered", "init not called"], regex: "ASM|Transform|bytecode.*inject|aspect.*not.*trigger"}
  diagnosis: incremental chain has no Gradle Transform; recompiled files lose instrumentation
  fix_strategy: use_jugg_gradle_build_to_run_transforms
  fix_scope: low
  confidence_hint: 0.90
  auto_apply: true

```
