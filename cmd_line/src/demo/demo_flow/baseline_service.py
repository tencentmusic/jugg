"""Transfer baseline records and ZIP artifacts over HTTP."""
import json
import os
from pathlib import Path
import shutil
import stat
import tempfile
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen
import zipfile


def records(server, project_id, record=None):
    url = server.rstrip('/') + '/base-builds?' + urlencode({'projectId': project_id})
    request = Request(url, data=None if record is None else json.dumps(record).encode(),
                      method='GET' if record is None else 'PUT',
                      headers={'Content-Type': 'application/json'})
    with urlopen(request, timeout=20) as response:
        return json.load(response)


def upload(server, root):
    # Stream the archive and dereference local symlinks when packing.
    with tempfile.TemporaryFile() as stream:
        with zipfile.ZipFile(stream, 'w', zipfile.ZIP_DEFLATED) as archive:
            archive.writestr('jugg/', '')
            for directory, _, files in os.walk(root, followlinks=True):
                for name in sorted(files):
                    path = Path(directory) / name
                    archive.write(path, 'jugg/' + path.relative_to(root).as_posix())
        size = stream.tell()
        stream.seek(0)
        request = Request(server.rstrip('/') + '/artifacts', data=stream, method='POST',
                          headers={'Content-Type': 'application/zip', 'Content-Length': str(size)})
        with urlopen(request, timeout=600) as response:
            return json.load(response)['artifactId']


def download(server, artifact_id, destination):
    url = server.rstrip('/') + '/artifacts/' + quote(artifact_id, safe='')
    with tempfile.TemporaryFile() as stream:
        with urlopen(url, timeout=600) as response:
            shutil.copyfileobj(response, stream)
        with zipfile.ZipFile(stream) as archive:
            for entry in archive.infolist():
                name = entry.filename
                kind = stat.S_IFMT(entry.external_attr >> 16)
                if (not name.startswith('jugg/') or '..' in Path(name).parts or '\\' in name
                        or kind not in (0, stat.S_IFREG, stat.S_IFDIR)):
                    raise ValueError(f'Invalid baseline ZIP entry: {name}')
            archive.extractall(destination.parent)
    if not destination.is_dir():
        raise ValueError('Baseline ZIP is missing the jugg/ directory')
