#!/usr/bin/env python3
"""Build the current committed checkout once, selecting Gradle or incremental automatically."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import uuid
from datetime import datetime, timezone
from urllib.parse import urlencode
from urllib.request import Request, urlopen

HERE = Path(__file__).resolve().parents[1]
SCENARIOS = {
    # The demo redirects every module build directory to root/build/<module>.
    'debug': ('assembleDebug', 'build/app/outputs/apk/debug/app-debug.apk'),
    'release': ('bundleReleaseToApk', 'build/app/outputs/bundle/release/duplicated-app.apk'),
}


def run(command, cwd):
    print('+', shlex.join(map(str, command)), flush=True)
    result = subprocess.run(list(map(str, command)), cwd=cwd, text=True, capture_output=True)
    print(result.stdout, end='')
    print(result.stderr, end='', file=sys.stderr)
    if result.returncode:
        raise RuntimeError(f'Command failed ({result.returncode}): {command}')
    return result.stdout.strip()


def request(server, path, record=None, project_id='default'):
    data = None if record is None else json.dumps(record).encode()
    separator = '&' if '?' in path else '?'
    req = Request(server + path + separator + urlencode({'projectId': project_id}),
                  data=data, method='GET' if record is None else 'PUT',
                  headers={'Content-Type': 'application/json'})
    # Saving now copies the complete baseline before replying, rather than just writing JSON.
    with urlopen(req, timeout=10 if record is None else 120) as response:
        return json.load(response)


# Business policy, not a Jugg compiler limit. CI owners can adjust this threshold.
# Exactly 100 changed files are eligible; 101 or more require a new Gradle baseline.
MAX_INCREMENTAL_FILES = 100


def incremental_rejection_reason(files, force_gradle):
    """Keep all policy decisions here so every caller uses the same rules."""
    if force_gradle:
        return 'Gradle explicitly requested'
    if any(f['type'] == 'BuildFile' for f in files):
        return 'build file changed'
    if any(f['type'] == 'Unknown' for f in files):
        return 'unrecognized changed files'
    if len(files) > MAX_INCREMENTAL_FILES:
        return f'{len(files)} changed files exceed limit {MAX_INCREMENTAL_FILES}'
    if any(not Path(f['file']).is_file() for f in files):
        return 'changed input is missing'
    return None


def should_use_incremental(files, force_gradle):
    return any(f['type'] != 'Unknown' for f in files) and incremental_rejection_reason(files, force_gradle) is None


def build(project, work, args, force_gradle=False):
    target = run(['git', 'rev-parse', 'HEAD'], project)
    task, apk_glob = SCENARIOS[args.scenario]
    key = f'demo-{args.scenario}-v1'
    print(f'Project: {args.project_id}; commit: {target}; scenario: {key}')
    metadata = checkout_metadata(project)
    # A newly prepared demo checkout must not consume archives from a previous walkthrough.
    records = [r for r in request(args.server, '/base-builds', project_id=args.project_id)
               if r['scenarioKey'] == key and r['compileCommand'] == task
               and all(r.get('metadata', {}).get(k) == v for k, v in metadata.items())
               and Path(r['juggRootPath']).is_dir()]
    output = work / 'runs' / uuid.uuid4().hex
    output.mkdir(parents=True)

    def jugg(command, **params):
        return run([args.cmd_line, f'cmd={command}'] + [f'{k}={v}' for k, v in params.items()], project)

    try:
        base = select_base(jugg, project, target, records, output)
        copy = output / 'jugg'
        files = changed_files(jugg, project, target, base, copy, output) if base else []
        files = filter_changed_files(project, files)
        rejection = incremental_rejection_reason(files, force_gradle) if base else 'no compatible baseline'
        if rejection is None:
            build_incremental(jugg, project, copy, output, files)
            built_apks(output / 'apk')
    except (RuntimeError, OSError, ValueError) as exception:
        rejection = f'Jugg CI failed: {exception}'
        print(f'Jugg CI 失败，降级为 Gradle：{exception}', flush=True)
    if rejection is not None:
        build_gradle(jugg, project, output, args.scenario, task, apk_glob)
        built_apks(project / 'build/jugg/classpath/apk')
        baseline = save_baseline(args.server, project, output, target, key, task, args.project_id)
        apk_dir = Path(baseline['juggRootPath']) / 'classpath/apk'
        mode = 'buildGradleBase'
    else:
        apk_dir = output / 'apk'
        mode = 'buildIncrementalApk' if files else 'reuseBaseApk'
    print('Decision:', rejection or mode)
    apks = built_apks(apk_dir)
    # Publish a machine-readable result only after artifacts and baseline registration succeed.
    (work / 'build-result.json').write_text(json.dumps(
        dict(commit=target, mode=mode, reason=rejection, apkDir=str(apk_dir), runDir=str(output)), indent=2))
    print('Build:', mode, 'APKs:', apks)
    return apks


def filter_changed_files(project, files):
    # This project explicitly declares only its root README irrelevant to APK contents.
    # Every other unknown input may affect custom build logic and must fall back.
    ignored = [f for f in files if Path(f['file']) == project / 'README.md']
    if ignored:
        print('编排主动忽略文档：' + ', '.join(f['file'] for f in ignored), flush=True)
    return [f for f in files if f not in ignored]


def checkout_metadata(project):
    marker = project / '.git/jugg-demo-id'
    return dict(projectDir=str(project), demoCheckoutId=marker.read_text().strip() if marker.exists() else None)


def built_apks(directory):
    apks = sorted(directory.glob('*.apk'))
    if not apks or any(p.stat().st_size == 0 for p in apks):
        raise RuntimeError('Build did not produce nonempty APKs')
    return apks


def select_base(jugg, project, target, records, output):
    # Let Jugg resolve first-parent ancestry rather than guessing from timestamps.
    if records:
        candidates = output / 'candidates.json'
        candidates.write_text(json.dumps([r['baseCommit'] for r in records]))
        match_file = output / 'base-result.json'
        try:
            jugg('findNearestBaseCommit', projectDir=project, targetCommit=target,
                 candidateCommitsFile=candidates, output=match_file)
        except RuntimeError:
            # An explicit no-match result is expected; other command failures must propagate.
            if not match_file.exists() or json.loads(match_file.read_text()).get('matchedCommit') is not None:
                raise
        match = json.loads(match_file.read_text())
        return next((r for r in records if r['baseCommit'] == match['matchedCommit']), None)
    return None


def changed_files(jugg, project, target, base, copy, output):
    # Incremental builds mutate history/database and mark this copy dirty.
    # Never hand the immutable stored baseline directly to the compiler.
    shutil.copytree(base['juggRootPath'], copy)
    changes = output / 'changed-files.json'
    jugg('getChangedFiles', sourceProjectDir=project, baseBuildJuggRootDir=copy,
         baseCommit=base['baseCommit'], targetCommit=target, output=changes)
    files = json.loads(changes.read_text())['files']
    print(f'ChangedFile total: {len(files)}; retained: {len(files)}')
    return files


def build_incremental(jugg, project, copy, output, files):
    jars = [HERE / 'custom_compilers' / name for name in
            ('custom_compiler_instrument-1.0.jar', 'dependency.jar')]
    if not files:
        jars = []
    for jar in jars:
        if not jar.is_file():
            raise RuntimeError(f'Missing custom compiler: {jar}')
    jugg('buildIncrementalApk', sourceProjectDir=project, baseBuildJuggRootDir=copy,
         outputApkDir=output / 'apk', changedFiles=os.pathsep.join(f['file'] for f in files),
         customCompilerJars=os.pathsep.join(map(str, jars)))


def build_gradle(jugg, project, output, scenario, task, apk_glob):
    jugg('buildGradleBase', baseBuildProjectDir=project, gradleCompileTask=task,
         gradleOutputApkPath=apk_glob)
    if scenario == 'release':
        mapping = project / 'build/app/outputs/mapping/release/mapping.txt'
        shutil.copy2(mapping, output / 'mapping.txt')
        usage = mapping.with_name('usage.txt')
        if usage.exists():
            shutil.copy2(usage, output / 'usage.txt')


def save_baseline(server, project, output, target, key, task, project_id):
    # Only clean Gradle snapshots can be consumed by a later incremental build.
    record = dict(id=hashlib.sha256(str(output).encode()).hexdigest(), projectId=project_id, baseCommit=target,
                  scenarioKey=key, compileCommand=task, juggRootPath=str(project / 'build/jugg'),
                  createdAt=datetime.now(timezone.utc).isoformat(), metadata=checkout_metadata(project))
    saved = request(server, '/base-builds', record, project_id=project_id)
    print('Saved baseline:', record['id'])
    return saved


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--scenario', choices=SCENARIOS, default='debug')
    parser.add_argument('--cmd-line', default=str(HERE.parent / 'bin/cmd_line'))
    parser.add_argument('--work-dir', type=Path, required=True)
    parser.add_argument('--server', default='http://127.0.0.1:18766')
    parser.add_argument('--project-id', default='default', help='基线服务中的项目标识')
    parser.add_argument('--force-gradle', action='store_true', help='Explicitly request a new Gradle baseline')
    args = parser.parse_args()
    work = args.work_dir.resolve()
    project = work / 'project'
    if not (project / '.git').is_dir():
        parser.error(f'Prepare a Git checkout first: {project}')
    args.cmd_line = str(Path(args.cmd_line).resolve())
    build(project, work, args, args.force_gradle)


if __name__ == '__main__':
    main()
