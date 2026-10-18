"""Run credential-safe Windows transport acceptance against tiny Linux fixtures."""
import argparse
import hashlib
import json
import re
import uuid
import os
from pathlib import Path
import shlex
import subprocess
import sys

from windows_rsync_probe import Probe, PUSH, write
from windows_rsync_benchmark import benchmark


class SafeProbe(Probe):
    phase = 'initialization'

    def transfer(self, args, source, dest, expected=0, bad_key=False):
        try:
            result = super().transfer(args, source, dest, expected, bad_key)
            if bad_key:
                assert b'permission denied' in self.last_stderr
            return result
        except RuntimeError:
            if (self.config['engine'] != 'cygwin' or not source.startswith(self.config['target'] + ':')
                    or not getattr(self, 'recoverable_link_creation', False)):
                raise
            self.link_retry_used = True
            return super().transfer(args, source, dest, expected, bad_key)

    def save(self, status):
        pass

    def run(self, argv, expected=0):
        result = subprocess.run(argv, env=self.env, capture_output=True, timeout=self.config.get('timeout', 30))
        self.last_stderr = result.stderr.lower()
        if (result.returncode == 0 if expected == 'nonzero' else result.returncode != expected):
            diagnostic = result.stderr.lower()
            self.recoverable_link_creation = (result.returncode == 23 and b'symlink ' in diagnostic
                                              and b'failed: no such file or directory' in diagnostic)
            category = 'command_failed'
            for needle, label in [(b'unprotected private key', 'private_key_permissions_too_open'),
                                  (b'bad permissions', 'private_key_permissions_too_open'),
                                  (b'invalid format', 'private_key_invalid_format'),
                                  (b'error in libcrypto', 'private_key_format_or_crypto'),
                                  (b'could not resolve hostname', 'hostname_resolution'),
                                  (b'no such identity', 'private_key_path_not_accessible'),
                                  (b'cannot run', 'cannot_launch_ssh_dependency'),
                                  (b'cygheap', 'cygwin_runtime_conflict'),
                                  (b'host key is known', 'host_key_changed'),
                                  (b'remote host identification has changed', 'host_key_changed'),
                                  (b'failed to add', 'known_hosts_write_failure'),
                                  (b'could not create', 'ssh_directory_creation_failure'),
                                  (b'identity file', 'private_key_path_or_load'),
                                  (b'permission denied', 'authentication_or_permission'),
                                  (b'host key verification failed', 'host_key'),
                                  (b'connection refused', 'connection_refused'),
                                  (b'no such file', 'missing_file'),
                                  (b'unknown option', 'unsupported_option')]:
                if needle in diagnostic:
                    category = label
                    break
            raise RuntimeError(category + ':exit_' + str(result.returncode))
        return result.stdout.decode('utf-8', errors='replace')


def failure(phase, error):
    result = dict(phase=phase, error_type=type(error).__name__)
    if type(error) is RuntimeError and re.fullmatch(r'[a-z_]+:exit_-?\d+', str(error)):
        result['category'] = str(error)
    return result


def changed_host_key(probe):
    known = Path(probe.config['known_hosts'])
    original = known.read_text()
    fake = Path(probe.config['bad_key'] + '.pub').read_text().split()
    lines = []
    for line in original.splitlines():
        fields = line.split()
        if len(fields) >= 3:
            fields[1:3] = fake[:2]
        lines.append(' '.join(fields))
    known.write_text('\n'.join(lines) + '\n')
    try:
        probe.run(probe.ssh() + ['-o', 'HostKeyAlgorithms=ssh-ed25519',
                                 probe.config['target'], 'printf UNEXPECTED'], expected='nonzero')
        assert b'host key verification failed' in probe.last_stderr
    finally:
        known.write_text(original)


def simulated_artifacts(probe):
    # Cover all 23 allBuildPaths entries, custom paths, APK and library diff.
    dirs = ['tmp/kotlin-classes/debug', 'intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes',
            'classes/kotlin/android/main', 'classes/kotlin/jvm/main',
            'intermediates/javac/debug/classes', 'intermediates/javac/debug/compileDebugJavaWithJavac/classes',
            'intermediates/compile_and_runtime_not_namespaced_r_class_jar/debug',
            'intermediates/compile_r_class_jar/debug', 'classes/kotlin/main', 'classes/java/main',
            'generated', 'intermediates/library_manifest/debug', 'intermediates/merged_manifest/debug',
            'intermediates/merged_manifests/debug', 'intermediates/compile_only_not_namespaced_r_class_jar/debug',
            'intermediates/data_binding_base_class_log_artifact/debug',
            'intermediates/data_binding_base_class_logs_dependency_artifacts/debug',
            'intermediates/data_binding_artifact/debug',
            'intermediates/data_binding_layout_info_type_merge/debug',
            'intermediates/data_binding_layout_info_type_package/debug']
    files = ['app/build/' + p + '/fixture.txt' for p in dirs]
    files += ['app/build/outputs/mapping/debug/mapping.txt', 'app/build/outputs/mapping/debug/usage.txt',
              'app/build/outputs/bundle/debug/resources-mapping.txt', 'app/custom-classpath/fixture.jar',
              'app/custom-sync/fixture.txt', 'app/build/outputs/apk/debug/app-debug.apk',
              'build/jugg/tmp/diff/fixture.json']
    probe.phase = 'create_tiny_artifacts'
    script = 'from pathlib import Path; p=Path(' + repr(probe.remote) + '); names=' + repr(files) + '; '
    script += '[( (p/n).parent.mkdir(parents=True,exist_ok=True), (p/n).write_bytes(bytes([116,105,110,121])) ) for n in names]'
    probe.remote_command('python3 -c ' + shlex.quote(script))
    probe.phase = 'simulated_artifact_pull'
    dest = probe.output / 'pull'
    dest.mkdir(exist_ok=True)
    filters = ['-av', '--delete', '--delete-excluded', '--prune-empty-dirs', '--include=*/']
    filters += ['--include=' + p for p in files] + ['--exclude=*']
    probe.transfer(filters, probe.config['target'] + ':' + probe.remote + '/', probe.local(dest) + '/')
    assert all((dest / p).read_bytes() == bytes([116,105,110,121]) for p in files)
    return len(files)


def run_probe(probe, record, benchmark_rounds=0):
    try:
        probe.phase = 'ssh_linux_rsync_python'
        result = probe.remote_command('test "$(uname -s)" = Linux && command -v rsync >/dev/null && command -v python3 >/dev/null && printf READY')
        assert result.strip() == 'READY'
        record['checks'].append(probe.phase)
        version = probe.remote_command('rsync --version')
        record['remote_rsync_version'] = re.search(r'version\s+([0-9.]+)', version).group(1)
        probe.phase = 'temporary_workspace'
        probe.remote = probe.remote_command('mktemp -d /tmp/jugg-rsync-probe-XXXXXXXX').strip()
        assert probe.remote.startswith('/tmp/jugg-rsync-probe-') and '\n' not in probe.remote
        record['checks'].append(probe.phase)
        probe.phase = 'filtered_push'
        if benchmark_rounds:
            probe.phase = 'benchmark'
            record['benchmark'] = benchmark(probe, benchmark_rounds)
            record['checks'].append(probe.phase)
            probe.phase = 'filtered_push'
        source = probe.output / 'source \u4e2d\u6587 space'
        write(source, 'src/preflight.txt', 'small preflight fixture')
        probe.transfer(PUSH, probe.local(source) + '/', probe.config['target'] + ':' + probe.remote + '/')
        record['checks'].append(probe.phase)
        record['verified_artifact_files'] = simulated_artifacts(probe)
        record['checks'].append('simulated_artifact_pull')
        probe.phase = 'file_incremental_delete_failure_matrix'
        probe.files()
        record['checks'].append(probe.phase)
        record['status'] = 'passed'
        for phase, action in [('changed_host_key', lambda: changed_host_key(probe)),
                              ('native_symlink_matrix', probe.links)]:
            probe.phase = phase
            try:
                action()
                record['checks'].append(phase)
                if getattr(probe, 'link_retry_used', False): record['link_retry_used'] = True
            except Exception as error:
                record.setdefault('failures', []).append(failure(phase, error))
                record['status'] = 'failed'
    except Exception as error:
        record.update(status='failed', **failure(probe.phase, error))
    finally:
        if probe.remote:
            try:
                probe.remote_command('rm -rf -- ' + shlex.quote(probe.remote))
                record['temporary_workspace_cleaned'] = True
            except Exception:
                record['temporary_workspace_cleaned'] = False


def main():
    # The private document is consumed only inside this process, never echoed.
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, default=os.environ.get('JUGG_REMOTE_CONFIG_FILE', Path.home() / '.jugg/remote.json'))
    parser.add_argument('--output', type=Path, default=Path('verification/windows-rsync/acceptance'))
    parser.add_argument('--cygwin-root', type=Path, default=Path('D:/cygwin64'))
    parser.add_argument('--benchmark-rounds', type=int, choices=range(1, 11), default=None)
    args = parser.parse_args()
    root = args.output.resolve()
    root.mkdir(parents=True, exist_ok=True)
    with args.config.open(encoding='utf-8-sig') as stream:
        private = json.load(stream)
    secret = private.get('remoteSshPassword', '')
    specified = Path(secret).expanduser() if secret else None
    keys = [str(specified.resolve())] if specified and specified.is_file() else []
    password = secret if secret and not keys else None
    report = {'key_auth_available': bool(keys), 'password_auth_configured': bool(password), 'engines': []}
    if not keys and not password:
        report['blocker'] = 'no_private_key_file_found'
    bad_key = root / 'wrong-key'
    if not bad_key.exists():
        key_path = subprocess.run([str(args.cygwin_root / 'bin/cygpath.exe'), '-u', str(bad_key)],
                                  capture_output=True, check=True, text=True).stdout.strip()
        result = subprocess.run([str(args.cygwin_root / 'bin/ssh-keygen.exe'), '-q', '-t', 'ed25519',
                                 '-N', '', '-f', key_path], capture_output=True)
        if result.returncode: raise RuntimeError('fixture_key_generation_failed')
    for engine, rsync, ssh in [
        ('cygwin', str(args.cygwin_root / 'bin/rsync.exe'), str(args.cygwin_root / 'bin/ssh.exe'))]:
        record = {'engine': engine, 'checks': [], 'rsync_sha256': hashlib.sha256(Path(rsync).read_bytes()).hexdigest()}
        report['engines'].append(record)
        if not keys and not password:
            continue
        output = root / ('matrix-' + engine + '-' + uuid.uuid4().hex[:8])
        output.mkdir(exist_ok=True)
        config = dict(engine=engine, rsync=rsync, ssh=ssh, cygpath=str(args.cygwin_root / 'bin/cygpath.exe'),
                      target=private['remoteSshUser'] + '@' + private['remoteSshIp'],
                      port=private['remoteSshPort'], keys=keys, password=password, bad_key=str(bad_key),
                      known_hosts=str(output / 'known_hosts'))
        probe = SafeProbe(config, output)
        if password:
            helper = Path('main/src/main/resources/tools/windows/rsync/askpass.exe').resolve()
            probe.env.update(SSH_ASKPASS=probe.local(helper), SSH_ASKPASS_REQUIRE='force',
                             DISPLAY='jugg:0', JUGG_RSYNC_PASSWORD=password)
        run_probe(probe, record, args.benchmark_rounds or 0)
    (root / 'preflight-report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    print(json.dumps(report, indent=2))
    return 1 if any(e.get('status') != 'passed' for e in report['engines']) else 0


if __name__ == '__main__':
    try:
        sys.exit(main())
    except Exception as error:
        print(json.dumps({'status': 'blocked', 'phase': 'private_config_validation', 'error_type': type(error).__name__}))
        sys.exit(1)
