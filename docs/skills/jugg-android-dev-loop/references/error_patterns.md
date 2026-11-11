# Error Pattern Library

## Suspected Jugg Problem

If comparable Gradle builds succeed but Jugg fails, or evidence points to
a Jugg internal failure, offer a dedicated investigation. Explain that it
may take time and download source; start only with user agreement.
Ordinary failures or crashes alone do not trigger it. Reuse explicit
investigation requests or ASK AI prompts as existing authorization.
If selected, follow the investigation guide:
https://raw.githubusercontent.com/tencentmusic/jugg/main/.agents/skills/issue-handler/references/local-agent-investigation-guide.md

Auto-fix policy: known pattern + `scope=low` + `confidence ≥ 0.8` → apply. Otherwise: stop and ask user.

Schema: `id, stage(compile|deploy|runtime|observe), signature{includes,regex}, diagnosis, fix_strategy, fix_scope(low|med|high), confidence_hint(0-1), auto_apply(bool)`

## Patterns

```yaml
- id: PROJECT_NOT_INITIALIZED
  stage: compile|deploy|runtime|observe
  signature: {includes: ["PROJECT_NOT_INITIALIZED"]}
  diagnosis: requested projectDir is not initialized in the current Jugg Runtime; read message Requested/Initialized projects or data.projects
  fix_strategy: open the target project in the IDE, or pass --project-dir of an initialized project
  fix_scope: low
  confidence_hint: 0.98
  auto_apply: false

- id: UI_HIERARCHY_INCOMPLETE
  stage: observe
  signature: {includes: ["UI_HIERARCHY_INCOMPLETE", "incomplete hierarchy"]}
  diagnosis: part of the UI hierarchy could not be extracted; missing nodes do not prove that the element is absent
  fix_strategy: preserve available artifacts and warnings; after a plugin upgrade, use a full Jugg Gradle build and install to update the app runtime; otherwise inspect the reported Compose tooling error
  fix_scope: low
  confidence_hint: 0.95
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
