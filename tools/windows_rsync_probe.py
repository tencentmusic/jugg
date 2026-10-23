"""Independent Windows Cygwin rsync SSH probe; never opens Jugg support gates."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import subprocess
import sys
import time
import uuid


# Snapshot of SyncFileCommand and JuggPathManager's transfer-root filters.
PUSH = [
    '-av', '--delete',
    '--include=/.gradle/', '--include=/.gradle/jugg/', '--include=/.gradle/jugg/**',
    '--exclude=/.gradle/**', '--include=/build/', '--include=/build/jugg/',
    '--include=/build/jugg/config/', '--include=/build/jugg/config/**',
    '--include=/build/jugg/database/', '--include=/build/jugg/database/project_infos.db/',
    '--include=/build/jugg/database/project_infos.db/project_infos.json',
    '--exclude=/build/**', '--exclude=build/', '--exclude=local.properties',
    '--exclude=.idea/', '--exclude=*.iml', '--exclude=.git/objects/',
    '--exclude=.git/modules/', '--exclude=.cxx/',
]


def write(root, name, content):
    target = root / name
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding='utf-8')
    return target


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class Probe:
    def __init__(self, config, output):
        self.config = config
        self.output = output
        self.events = []
        self.remote = None
        self.env = dict(os.environ)
        self.env['CYGWIN'] = 'winsymlinks:nativestrict'
        # Keep runtime DLL discovery beside executables; exclude Git/MSYS2 from PATH.
        self.env['PATH'] = str(Path(config['rsync']).parent) + os.pathsep + str(
            Path(os.environ.get('SystemRoot', 'C:/Windows')) / 'System32')

    def run(self, argv, expected=0):
        started = time.monotonic()
        event = {'argv': argv}
        self.events.append(event)
        try:
            result = subprocess.run(argv, env=self.env, capture_output=True,
                                    timeout=self.config.get('timeout', 60))
            event.update(exit_code=result.returncode,
                         stdout=result.stdout.decode('utf-8', errors='replace'),
                         stderr=result.stderr.decode('utf-8', errors='replace'))
            if expected == 'nonzero':
                assert result.returncode != 0, event
            else:
                assert result.returncode == expected, event
            return event['stdout']
        except Exception as error:
            event['error'] = str(error)
            raise
        finally:
            event['seconds'] = time.monotonic() - started
            self.save('running')

    def save(self, status):
        report = {'status': status, 'engine': self.config['engine'],
                  'remote_fixture': self.remote, 'events': self.events}
        (self.output / 'report.json').write_text(
            json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')

    def local(self, path):
        path = str(Path(path).resolve())
        return self.run([self.config['cygpath'], '-u', path]).strip()

    def ssh(self, bad_key=False):
        keys = [self.config['bad_key']] if bad_key else self.config['keys']
        password = bool(self.config.get('password')) and not bad_key
        args = [self.config['ssh'], '-F', '/dev/null',
                '-p', str(self.config['port']), '-o', 'PubkeyAuthentication=' + ('no' if password else 'yes'),
                '-o', 'PreferredAuthentications=' + ('password,keyboard-interactive' if password else 'publickey'),
                '-o', 'NumberOfPasswordPrompts=1',
                '-o', 'BatchMode=' + ('no' if password else 'yes'), '-o', 'IdentitiesOnly=yes',
                '-o', 'IdentityAgent=none', '-o', 'ConnectTimeout=10',
                '-o', 'StrictHostKeyChecking=accept-new', '-o',
                'UserKnownHostsFile=' + self.local(self.config['known_hosts'])]
        for key in keys:
            args += ['-i', self.local(key)]
        return args

    def remote_command(self, script):
        return self.run(self.ssh() + [self.config['target'], script])

    def transfer(self, args, source, dest, expected=0, bad_key=False):
        # rsync parses -e itself even though the outer process uses argv.
        ssh = self.ssh(bad_key)
        ssh[0] = self.local(ssh[0])
        return self.run([self.config['rsync'], '-e', shlex.join(ssh)] +
                        args + [source, dest], expected)

    def check_remote(self, expression):
        script = 'from pathlib import Path; import os; p=Path(' + repr(self.remote) + '); '
        script += 'assert ' + expression
        self.remote_command('python3 -c ' + shlex.quote(script))

    def prepare(self):
        for name in ['rsync', 'ssh'] + (['cygpath'] if self.config['engine'] == 'cygwin' else []):
            path = Path(self.config[name])
            assert path.is_absolute() and path.is_file(), 'Missing absolute executable: ' + str(path)
            self.events.append({'tool': name, 'path': str(path), 'sha256': digest(path)})
        self.run([self.config['rsync'], '--version'])
        self.run([self.config['ssh'], '-V'])
        self.remote_command('uname -s; rsync --version; python3 --version')
        self.remote = self.remote_command('mktemp -d /tmp/jugg-rsync-probe-XXXXXXXX').strip()
        assert self.remote.startswith('/tmp/jugg-rsync-probe-') and '\n' not in self.remote

    def files(self):
        source = self.output / '工程 source & (test)'
        fixture = {'src/中文 file.txt': 'source data', '.gradle/jugg/config.txt': 'config',
                   'build/jugg/config/config.txt': 'build config',
                   'build/jugg/database/project_infos.db/project_infos.json': '{}',
                   '.gradle/cache/no.txt': 'excluded', 'build/no.txt': 'excluded',
                   'local.properties': 'excluded', 'app/build/no.txt': 'excluded'}
        for name, content in fixture.items():
            write(source, name, content)
        before = {name: digest(source / name) for name in fixture}
        dest = self.config['target'] + ':' + self.remote + '/'
        self.remote_command('touch ' + shlex.quote(self.remote + '/obsolete.txt'))
        self.transfer(PUSH, self.local(source) + '/', dest)
        self.check_remote("(p/'src/中文 file.txt').read_text() == 'source data'")
        for name in ['.gradle/jugg/config.txt', 'build/jugg/config/config.txt',
                     'build/jugg/database/project_infos.db/project_infos.json']:
            self.check_remote('(p/' + repr(name) + ').is_file()')
        for name in ['obsolete.txt', 'local.properties', '.gradle/cache/no.txt',
                     'build/no.txt', 'app/build/no.txt']:
            self.check_remote('not (p/' + repr(name) + ').exists()')
        stamp = self.remote_command('stat -c %Y ' + shlex.quote(self.remote + '/src/中文 file.txt'))
        self.transfer(PUSH, self.local(source) + '/', dest)
        assert stamp == self.remote_command('stat -c %Y ' + shlex.quote(self.remote + '/src/中文 file.txt'))
        assert before == {name: digest(source / name) for name in fixture}
        write(source, 'src/中文 file.txt', 'updated source with different size')
        self.transfer(PUSH, self.local(source) + '/', dest)
        self.check_remote("(p/'src/中文 file.txt').read_text() == 'updated source with different size'")
        download = self.output / 'download 中文 & (test)'
        download.mkdir()
        write(download, 'obsolete.txt', 'remove me')
        filters = ['-av', '--prune-empty-dirs', '--include=*/', '--include=src/**', '--exclude=*']
        self.transfer(filters, dest, self.local(download) + '/')
        assert (download / 'obsolete.txt').exists()
        self.transfer(filters[:1] + ['--delete', '--delete-excluded'] + filters[1:],
                      dest, self.local(download) + '/')
        assert not (download / 'obsolete.txt').exists()
        assert not (download / 'build').exists()
        assert digest(download / 'src/中文 file.txt') == digest(source / 'src/中文 file.txt')
        assert abs((download / 'src/中文 file.txt').stat().st_mtime -
                   (source / 'src/中文 file.txt').stat().st_mtime) < 2
        self.transfer(['-av', '--rsync-path=/jugg-probe-missing-rsync'],
                      self.local(source) + '/', dest, expected='nonzero')
        if self.config.get('bad_key'):
            self.transfer(PUSH, self.local(source) + '/', dest,
                          expected='nonzero', bad_key=True)
        else:
            self.events.append({'case': 'wrong key', 'status': 'not_run'})

    def links(self):
        source = self.output / 'links source'
        source.mkdir()
        write(source, 'internal.txt', 'internal')
        external = write(self.output, 'external.txt', 'external')
        os.symlink('internal.txt', source / 'inside.txt')
        os.symlink('../external.txt', source / 'outside.txt')
        os.symlink(str(external.resolve()), source / 'absolute.txt')
        dest = self.config['target'] + ':' + self.remote + '/links/'
        self.remote_command('mkdir ' + shlex.quote(self.remote + '/links'))
        self.transfer(PUSH, self.local(source) + '/', dest)
        self.check_remote("(p/'links/inside.txt').is_symlink()")
        for name in ['outside.txt', 'absolute.txt']:
            self.check_remote("(p/'links'/" + repr(name) + ").is_symlink()")
        # Outside targets are deliberately not materialized or downloaded.
        self.remote_command('rm -- ' + shlex.quote(self.remote + '/links/outside.txt') + ' ' +
                            shlex.quote(self.remote + '/links/absolute.txt'))
        download = self.output / 'links download'
        download.mkdir()
        self.transfer(['-av'], dest, self.local(download) + '/')
        assert (download / 'inside.txt').is_symlink()
        assert (download / 'inside.txt').read_text() == 'internal'
        assert (source / 'outside.txt').is_symlink() and external.read_text() == 'external'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--output', type=Path, default=Path('verification/windows-rsync'))
    parser.add_argument('--links', action='store_true', help='Requires Windows native symlink permission')
    args = parser.parse_args()
    config = json.loads(args.config.read_text(encoding='utf-8-sig'))
    assert config['engine'] == 'cygwin'
    output = args.output.resolve() / (config['engine'] + '-' + uuid.uuid4().hex[:10])
    output.mkdir(parents=True)
    probe = Probe(config, output)
    try:
        assert sys.platform == 'win32', 'Run the actual transport probe on Windows'
        probe.prepare()
        probe.files()
        if args.links:
            probe.links()
        probe.events.append({'case': 'links', 'status': 'passed' if args.links else 'not_run'})
        probe.save('passed_selected_cases')
        print('Selected cases passed. Evidence: ' + str(output))
    except Exception as error:
        probe.save('failed')
        print(str(error) + '\nEvidence: ' + str(output), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
