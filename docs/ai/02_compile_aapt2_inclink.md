# AAPT2 Native Incremental Linker

> Last verified: 2026-11-09
> Consistency rule: If documentation conflicts with code, code takes precedence.

## 1. Scope and Maintenance Decisions

This topic supports maintenance of the native incremental-link algorithm and diagnosis of resource-link failures. All native paths below are relative to `aapt2-inclink/source/`. The Jugg caller, baseline selection, generated auxiliary inputs, and deployment handoff are covered in [02_compile_resource.md](02_compile_resource.md).

The maintainer confirmed that `aapt2-inclink/source/` is the source maintenance entry point. Edit it directly. Synchronization into an AOSP checkout will use a script or link; the standalone macOS build reads this directory directly; see §8.

The original import commit `6361ca80c` records AOSP commit `a707013b78cea3586fdadf9a2f04932e823d7504` as its baseline. This is the upstream revision recorded by the import commit; an Android release label has not been established. The subtree imports the independent repository through `b5b59b2ac`. That last commit adds Android 14 AOSP build compatibility in `Android.bp`; it does not establish a new upstream source baseline. Original history uses source-root paths, so inspect it with `git show <commit> -- cmd/IncLink.cpp`, without the current subtree prefix.

**Maintainer rationale:** loading the APK was historically about 4–5 times faster than reading all compiled `.flat` inputs. Its `resources.arsc` already represents the final resource table with assigned IDs, avoiding an `--emit-ids` / `--stable-ids` round trip to preserve those IDs. The speed ratio is a maintainer-reported historical measurement; its dataset and measurement conditions are not recorded here.

**Maintainer priority:** the maintainer considers the current design nearly stable. Their stated concern is that compatibility for rare cases could increase common-case latency or disrupt the overall design, with high regression costs.

## 2. Core Source Index

| Owner | Source location | Responsibility |
|---|---|---|
| `MainCommand`, `DaemonCommand::Action()` | `Main.cpp` | Register `inclink` / `il`; execute repeated commands in one process |
| `IncLinkCommand::Action()`, `IncLinker` | `cmd/IncLink.cpp`, `cmd/IncLink.h` | Load one APK, own the process cache, merge successive increments and emit outputs |
| `LinkContext`, `Linker::MergePath()`, `Linker::WriteApk()` | `cmd/Link.cpp` | Retain linking context; consume compiled resources; write Manifest, changed files, and resource table |
| `TableMerger::MergeFile()`, `IsMergedFile()` | `link/TableMerger.cpp` | Merge resources and track which file resources belong to this invocation |
| `IdAssigner`, `ReferenceLinker` | `compile/IdAssigner.cpp`, `link/ReferenceLinker.cpp` | Preserve/assign resource IDs and resolve table references |
| `ResourceFileFlattener::Flatten()` | `cmd/Link.cpp` | Link and flatten only current merged file resources; coordinate XML compatibility variants |
| `XmlCompatVersioner::Process()` | `link/XmlCompatVersioner.cpp` | Produce SDK-specific XML, including the incremental v22 overwrite case |
| `ResourceTable::AddResourceMangledKeepOriginalIfCollision()` | `ResourceTable.cpp` | Keep an existing conflicting value when inserting generated XML variants |
| `ArscCompiler.loadTable()` / `doApkCompile()` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/ArscCompiler.kt` (repository root) | Own one daemon per APK, supply baseline/auxiliary inputs, discard failed invokers |

`IncLink.cpp` directly includes `Link.cpp` and derives `IncLinker` privately from `Linker`. Ordinary `link` and `inclink` share the merge and flatten implementations in that file.

## 3. Process State and Invocation Contract

| State | Lifetime and meaning |
|---|---|
| `_cacheIncLink` | One process-global linker cache, not an APK-keyed registry. A successful `--load` initializes it; subsequent invocations reuse it. |
| `final_table_`, loaded Manifest, symbol sources | Retained across incremental invocations. The table incorporates merged inputs; the Manifest can be replaced. Baseline entries keep their IDs; new entries pass through `IdAssigner`. |
| `LinkContext` | Retains load-time package, SDK and symbol context. `updateContextOnInc()` updates only diagnostics on the retained context. |
| `LinkOptions` | Replaced by each incremental invocation. Updating options does not rerun load-time initialization. |
| `table_merger_` | Recreated per `IncLinkAdv()` invocation; its merged-file membership selects this invocation's file outputs. |

Load and increments share state through the process-global cache. `DaemonCommand` prints `Ready` to stdout, accepts a command followed by one argument per line and an empty terminating line, then writes `Error` for a nonzero result and `Done` after command execution to stderr.

`inclink --load` accepts exactly one positional APK. `-I` inputs are dependencies, not additional positional load targets. `-o` and `--manifest` remain required parser flags, but load reads the Manifest from the APK and does not write an output archive. The Jugg caller supplies placeholder values for those flags on load.

Jugg caches separate daemon invokers per APK path. A feature load also receives its base resource APK through `-I`.

## 4. Core Data Flow

The following is a stage/data flow, not a list of direct method calls:

```text
ArscCompiler selects current resource APK and creates an APK-specific daemon
  -> inclink --load: LoadedApk supplies binary Manifest and resource table
  -> IncLinker reconstructs retained table, IDs, symbol context and auxiliary metadata
  -> later inclink: TableMerger merges this invocation's compiled inputs into retained table
  -> IdAssigner and ReferenceLinker resolve the accumulated table
  -> WriteApk flattens current merged files and the accumulated table with current Manifest
  -> optional R.java / text symbols and Proguard outputs
  -> Jugg filters and stages resource outputs for their owning APK
```

### 4.1 APK initialization

`LoadFromApk()` clones the APK Manifest, derives the compilation package, loads include symbols, and copies table packages/types/entries/configurations into `final_table_`, cloning values into its string pool while preserving IDs and visibility. It then supplies binary APK dependency symbols, imports styleables, adds Manifest-exported symbols, and prepends the retained table as a symbol source.

Feature APK tables may use `<application package>.<split name>`. Loading restores the compilation package name and takes the matching feature package ID from that table. Shared `WriteApk()` handles its output package rewrite and restores the in-memory name afterward. This support was added in `fb05994ae`.

The load path can remove resources without default configurations unless `--no-resource-removal` is supplied. This inherited cleanup is distinct from deleting a source file during an increment: the incremental path has no deletion input protocol.

### 4.2 Incremental merge and outputs

`IncLinkAdv()` creates a fresh `TableMerger` over the existing table with automatic overlay addition enabled. `MergePath(input, true)` applies compiled inputs using overlay semantics. IDs are assigned and references linked before output is written.

The output combines an **accumulated resource table** with **file resources merged in this invocation**. `ResourceFileFlattener` selects files through `TableMerger::IsMergedFile()`, which requires a backing file and a source path in the merger's current list. APK-loaded file references are retained for lookup without replaying all historical resource bytes. History `84ee7999a` moves baseline entries into `final_table_`; `a5fbab9eb` corrects merged-file output membership.

The retained Manifest is replaced when the supplied `--manifest` path can be opened and its XML successfully processed. When the input stream reports an error, that replacement block is skipped and the previous Manifest is used. XML load or processing failure returns an error instead. `WriteApk()` writes a Manifest into the output; Jugg filters an unchanged Manifest before staging. Manifest compilation support was added in `35a087668`.

The ordinary R-generation gate compares per-resource-type entry counts before and after merge (`5aa0b8bf8`). Configuration-count changes and edits to existing values do not trigger it. When Java or text-symbol output is requested, `--force-generate-java` bypasses that count gate (`94ba66c8c`). It does not run unless an output destination is requested.

### 4.3 Failure and recovery boundary

`IncLinkAdv()` merges into the retained table before assigning IDs and linking references; later error returns do not restore the pre-merge table. Jugg's `ArscCompiler.doApkCompile()` releases the invoker when `incLinkCompile()` returns no outputs and initializes another on a later call.

`IncLinkCommand::Action()` deletes the old `_cacheIncLink` before loading its replacement and assigns the cache only on success. It does not explicitly clear the old pointer on the failure path. Jugg's `loadTable()` creates a fresh daemon and releases it on load failure.

## 5. Auxiliary Metadata and Compatibility Boundaries

### 5.1 Styleables

An APK table lacks the aggregate styleable declarations needed for R generation. Jugg reconstructs them from R classes and passes a line-oriented `name:attr1,attr2` file at load time. `processStyleableFile()` creates public styleable entries, including empty declarations. Local attributes are resolved with an empty package; external names such as `android_layout_gravity` are split at the first underscore and resolved through external symbols. Missing attributes are warned about and skipped, which can change array membership/indexes even when loading completes.

Relevant history: `c655e3cfd` (styleable import), `7a5dc3273` (public visibility to fix duplicate IDs), `1d0fd7c3c` (local attribute namespace to fix indexes), `c85dde759` (external attribute namespace).

### 5.2 Resource-name obfuscation

`processResGuardMappingFile()` accepts `type:original:obfuscated` and indexes it as `(type, obfuscated) -> original`. During APK load it restores entry names while retaining IDs, then restores named references in direct references, attribute symbols, styles, styleables, arrays and plurals. The mapping key does not include a package name.

Mapping and styleable files are load-time inputs. Passing new paths during an increment does not rebuild the already loaded table. File-open failures are warnings and the affected auxiliary import is omitted. History `911da7b12`, `caa40509f`, and `93314bfef` adds entry-name, attribute-symbol and value-reference de-obfuscation respectively.

### 5.3 Generated layout-v22 resources

An old generated v22 layout can outrank a newly changed default layout at runtime. `ResourceFileFlattener` detects an existing v22 configuration matching the current qualifiers apart from SDK level and asks `XmlCompatVersioner` to regenerate it. If an explicit matching v22 file is part of this merge, forced generation is disabled. Generated insertions use the keep-original collision rule.

History `4fcc2f32c` fixes stale runtime XML; `72d36996f` narrows matching and handles duplicate generation. Jugg additionally filters generated configurations when an explicit XML override would otherwise be overwritten; see the resource topic.

## 6. Diagnostic Producers

| Diagnostic | Producer and condition |
|---|---|
| `no cache data found, run with --load first` | `IncLinkCommand::Action()`: `_cacheIncLink == NULL` on an incremental invocation. |
| `multiply apk load not supported` | `LoadFromApk()`: positional input count is neither zero nor one; zero has its own `no apk provided` diagnostic. |
| `failed parsing input` | `IncLinkAdv()`: `MergePath(input, true)` returned false. Detailed input diagnostics are emitted in the merge path. |
| `failed assigning IDs` | `IncLinkAdv()`: `IdAssigner::Consume()` returned false. |
| `failed linking references` | `IncLinkAdv()`: `ReferenceLinker::Consume()` returned false. |
| `Unable to find attr ..., ignore attr` | `processStyleableFile()`: local/external attribute lookup failed; this attribute is skipped. |
| `isRJavaChange true` / `isRJavaChange false` | `IncLinkAdv()` emits the count-gate result before ID assignment and reference linking. `--force-generate-java` is a separate generation condition. |

Jugg-level diagnostic interpretation and log collection are covered in `02_compile_resource.md` and `09_plugin_runtime_debug.md`.

## 7. Existing Test Entry Points

Existing native test files include `cmd/Link_test.cpp`, `link/TableMerger_test.cpp` and `link/XmlCompatVersioner_test.cpp`. Repository verification policy is in [06_testing.md](06_testing.md).

## 8. Build and Publication

The standalone macOS Debug build, pinned Android 14 dependencies, smoke and LLDB evidence are documented in [02_compile_aapt2_build.md](02_compile_aapt2_build.md). `aapt2-inclink/scripts/build_macos.py` compiles maintained source directly; `source/development.md` retains upstream AOSP instructions.

Gradle packages prebuilts under `aapt2-inclink/src/main/resources/tools/`; local builds do not update them. Cross-platform publication remains separate work.
