"""Measure isolated transfers of real project sources and compiled artifacts without printing credentials."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import statistics
import time
import uuid

from windows_rsync_acceptance import SafeProbe, failure
from windows_rsync_probe import PUSH


def hashes(root):
    return {p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in root.rglob('*') if p.is_file() and not p.is_symlink()}


def measure(probe, samples, phase, arguments, source, destination):
    probe.phase = phase
    start = time.perf_counter()
    probe.transfer(arguments, source, destination)
    samples[phase].append(round(time.perf_counter() - start, 4))


def make_probe(private, engine, output):
    secret = private['remoteSshPassword']
    keys = [str(Path(secret).resolve())] if Path(secret).is_file() else []
    directories = (Path(entry.strip().strip('"')) for entry in os.environ.get('PATH', '').split(os.pathsep) if entry.strip())
    executable = next((directory / 'rsync.exe' for directory in directories
                       if directory.is_absolute() and (directory / 'rsync.exe').is_file()), None)
    if executable is None:
        raise RuntimeError('rsync_not_found:exit_1')
    tools = executable.parent
    if not all((tools / name).is_file() for name in ['ssh.exe', 'cygpath.exe']):
        raise RuntimeError('missing_sibling_tools:exit_1')
    config = dict(engine=engine, target=private['remoteSshUser'] + '@' + private['remoteSshIp'],
                  port=private['remoteSshPort'], keys=keys, password=None if keys else secret,
                  known_hosts=str(output / 'known_hosts'), timeout=300,
                  cygpath=str(tools / 'cygpath.exe'), rsync=str(executable),
                  ssh=str(tools / 'ssh.exe'))
    probe = SafeProbe(config, output)
    if not keys:
        helper = Path('main/src/main/resources/tools/windows/rsync/askpass.exe').resolve()
        probe.env.update(SSH_ASKPASS=probe.local(helper), SSH_ASKPASS_REQUIRE='force',
                         DISPLAY='jugg:0', JUGG_RSYNC_PASSWORD=secret)
    return probe


def push_round(probe, source, iteration, samples):
    target = probe.config['target'] + ':'
    push = target + probe.remote + f'/source-{iteration}/'
    measure(probe, samples, 'full_push', PUSH, probe.local(source) + '/', push)
    measure(probe, samples, 'unchanged_push', PUSH, probe.local(source) + '/', push)
    marker = source / 'jugg-benchmark-delta.txt'
    marker.write_text('changed real project fixture ' + str(iteration), encoding='utf-8')
    measure(probe, samples, 'changed_push', PUSH, probe.local(source) + '/', push)
    script = ('import pathlib,hashlib,json; p=pathlib.Path(' + repr(probe.remote + f'/source-{iteration}') + '); '
              'print(json.dumps({f.relative_to(p).as_posix():hashlib.sha256(f.read_bytes()).hexdigest() '
              'for f in p.rglob("*") if f.is_file() and not f.is_symlink()}))')
    remote_hashes = json.loads(probe.remote_command('python3 -c ' + shlex.quote(script)))
    if hashes(source) != remote_hashes:
        raise RuntimeError('source_hash_mismatch:exit_1')
    marker.unlink()


def pull_round(probe, artifacts, delta_relative, iteration, samples):
    target = probe.config['target'] + ':'
    download = probe.output / f'artifacts-{iteration}'
    download.mkdir()
    remote = target + probe.remote + '/artifacts/'
    measure(probe, samples, 'full_pull', ['-a', '--delete'], remote, probe.local(download) + '/')
    measure(probe, samples, 'unchanged_pull', ['-a', '--delete'], remote, probe.local(download) + '/')
    # Append to an isolated APK copy; the original build outputs remain untouched.
    probe.remote_command("printf 'benchmark-delta' >> " + shlex.quote(probe.remote + '/artifacts/' + delta_relative))
    with (artifacts / delta_relative).open('ab') as stream:
        stream.write(b'benchmark-delta')
    measure(probe, samples, 'changed_pull', ['-a', '--delete'], remote, probe.local(download) + '/')
    if hashes(artifacts) != hashes(download):
        raise RuntimeError('artifact_hash_mismatch:exit_1')


def benchmark(probe, project, remote_artifacts, rounds, local_artifacts=None):
    probe.phase = 'copy_real_source'
    source = probe.output / 'source'
    shutil.copytree(project, source, symlinks=True, ignore=shutil.ignore_patterns(
        'build', '.gradle', '.git', '.idea', 'local.properties', '*.iml', '.cxx'))
    artifacts = probe.output / 'artifact-seed'
    target = probe.config['target'] + ':'
    if local_artifacts:
        shutil.copytree(local_artifacts, artifacts, symlinks=True)
    else:
        artifacts.mkdir()
        probe.phase = 'fetch_real_artifact_seed'
        probe.transfer(['-a'], target + remote_artifacts.rstrip('/') + '/', probe.local(artifacts) + '/')
    original_hashes = hashes(artifacts)
    apks = [p for p in artifacts.rglob('*.apk') if p.is_file()]
    if not apks or not original_hashes:
        raise RuntimeError('missing_real_artifacts:exit_1')
    delta_relative = apks[0].relative_to(artifacts).as_posix()
    probe.remote = '/tmp/jugg-project-benchmark-' + uuid.uuid4().hex
    probe.phase = 'stage_real_artifacts'
    probe.remote_command('mkdir -p ' + shlex.quote(probe.remote + '/artifacts'))
    probe.transfer(['-a'], probe.local(artifacts) + '/', target + probe.remote + '/artifacts/')
    samples = {p: [] for p in ['full_push', 'unchanged_push', 'changed_push',
                               'full_pull', 'unchanged_pull', 'changed_pull']}
    source_hashes = hashes(source)
    artifact_bytes = sum(p.stat().st_size for p in artifacts.rglob('*') if p.is_file())
    try:
        for iteration in range(rounds):
            push_round(probe, source, iteration, samples)
            pull_round(probe, artifacts, delta_relative, iteration, samples)
        return dict(rounds=rounds, source_files=len(source_hashes),
                    source_bytes=sum(p.stat().st_size for p in source.rglob('*') if p.is_file()),
                    artifact_files=len(original_hashes), artifact_initial_bytes=artifact_bytes,
                    scope='real source and supplied real artifacts; isolated changes; includes SSH startup, excludes local path conversion',
                    verified='remote SHA-256 of every uploaded file; local SHA-256 after each completed pull round',
                    workloads={name: dict(seconds=values, median_seconds=round(statistics.median(values), 4))
                               for name, values in samples.items() if values})
    finally:
        probe.remote_command('rm -rf -- ' + shlex.quote(probe.remote))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--project', type=Path, required=True)
    artifacts = parser.add_mutually_exclusive_group(required=True)
    artifacts.add_argument('--remote-artifacts')
    artifacts.add_argument('--artifacts', type=Path)
    parser.add_argument('--engine', choices=['cygwin'], default='cygwin')
    parser.add_argument('--rounds', type=int, choices=range(1, 6), default=3)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    private = json.loads(args.config.read_text(encoding='utf-8-sig'))
    probe = make_probe(private, args.engine, args.output.resolve())
    try:
        result = dict(engine=args.engine, status='passed',
                      rsync_sha256=hashlib.sha256(Path(probe.config['rsync']).read_bytes()).hexdigest(), **benchmark(
            probe, args.project.resolve(), args.remote_artifacts, args.rounds, args.artifacts))
    except Exception as error:
        result = dict(engine=args.engine, status='failed', **failure(probe.phase, error))
        if isinstance(error, OSError):
            result.update(errno=error.errno, winerror=getattr(error, 'winerror', None),
                          file_basename=Path(error.filename).name if error.filename else None)
    (args.output / 'summary.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result, indent=2))
    return int(result['status'] != 'passed')


if __name__ == '__main__':
    try:
        raise SystemExit(main())
    except Exception as error:
        print(json.dumps(dict(status='blocked', error_type=type(error).__name__)))
        raise SystemExit(1)
