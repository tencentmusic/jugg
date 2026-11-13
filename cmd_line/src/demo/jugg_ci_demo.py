#!/usr/bin/env python3
"""Run the CI sample against a disposable demo checkout and localhost storage."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import uuid
from datetime import datetime, timezone
from urllib.request import Request, urlopen
import zipfile

HERE = Path(__file__).resolve().parent
SCENARIOS = {
    'debug': ('assembleDebug', 'app/build/outputs/apk/debug/*-debug.apk'),
    'release': ('bundleReleaseToApk', 'app/build/outputs/bundle/release/duplicated-app.apk'),
}


def run(command, cwd):
    print('+', shlex.join(map(str, command)), flush=True)
    result = subprocess.run(list(map(str, command)), cwd=cwd, text=True, capture_output=True)
    print(result.stdout, end='')
    print(result.stderr, end='', file=sys.stderr)
    if result.returncode:
        raise RuntimeError(f'Command failed ({result.returncode}): {command}')
    return result.stdout.strip()


def request(server, path, record=None):
    data = None if record is None else json.dumps(record).encode()
    req = Request(server + path, data=data, headers={'Content-Type': 'application/json'})
    with urlopen(req, timeout=10) as response:
        return json.load(response)


def should_use_incremental(files, force_gradle):
    return (not force_gradle and bool(files)
            and all(f['type'] != 'BuildFile' and Path(f['file']).is_file() for f in files))


def prepare(work):
    project = work / 'project'
    if project.exists():
        raise RuntimeError(f'Use a new work directory; project already exists: {project}')
    archive = HERE / 'demo_project.zip'
    if archive.exists():
        with zipfile.ZipFile(archive) as source:
            source.extractall(work / 'unpacked')
        shutil.move(str(work / 'unpacked/demo_project'), project)
    else:
        source = HERE.parents[2] / 'android_demo_project'
        shutil.copytree(source, project, ignore=shutil.ignore_patterns('build', '.gradle', '.idea', '.git', '__pycache__'))
    wrapper = project / 'gradlew'
    if wrapper.exists():
        wrapper.chmod(wrapper.stat().st_mode | 0o100)
    run(['git', 'init'], project)
    run(['git', 'config', 'user.name', 'Jugg demo'], project)
    run(['git', 'config', 'user.email', 'jugg-demo@localhost'], project)
    commit(project, 'initial demo')
    return project


def commit(project, message):
    run(['git', 'add', '.'], project)
    run(['git', 'commit', '-m', message], project)
    return run(['git', 'rev-parse', 'HEAD'], project)


def modify(project):
    activity = project / 'app/src/main/java/com/example/myapplication/MainActivity.kt'
    text = activity.read_text()
    text, count = re.subn(r'(super\.onCreate\([^\n]*\))',
                         r'\1\n        android.widget.Toast.makeText(this, "Hello Jugg CI!", android.widget.Toast.LENGTH_SHORT).show()', text, count=1)
    if count != 1:
        raise RuntimeError('MainActivity onCreate anchor not found')
    activity.write_text(text)
    layout = project / 'app/src/main/res/layout/activity_main.xml'
    text, count = re.subn(r'android:text="[^"]*"', 'android:text="Hello Jugg CI!"', layout.read_text(), count=1)
    if count != 1:
        raise RuntimeError('Layout text anchor not found')
    layout.write_text(text)
    commit(project, 'change Kotlin and layout')


def build(project, work, args, force_gradle=False):
    target = run(['git', 'rev-parse', 'HEAD'], project)
    task, apk_glob = SCENARIOS[args.scenario]
    key = f'demo-{args.scenario}-v1'
    print(f'Commit: {target}; scenario: {key}')
    records = [r for r in request(args.server, '/base-builds')
               if r['scenarioKey'] == key and r['compileCommand'] == task
               and r.get('metadata', {}).get('projectDir') == str(project)]
    output = work / 'runs' / uuid.uuid4().hex
    output.mkdir(parents=True)

    def jugg(command, **params):
        return run([args.cmd_line, f'cmd={command}'] + [f'{k}={v}' for k, v in params.items()], project)

    base = None
    if records:
        candidates = output / 'candidates.json'
        candidates.write_text(json.dumps([r['baseCommit'] for r in records]))
        match_file = output / 'base-result.json'
        try:
            jugg('findNearestBaseCommit', projectDir=project, targetCommit=target,
                 candidateCommitsFile=candidates, output=match_file)
        except RuntimeError:
            if not match_file.exists() or json.loads(match_file.read_text()).get('matchedCommit') is not None:
                raise
        match = json.loads(match_file.read_text())
        base = next((r for r in records if r['baseCommit'] == match['matchedCommit']), None)
    print('Selected base:', base['baseCommit'] if base else 'none')
    files = []
    if base:
        copy = output / 'jugg'
        shutil.copytree(base['juggRootPath'], copy)
        changes = output / 'changed-files.json'
        jugg('getChangedFiles', sourceProjectDir=project, baseBuildJuggRootDir=copy,
             baseCommit=base['baseCommit'], targetCommit=target, output=changes)
        files = json.loads(changes.read_text())['files']
        print(f'ChangedFile total: {len(files)}; retained: {len(files)}')
    incremental = should_use_incremental(files, force_gradle)
    if incremental:
        jars = [HERE / 'custom_compilers' / name for name in
                ('custom_compiler_instrument-1.0.jar', 'dependency.jar')]
        for jar in jars:
            if not jar.is_file():
                raise RuntimeError(f'Missing custom compiler: {jar}')
        jugg('buildIncrementalApk', sourceProjectDir=project, baseBuildJuggRootDir=copy,
             outputApkDir=output / 'apk', changedFiles=os.pathsep.join(f['file'] for f in files),
             customCompilerJars=os.pathsep.join(map(str, jars)))
    else:
        jugg('buildGradleBase', baseBuildProjectDir=project, gradleCompileTask=task,
             gradleOutputApkPath=apk_glob, outputApkDir=output / 'apk')
        if args.scenario == 'release':
            mapping = project / 'app/build/outputs/mapping/release/mapping.txt'
            shutil.copy2(mapping, output / 'mapping.txt')
            usage = mapping.with_name('usage.txt')
            if usage.exists():
                shutil.copy2(usage, output / 'usage.txt')
        shutil.copytree(project / 'build/jugg', output / 'baseline')
    apks = list((output / 'apk').glob('*.apk'))
    if not apks or any(p.stat().st_size == 0 for p in apks):
        raise RuntimeError('Build did not produce nonempty APKs')
    # Only clean Gradle snapshots can be consumed by a later incremental build.
    if not incremental:
        record = dict(id=hashlib.sha256(str(output).encode()).hexdigest(), baseCommit=target,
                      scenarioKey=key, compileCommand=task, apkPath=str(apks[0]),
                      juggRootPath=str(output / 'baseline'),
                      createdAt=datetime.now(timezone.utc).isoformat(), metadata={'projectDir': str(project)})
        request(args.server, '/base-builds', record)
        print('Saved baseline:', record['id'])
    print('Build:', 'buildIncrementalApk' if incremental else 'buildGradleBase', 'APKs:', apks)
    return apks


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--scenario', choices=SCENARIOS, default='debug')
    parser.add_argument('--cmd-line', default=str(HERE.parent / 'bin/cmd_line'))
    parser.add_argument('--work-dir', type=Path, required=True)
    parser.add_argument('--server', default='http://127.0.0.1:18765')
    parser.add_argument('--force-gradle', action='store_true', help='Force the second build to use Gradle')
    parser.add_argument('--install', action='store_true')
    args = parser.parse_args()
    work = args.work_dir.resolve()
    work.mkdir(parents=True, exist_ok=True)
    args.cmd_line = str(Path(args.cmd_line).resolve())
    request(args.server, '/base-builds')
    project = prepare(work)
    build(project, work, args)
    modify(project)
    apks = build(project, work, args, args.force_gradle)
    if args.install:
        run(['adb', 'install-multiple', '-r', *apks], project)
        run(['adb', 'shell', 'am', 'start', '-n', 'com.example.myapplication/.MainActivity'], project)


if __name__ == '__main__':
    main()
