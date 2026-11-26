#!/usr/bin/env python3
"""Build the current commit using a compatible CI baseline or Gradle."""
import argparse
from contextlib import suppress
from datetime import datetime, timezone
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import sys
from time import monotonic
import uuid
import zipfile

from _common import HERE, log, run, run_logged

# Load the packaged HTTP client when invoked from another directory.
sys.path.insert(0, str(HERE))
from demo_flow.baseline_service import download as download_artifact, records as api, upload as upload_artifact

# Project and CI configuration
DEFAULT_SERVER = 'http://127.0.0.1:18766'
SCENARIOS = {
    # Module outputs live under the root build directory.
    'debug': ('assembleDebug', 'build/app/outputs/apk/debug/app-debug.apk'),
    'release': ('bundleReleaseToApk', 'build/app/outputs/bundle/release/duplicated-app.apk'),
}


# The CI policy chooses the maximum incremental file count.
MAX_INCREMENTAL_FILES = 100
# Match either changed annotation lines or all changed source text.
DIFF_FALLBACK_ANNOTATIONS = (
    'Keep',  # The release build uses R8 and must recompute keep rules.
    # 'Route',  # Removing the last route on a class requires Gradle.
    # 'BindingAdapter',  # Handled by incremental Data Binding.
    # 'Composable', 'Preview', 'Parcelize',  # Project compiler plugins are reused.
    # 'OptIn', 'HiddenFromObjC',  # Project KMP opt-ins are reused.
)
FULL_FALLBACK_ANNOTATIONS = (
    # 'BindView', 'BindString', 'OnClick',  # BV aliases BindView in the demo.
    # 'JsonClass', 'Json',  # The demo uses Moshi with KSP1.
    # Hilt graph changes require annotation processing by Gradle.
    'Inject', 'AndroidEntryPoint', 'HiltAndroidApp',
)

# Enable when downstream CI signs the delivered APK.
SKIP_SIGN = False
LOCAL_JUGG_CI_PATH = HERE.parent / 'bin/cmd_line'
CUSTOM_COMPILER_JARS = (
    HERE / 'custom_compilers/custom_compiler_instrument-1.0.jar',
    HERE / 'custom_compilers/dependency.jar',
)


def filter_changed_files(project, files):
    # Only the root README is explicitly excluded from APK inputs.
    ignored = [f for f in files if Path(f['file']) == project / 'README.md']
    if ignored:
        log(5, '编排主动忽略文档：' + ', '.join(f['file'] for f in ignored))
    return [f for f in files if f not in ignored]


def detect_repo_fallback_reason(diff, files, scenario):
    """Check the repository Java/Kotlin patch and typed file list for fallback reasons."""
    # Diff rules only inspect added and deleted lines.
    diff_annotations = DIFF_FALLBACK_ANNOTATIONS
    if scenario != 'release':
        diff_annotations = tuple(name for name in diff_annotations if name != 'Keep')
    annotation = find_diff_annotation(diff, diff_annotations)
    if annotation:
        return f'Git diff 命中业务注解 @{annotation}，需要 Gradle 重新处理'

    # Check the patch as well, to catch removal of the last annotation.
    annotation = find_diff_annotation(diff, FULL_FALLBACK_ANNOTATIONS)
    if annotation:
        return f'Git diff 命中全文规则注解 @{annotation}，需要 Gradle 重新处理'
    for change in files:
        if change['type'] not in ('Java', 'Kotlin'):
            continue
        path = Path(change['file'])
        if not path.is_file():
            continue
        annotation = find_annotation(path.read_text(encoding='utf-8'), FULL_FALLBACK_ANNOTATIONS)
        if annotation:
            return f'变更文件 {path} 含全文规则注解 @{annotation}，需要 Gradle 重新处理'

    return None


# Shared build orchestration

def make_scenario_key(project, task):
    properties = project / 'local.properties'
    checksum = hashlib.sha256(properties.read_bytes() if properties.exists() else b'').hexdigest()
    return f'demo-v2:{task}:{checksum[:12]}'


def find_diff_annotation(diff, annotations):
    """Match added or removed patch lines, excluding headers and context."""
    changed_lines = '\n'.join(line[1:] for line in diff.splitlines()
                              if line.startswith(('+', '-')) and not line.startswith(('+++', '---')))
    return find_annotation(changed_lines, annotations)


def find_annotation(source, annotations):
    """Match the first configured annotation, including qualified names and Kotlin use-site targets."""
    for annotation in annotations:
        pattern = r'@(?:[\w$]+:)?(?:[\w$]+\.)*' + re.escape(annotation) + r'(?![\w$.])'
        if re.search(pattern, source):
            return annotation
    return None


def validate_and_get_target_commit(project):
    # Commit-indexed baselines require a clean checkout including untracked files.
    # Ignored build outputs are excluded.
    # The scenario key includes the local.properties digest.
    if run(['git', 'status', '--porcelain', '--untracked-files=normal'], project):
        raise RuntimeError('请先提交或移走工作区改动；CI 只构建干净 checkout')
    return run(['git', 'rev-parse', 'HEAD'], project)




def check_changed_files_fallback(files):
    """Check file count, types, and existence."""
    if len(files) > MAX_INCREMENTAL_FILES:
        return f'变更超过 {MAX_INCREMENTAL_FILES} 个文件'
    for change in files:
        if change['type'] in ('BuildFile', 'Unknown'):
            return f'存在需要 Gradle 处理的构建输入：{change["file"]}'
    if any(not Path(f['file']).is_file() for f in files):
        return '存在缺失的变更输入'
    return None


def find_nearest_commit(jugg, root, commits, target, directory):
    """Return the nearest first-parent candidate or None."""
    directory.mkdir(parents=True, exist_ok=True)
    candidates, result = directory / 'commits.json', directory / 'result.json'
    candidates.write_text(json.dumps(list(dict.fromkeys(commits))))
    try:
        jugg('findNearestBaseCommit', projectDir=root, targetCommit=target,
             candidateCommitsFile=candidates, output=result)
    except RuntimeError:
        if not result.exists() or json.loads(result.read_text()).get('matchedCommit') is not None:
            raise
    return json.loads(result.read_text())['matchedCommit']


def resolve_repository_root(repo, record, project):
    """Recover repository locations relative to the original checkout for legacy records."""
    root = Path(repo['gitRoot'])
    if root.is_absolute():
        previous_project = record.get('metadata', {}).get('projectDir', str(project))
        root = Path(os.path.relpath(root, previous_project))
    return str((project / root).resolve())


def relocate_baseline(baseline, project):
    """Relocate source and backup paths in the downloaded copy."""
    marker = baseline / 'database/project_infos.db/history_project_dir.txt'
    if not marker.exists():
        return
    started = monotonic()
    previous = Path(marker.read_text().strip())
    current = project.resolve()
    history_file = baseline / 'database/deploy_history.db/deploy_history.json'
    history = json.loads(history_file.read_text())
    roots = [previous] + [Path(root) if Path(root).is_absolute() else previous / root
                         for root in history.get('subModulesFullCompileGitCommitHash', {})]
    replacements = {str(previous / 'build/jugg'): str(baseline.resolve())}
    for root in roots:
        replacements[str(root)] = str((current / os.path.relpath(root, previous)).resolve())
    # Replace the longest prefix first to distinguish backup directories from source roots.
    prefixes = sorted(replacements, key=len, reverse=True)

    def relocate(value):
        if isinstance(value, dict):
            return {relocate(key): relocate(item) for key, item in value.items()}
        if isinstance(value, list):
            return [relocate(item) for item in value]
        if isinstance(value, str):
            for prefix in prefixes:
                if value == prefix or value.startswith(prefix + '/'):
                    return replacements[prefix] + value[len(prefix):]
        return value

    for path in (baseline / 'database').rglob('*.json'):
        original = json.loads(path.read_text())
        relocated = relocate(original)
        if relocated != original:
            path.write_text(json.dumps(relocated, ensure_ascii=False, indent=2))
    marker.write_text(str(current))
    log(4, f'基线路径恢复：{previous} -> {current}，耗时 {monotonic() - started:.2f}s')


def find_nearest_candidates(jugg, records, target, output, project):
    """Find nearest candidates per repository and deduplicate by record ID."""
    if not records:
        return []
    main_root = str(Path(run(['git', 'rev-parse', '--show-toplevel'], project)).resolve())
    candidates = []
    for record in records:
        # Legacy records identify only the primary commit. Jugg checks external commits after download.
        commits = {resolve_repository_root(repo, record, project): repo['commit']
                   for repo in record.get('repositories', [])}
        commits[main_root] = record['baseCommit']
        candidates.append((record, commits))
    roots = [root for _, commits in candidates for root in commits]
    selected = set()
    for index, root in enumerate(dict.fromkeys(roots), 1):
        commits = [commits[root] for _, commits in candidates if root in commits]
        if not commits:
            continue
        try:
            repository_target = target if root == main_root else run(['git', '-C', root, 'rev-parse', 'HEAD'])
            matched = find_nearest_commit(jugg, root, commits, repository_target, output / 'selection' / str(index))
        except (RuntimeError, OSError, ValueError, KeyError) as error:
            log(3, f'仓库最近提交查询失败：{root}：{error}', error=True)
            continue
        if matched is None:
            continue
        # Records arrive newest first. Prefer the first record for a tied commit.
        record = next((record for record, commits in candidates if commits.get(root) == matched), None)
        if record is not None:
            selected.add(record['id'])
            log(3, f'仓库 {root} 命中 {matched}，基线 {record["id"]}')
    result = []
    for record, _ in candidates:
        if record['id'] in selected:
            result.append(record)
            selected.remove(record['id'])
    log(3, f'去重后待检查基线：{len(result)} 份')
    return result


def evaluate_baseline_candidate(jugg, record, target, directory, diff_cache, project, server, scenario):
    """Inspect an isolated baseline copy and reuse Git diffs across candidates."""
    directory.mkdir(parents=True)
    baseline, changes = directory / 'jugg', directory / 'changed-files.json'
    log(4, f'下载基线 ZIP：{record["artifactId"]}')
    download_artifact(server, record['artifactId'], baseline)
    relocate_baseline(baseline, project)
    jugg('getChangedFiles', sourceProjectDir=project, baseBuildJuggRootDir=baseline,
         baseCommit=record['baseCommit'], targetCommit=target, output=changes)
    document = json.loads(changes.read_text())
    repositories = document.get('repositories', [])
    if record.get('repositories'):
        saved = {resolve_repository_root(repo, record, project): repo['commit'] for repo in record['repositories']}
        actual = {str(Path(repo['gitRoot']).resolve()): repo['baseCommit'] for repo in repositories}
        if saved != actual:
            raise ValueError('基线记录的仓库提交与 ZIP 内 history 不一致')
    raw_files = document['files']
    files = filter_changed_files(project, raw_files)
    log(5, f'基线 {record["id"]}：原始 {len(raw_files)} 个，过滤后 {len(files)} 个文件')
    reason = check_changed_files_fallback(files)
    if reason is None:
        reason = check_changed_content_fallback(repositories, directory, diff_cache, files, scenario)
    return dict(base=record, reason=reason, files=files, raw_files=raw_files,
                repositories=repositories, jugg_dir=baseline)


def choose_baseline(jugg, records, target, output, project, server, scenario):
    """Select the eligible candidate with the fewest effective changes."""
    last = dict(base=None, reason='无兼容的祖先基线', files=[], raw_files=None, repositories=[])
    reports, diff_cache = [], {}
    eligible = []
    for record in find_nearest_candidates(jugg, records, target, output, project):
        directory = output / 'candidates' / str(len(reports) + 1)
        try:
            state = evaluate_baseline_candidate(jugg, record, target, directory, diff_cache, project, server, scenario)
        except (RuntimeError, OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
            state = dict(base=record, reason='Jugg 基线复用失败: ' + str(error),
                         files=[], raw_files=None, repositories=[])
        last = state
        reports.append(dict(id=record['id'], reason=state['reason'],
                            changedFileCount=len(state['files']) if state['raw_files'] is not None else None))
        (output / 'candidate-results.json').write_text(json.dumps(reports, ensure_ascii=False, indent=2))
        if state['reason'] is None:
            eligible.append(state)
        else:
            log(5, f'跳过基线 {record["id"]}：{state["reason"]}')
    if eligible:
        # Keep server ordering for ties and retain a single baseline for all modules.
        selected = min(eligible, key=lambda state: len(state['files']))
        log(6, f'选中基线 {selected["base"]["id"]}，有效变更 {len(selected["files"])} 个')
        return selected
    return last


def get_repository_diff(root, base, target, cache):
    key = (root, base, target)
    if key not in cache:
        if base == target:
            cache[key] = ''
        else:
            cache[key] = run(['git', '-C', root, 'diff', '--no-ext-diff', '--no-textconv', '--no-color',
                              '--unified=0', base, target, '--', '*.java', '*.kt'])
    return cache[key]


def check_changed_content_fallback(repositories, output, cache, files, scenario):
    """Apply source and annotation rules to each repository commit range."""
    if not repositories:
        raise ValueError('getChangedFiles 未返回 repositories，请更新 cmd_line 发行包')
    for index, repo in enumerate(repositories, 1):
        root, base, target = repo['gitRoot'], repo['baseCommit'], repo['targetCommit']
        log(5, f'分析仓库 {index}/{len(repositories)}：{root}，{base} -> {target}')
        diff = get_repository_diff(root, base, target, cache)
        (output / f'source-diff-{index}.patch').write_text(diff)
        # Group entries by the gitRoot and type reported by Jugg.
        repo_files = [change for change in files if change['gitRoot'] == root]
        log(5, f'检查文件类型与注解：{root}，变更 {len(repo_files)} 个文件')
        reason = detect_repo_fallback_reason(diff, repo_files, scenario)
        if reason is not None:
            log(5, f'业务降级规则命中：{reason}')
            return f'{root}: {reason}'
    log(5, '所有仓库的业务降级规则检查通过')
    return None


def publish_baseline(output, target, *, project, args, metadata, scenario_key, gradle_task):
    """Upload the baseline ZIP and register its artifact ID in a separate metadata request."""
    # Publish only when build history matches the target commit.
    root = project / 'build/jugg'
    history = json.loads((root / 'database/deploy_history.db/deploy_history.json').read_text())
    if history.get('changedFiles'):
        raise RuntimeError('Gradle 基线包含未提交源码，拒绝上传登记')
    if history.get('fullCompileGitCommitHash') != target:
        raise RuntimeError('Gradle 基线主仓库提交与目标提交不一致')
    external = [dict(gitRoot=path, targetCommit=commit)
                for path, commit in history.get('subModulesFullCompileGitCommitHash', {}).items()]
    main_root = str(Path(run(['git', 'rev-parse', '--show-toplevel'], project)).resolve())
    repositories = {main_root: dict(gitRoot=main_root, targetCommit=target)}
    for repo in external:
        root_path = str((project / repo['gitRoot']).resolve())
        if root_path == main_root and repo['targetCommit'] != target:
            raise RuntimeError('Gradle 基线主仓库提交记录不一致')
        repositories[root_path] = dict(gitRoot=root_path, targetCommit=repo['targetCommit'])
    log(8, f'上传基线 ZIP：{root}')
    artifact_id = upload_artifact(args.server, root)
    record = dict(id=uuid.uuid4().hex, projectId=args.project_id, baseCommit=target,
                  scenarioKey=scenario_key, compileCommand=gradle_task, artifactId=artifact_id,
                  createdAt=datetime.now(timezone.utc).isoformat(),
                  metadata=metadata,
                  repositories=[dict(gitRoot=Path(os.path.relpath(repo['gitRoot'], project)).as_posix(), commit=repo['targetCommit'])
                                for repo in repositories.values()])
    # Keep the upload receipt if registration fails so the artifact can be traced.
    receipt = output / 'saved-baseline.json'
    receipt.write_text(json.dumps(record, indent=2))
    log(9, f'登记基线：{artifact_id}')
    saved = api(args.server, args.project_id, record)
    receipt.write_text(json.dumps(saved, indent=2))
    return saved


def build(project, work, args):
    """Write a success result after the build and any required archival complete."""
    dry_run = args.dry_run
    # The sequential demo shares its checkout and service across runs.
    result = work / ('dry-run-result.json' if dry_run else 'build-result.json')
    result.unlink(missing_ok=True)
    gradle_task, apk_path = SCENARIOS[args.scenario]
    scenario_key = make_scenario_key(project, gradle_task)
    marker = project / '.git/jugg-demo-id'
    metadata = dict(projectDir=str(project), demoCheckoutId=marker.read_text().strip()) if marker.exists() else {}
    print(flush=True)
    log(1, f'开始{"模拟" if dry_run else "构建"}：project={project}，scenario={scenario_key}')
    log(1, f'本轮 Gradle 命令：{gradle_task}')
    target = validate_and_get_target_commit(project)
    log(1, f'工作区检查完成：commit={target}')

    # Preserve each run’s change list, logs and baseline copy.
    runs_dir = work / 'runs'
    output = runs_dir / uuid.uuid4().hex
    output.mkdir(parents=True)
    apk_dir = output / 'apk'
    log(1, f'本轮诊断目录：{output}')
    log(2, f'使用仓库 Jugg CI 启动器：{args.cmd_line}')

    def jugg(command, **params):
        # Pass each key=value pair as one argument.
        # Preserve command output on both success and failure.
        step = {'findNearestBaseCommit': 3, 'getChangedFiles': 5}.get(command, 7)
        log(step, f'执行 Jugg 命令：{command}')
        started = monotonic()
        log_path = Path(params['output']).parent / (command + '.log') if 'output' in params else output / (command + '.log')
        try:
            run_logged([args.cmd_line, 'cmd=' + command] + [f'{k}={v}' for k, v in params.items()],
                       project, step, log_path)
        except (RuntimeError, OSError):
            log(step, f'{command} 失败，耗时 {monotonic() - started:.2f}s，日志：{log_path}', error=True)
            raise
        log(step, f'{command} 完成，耗时 {monotonic() - started:.2f}s，日志：{log_path}')

    # A fallback reason triggers one Gradle build.
    reason = '已主动选择 Gradle 构建' if args.force_gradle else None
    files = []
    raw_files = None
    base = None
    previous_baseline_dir = None
    repositories = []
    if reason is None:
        try:
            # Restrict ancestor selection to compatible build configurations.
            records = [r for r in api(args.server, args.project_id)
                       if r.get('scenarioKey') == scenario_key and r.get('artifactId')
                       and all(r.get('metadata', {}).get(k) == v for k, v in metadata.items())]
            log(3, f'兼容候选基线：{len(records)} 条，主仓库目标提交：{target}')
            selection = choose_baseline(jugg, records, target, output, project, args.server, args.scenario)
            base, reason = selection['base'], selection['reason']
            files, raw_files = selection['files'], selection['raw_files']
            repositories = selection['repositories']
            if reason is not None:
                previous_baseline_dir = selection.get('jugg_dir')
            else:
                # The sequential demo reads the selected baseline’s original changes from this directory.
                shutil.copy2(selection['jugg_dir'].parent / 'changed-files.json', output / 'changed-files.json')
                log(6, f'构建决策：增量编译 {len(files)} 个文件' if files else '构建决策：无有效变更，导出基线 APK')
                if dry_run:
                    log(7, '模拟：将增量编译' if files else '模拟：将导出基线 APK')
                else:
                    signing_options = {'skipSign': str(SKIP_SIGN).lower()} if files else {}
                    jars = CUSTOM_COMPILER_JARS if files else ()
                    for jar in jars:
                        if not jar.is_file():
                            raise RuntimeError(f'缺少演示自定义编译器：{jar}')
                    jugg('buildIncrementalApk', sourceProjectDir=project,
                         baseBuildJuggRootDir=selection['jugg_dir'], outputApkDir=apk_dir,
                         changedFiles=os.pathsep.join(f['file'] for f in files),
                         customCompilerJars=os.pathsep.join(map(str, jars)), **signing_options)

        except (RuntimeError, OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
            # Use Gradle once on failure. Incremental copies may already have been consumed.
            reason = 'Jugg 基线复用失败: ' + str(error)
    if reason is not None:
        log(6, ('模拟：将执行 Gradle；原因：' if dry_run else '构建决策：执行 Gradle；原因：') + reason)
        if not dry_run:
            # Remove partial APKs before collecting Gradle outputs.
            if apk_dir.exists():
                shutil.rmtree(apk_dir)
            cache_option = {'previousBaselineDir': previous_baseline_dir} if previous_baseline_dir else {}
            jugg('buildGradleBase', baseBuildProjectDir=project, gradleCompileTask=gradle_task,
                 gradleOutputApkPath=apk_path, outputApkDir=apk_dir, **cache_option)
            if args.scenario == 'release':
                mapping = project / 'build/app/outputs/mapping/release/mapping.txt'
                shutil.copy2(mapping, output / 'mapping.txt')
                usage = mapping.with_name('usage.txt')
                if usage.exists():
                    shutil.copy2(usage, output / 'usage.txt')
            # Only a full Gradle build produces the next baseline.
            # An archival error fails the build.
            publish_baseline(output, target, project=project, args=args, metadata=metadata,
                             gradle_task=gradle_task, scenario_key=scenario_key)
        mode = 'buildGradleBase'
    else:
        mode = 'buildIncrementalApk' if files else 'reuseBaseApk'
    if dry_run:
        # Preview reports the decision before any APK is built.
        payload = dict(dryRun=True, commit=target, plannedMode=mode, reason=reason,
                       baseCommit=base['baseCommit'] if base else None,
                       changedFileCount=len(files) if raw_files is not None else None, runDir=str(output))
    else:
        # Collect the APK paths reported by the successful build.
        payload = dict(commit=target, mode=mode, reason=reason, runDir=str(output),
                       apkDir=str(apk_dir), apks=list(map(str, sorted(apk_dir.glob('*.apk')))))
    payload['baseId'] = base['id'] if base and reason is None else None
    payload['repositories'] = repositories
    result.write_text(json.dumps(payload, ensure_ascii=False, indent=2))
    log(10, f'{"模拟完成，计划" if dry_run else "构建成功"}：mode={mode}，commit={target}')
    if not dry_run:
        log(10, f'APK 目录：{apk_dir}')
    log(10, f'结果文件：{result}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--scenario', choices=SCENARIOS, default='debug')
    parser.add_argument('--cmd-line', default=str(LOCAL_JUGG_CI_PATH))
    parser.add_argument('--work-dir', type=Path, required=True)
    parser.add_argument('--server', default=DEFAULT_SERVER)
    parser.add_argument('--project-id', default='default', help='基线服务中的项目标识')
    parser.add_argument('--force-gradle', action='store_true', help='强制构建并登记新的 Gradle 基线')
    parser.add_argument('--dry-run', action='store_true', help='仅分析构建决策，不构建或登记基线')
    args = parser.parse_args()
    work = args.work_dir.resolve()
    project = work / 'project'
    if not (project / '.git').exists():
        parser.error(f'Prepare a Git checkout first: {project}')
    args.cmd_line = str(Path(args.cmd_line).resolve())
    try:
        build(project, work, args)
    except (RuntimeError, OSError, ValueError, KeyError, zipfile.BadZipFile) as error:
        log(0, ('CI 模拟失败: ' if args.dry_run else 'CI 构建失败: ') + str(error), error=True)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
