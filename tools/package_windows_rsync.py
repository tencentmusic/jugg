"""Build a pinned runtime from an explicitly staged and audited directory."""

import argparse
import hashlib
import json
from pathlib import Path
import zipfile


def package(source, destination, engine, rsync, ssh, cygpath):
    source = source.resolve()
    entries = sorted(path for path in source.rglob('*') if path.is_file())
    manifest = {'engine': engine, 'rsync': rsync, 'ssh': ssh}
    if engine == 'cygwin':
        manifest['cygpath'] = cygpath
    for name in list(manifest.values())[1:]:
        path = source / name
        if not path.resolve().is_relative_to(source) or not path.is_file():
            raise ValueError('Missing or unsafe runtime executable: ' + str(name))
    if not (source / 'provenance.json').is_file() or not (source / 'licenses').is_dir():
        raise ValueError('Stage provenance.json and licenses/ before packaging')
    if destination.resolve().is_relative_to(source):
        raise ValueError('Output ZIP must be outside the staged directory')
    if any(path.is_symlink() for path in source.rglob('*')):
        raise ValueError('Stage regular files only; runtime symlinks are unsupported')
    manifest['sha256'] = {path.relative_to(source).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
                          for path in entries if path.name != 'runtime.json'}
    destination.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(destination, 'w', zipfile.ZIP_DEFLATED) as archive:
        for path in entries:
            if path.name != 'runtime.json':
                archive.write(path, path.relative_to(source).as_posix())
        archive.writestr('runtime.json', json.dumps(manifest, indent=2))
    print('SHA-256: ' + hashlib.sha256(destination.read_bytes()).hexdigest())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--engine', choices=['oc-rsync', 'cygwin'], required=True)
    parser.add_argument('--rsync', default='bin/rsync.exe')
    parser.add_argument('--ssh', default='bin/ssh.exe')
    parser.add_argument('--cygpath', default='bin/cygpath.exe')
    args = parser.parse_args()
    package(args.input, args.output, args.engine, args.rsync, args.ssh, args.cygpath)


if __name__ == '__main__':
    main()
