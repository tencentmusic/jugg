#!/usr/bin/env python3
"""Store local demo baseline records and ZIP artifacts."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
import uuid
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs, urlparse
from zipfile import is_zipfile

DEFAULT_DATA_FILE = Path(__file__).resolve().with_name('base-builds.json')
MAX_RECORDS_PER_PROJECT = 100
CMD_LINE_ZIP = Path(__file__).resolve().with_name('cmd_line.zip')


class Handler(BaseHTTPRequestHandler):
    def _data(self):
        try:
            return json.loads(Path(self.server.data_file).read_text(encoding='utf-8'))
        except FileNotFoundError:
            return []

    def _send(self, code, value):
        body = json.dumps(value, ensure_ascii=False).encode('utf-8')
        self.send_response(code)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        url = urlparse(self.path)
        if url.path == '/cmd_line.zip':
            return self._send_zip(CMD_LINE_ZIP, 'cmd_line.zip')
        if url.path.startswith('/artifacts/'):
            artifact_id = url.path[len('/artifacts/'):]
            if not re.fullmatch('[0-9a-f]{32}', artifact_id):
                return self._send(404, {'error': '文件不存在'})
            path = Path(self.server.data_file).parent / 'artifacts' / (artifact_id + '.zip')
            return self._send_zip(path, 'jugg.zip')
        if url.path not in ('/base-builds', '/base-builds/available'):
            return self._send(404, {'error': '接口不存在'})
        query = parse_qs(url.query)
        project_id = query.get('projectId', ['default'])[0]
        print('GET 请求参数：' + json.dumps(dict(path=url.path, query=query), ensure_ascii=False), flush=True)
        data = [r for r in self._data() if r.get('projectId', 'default') == project_id]
        if url.path == '/base-builds/available':
            data = [r for r in data if all(r.get(key) == query.get(key, [''])[0]
                                          for key in ('scenarioKey', 'compileCommand'))]
        # Sort records without a server timestamp after timestamped records.
        data = [dict(record, uploadedAt=record.get('uploadedAt')) for record in reversed(data)]
        data.sort(key=lambda record: record['uploadedAt'] or '', reverse=True)
        print('GET 返回列表：' + json.dumps(data, ensure_ascii=False), flush=True)
        self._send(200, data)

    def _send_zip(self, path, filename):
        try:
            stream = path.open('rb')
        except FileNotFoundError:
            return self._send(404, {'error': '文件不存在'})
        with stream:
            self.send_response(200)
            self.send_header('Content-Type', 'application/zip')
            self.send_header('Content-Disposition', f'attachment; filename="{filename}"')
            self.send_header('Content-Length', str(os.fstat(stream.fileno()).st_size))
            self.end_headers()
            shutil.copyfileobj(stream, self.wfile)

    def do_PUT(self):
        url = urlparse(self.path)
        if url.path != '/base-builds':
            return self._send(404, {'error': '接口不存在'})
        project_id = parse_qs(url.query).get('projectId', ['default'])[0]
        try:
            record = json.loads(self.rfile.read(int(self.headers.get('Content-Length', '0'))))
            if not isinstance(record, dict) or record.get('projectId', project_id) != project_id:
                raise ValueError('项目标识不一致')
            if not isinstance(record.get('id'), str) or not record['id'] or not project_id:
                raise ValueError('需要非空 id 和 projectId')
        except ValueError as exception:
            return self._send(400, {'error': f'无效记录：{exception}'})
        record['projectId'] = project_id
        record['uploadedAt'] = datetime.now(timezone.utc).isoformat()
        print('PUT 请求参数：' + json.dumps(dict(query=project_id, record=record), ensure_ascii=False), flush=True)
        data = self._data()
        others = [r for r in data if r.get('projectId', 'default') != project_id]
        current = [r for r in data if r.get('projectId', 'default') == project_id and r.get('id') != record['id']]
        current.append(record)
        try:
            self._save(others + current[-MAX_RECORDS_PER_PROJECT:])
        except OSError as exception:
            return self._send(500, {'error': f'记录保存失败：{exception}'})
        self._send(200, record)

    def do_DELETE(self):
        url = urlparse(self.path)
        if url.path != '/base-builds':
            return self._send(404, {'error': '接口不存在'})
        query = parse_qs(url.query, keep_blank_values=True)
        commits = query.get('baseCommit', [])
        projects = query.get('projectId')
        if (len(commits) != 1 or not commits[0].strip()
                or projects is not None and (len(projects) != 1 or not projects[0].strip())):
            return self._send(400, {'error': '需要唯一非空 baseCommit，projectId 可选且非空'})
        commit, project = commits[0], projects[0] if projects is not None else None
        try:
            result = self._delete_baselines(commit, project)
        except (OSError, ValueError) as exception:
            return self._send(500, {'error': f'基线删除失败：{exception}'})
        print('DELETE 基线：' + json.dumps(dict(baseCommit=commit, projectId=project, **result),
                                        ensure_ascii=False), flush=True)
        self._send(200, result)

    def _delete_baselines(self, commit, project):
        records = self._data()
        deleted = [record for record in records if record.get('baseCommit') == commit
                   and (project is None or record.get('projectId', 'default') == project)]
        remaining = [record for record in records if record not in deleted]
        if not deleted:
            return dict(deletedRecords=0, deletedArtifacts=0)
        referenced = [record.get('artifactId') for record in remaining]
        identifiers = {record['artifactId'] for record in deleted
                       if isinstance(record.get('artifactId'), str)
                       and re.fullmatch('[0-9a-f]{32}', record['artifactId'])
                       and record['artifactId'] not in referenced}
        root = Path(self.server.data_file).parent
        # Stage ZIP deletions so an index write failure can be rolled back.
        staged = Path(tempfile.mkdtemp(prefix='delete-baselines-', dir=root))
        moved = []
        try:
            for identifier in identifiers:
                original = root / 'artifacts' / (identifier + '.zip')
                try:
                    original.replace(staged / original.name)
                except FileNotFoundError:
                    continue
                moved.append(original)
            self._save(remaining)
        except OSError:
            for original in moved:
                (staged / original.name).replace(original)
            staged.rmdir()
            raise
        shutil.rmtree(staged)
        return dict(deletedRecords=len(deleted), deletedArtifacts=len(moved))

    def do_POST(self):
        if urlparse(self.path).path != '/artifacts':
            return self._send(404, {'error': '接口不存在'})
        try:
            length = int(self.headers.get('Content-Length', '0'))
            if length <= 0:
                raise ValueError('需要 Content-Length 和 ZIP 请求体')
            root = Path(self.server.data_file).parent / 'artifacts'
            root.mkdir(parents=True, exist_ok=True)
            with tempfile.TemporaryDirectory(dir=root) as temporary:
                staged = Path(temporary) / 'upload.zip'
                self._receive_file(staged, length)
                if not is_zipfile(staged):
                    raise ValueError('请求体必须是 ZIP 文件')
                artifact_id = uuid.uuid4().hex
                os.replace(staged, root / (artifact_id + '.zip'))
        except ValueError as exception:
            return self._send(400, {'error': f'无效文件上传：{exception}'})
        except OSError as exception:
            return self._send(500, {'error': f'文件上传失败：{exception}'})
        self._send(201, {'artifactId': artifact_id, 'uploadedAt': datetime.now(timezone.utc).isoformat()})

    def _receive_file(self, path, length):
        with path.open('wb') as stream:
            while length:
                chunk = self.rfile.read(min(length, 1024 * 1024))
                if not chunk:
                    raise ValueError('请求体不完整')
                stream.write(chunk)
                length -= len(chunk)

    def _save(self, data):
        path = Path(self.server.data_file)
        path.parent.mkdir(parents=True, exist_ok=True)
        fd, temporary = tempfile.mkstemp(dir=path.parent)
        try:
            with os.fdopen(fd, 'w', encoding='utf-8') as stream:
                json.dump(data, stream, ensure_ascii=False, indent=2)
            os.replace(temporary, path)
        finally:
            if os.path.exists(temporary):
                os.unlink(temporary)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=18766)
    parser.add_argument('--data-file', type=Path, default=DEFAULT_DATA_FILE,
                        help='元数据文件，默认在脚本同级保存 base-builds.json')
    args = parser.parse_args()
    server = HTTPServer((args.host, args.port), Handler)
    server.data_file = str(args.data_file.resolve())
    print(f'基线服务：http://{args.host}:{server.server_port}', flush=True)
    print(f'数据文件：{server.data_file}；每个项目最多保留 {MAX_RECORDS_PER_PROJECT} 条记录', flush=True)
    print(f'文件存储：{Path(server.data_file).parent / "artifacts"}', flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == '__main__':
    main()
